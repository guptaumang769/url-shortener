package com.umang.urlshortener.service;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * Distributed token-bucket rate limiter backed by Redis.
 *
 * <p>Why token bucket? It allows short bursts (up to the bucket capacity) while capping
 * the sustained rate (the refill rate) — a better fit for real traffic than a fixed
 * window, which lets 2× the limit through at a window boundary.
 *
 * <p>Why a Lua script? The check-and-decrement must be atomic. If we did GET then SET
 * from Java, two concurrent requests could both read "1 token left" and both proceed —
 * classic race. Redis runs the whole script atomically on the server, so the read,
 * refill calculation, and token deduction happen as one indivisible step. This is the
 * canonical way to build a correct distributed limiter.
 */
@Service
public class RateLimiterService {

    // KEYS[1] = bucket key. ARGV: capacity, refillPerSec, nowMillis, requestedTokens.
    // Stores two fields: the current token count and the last-refill timestamp.
    private static final String LUA = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refillPerSec = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])

            local bucket = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(bucket[1])
            local ts = tonumber(bucket[2])
            if tokens == nil then
                tokens = capacity
                ts = now
            end

            -- Refill based on elapsed time since the last call.
            local elapsed = math.max(0, now - ts) / 1000.0
            tokens = math.min(capacity, tokens + elapsed * refillPerSec)

            local allowed = 0
            if tokens >= requested then
                tokens = tokens - requested
                allowed = 1
            end

            redis.call('HMSET', key, 'tokens', tokens, 'ts', now)
            -- Expire idle buckets so we don't leak keys for one-off clients.
            redis.call('PEXPIRE', key, math.ceil(capacity / refillPerSec * 1000) + 1000)
            return allowed
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script;
    private final long capacity;
    private final long refillPerSec;

    public RateLimiterService(StringRedisTemplate redis,
                              org.springframework.core.env.Environment env) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>(LUA, Long.class);
        this.capacity = Long.parseLong(env.getProperty("ratelimit.capacity", "20"));
        this.refillPerSec = Long.parseLong(env.getProperty("ratelimit.refill-per-sec", "10"));
    }

    /**
     * @param clientId typically the caller's IP or API key
     * @return true if the request is allowed, false if the bucket is empty
     */
    public boolean tryAcquire(String clientId) {
        Long allowed = redis.execute(
                script,
                List.of("ratelimit:" + clientId),
                String.valueOf(capacity),
                String.valueOf(refillPerSec),
                String.valueOf(System.currentTimeMillis()),
                "1");
        return allowed != null && allowed == 1L;
    }
}
