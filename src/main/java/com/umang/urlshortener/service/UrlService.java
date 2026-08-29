package com.umang.urlshortener.service;

import com.umang.urlshortener.exception.AliasTakenException;
import com.umang.urlshortener.exception.NotFoundException;
import com.umang.urlshortener.model.dto.ShortenRequest;
import com.umang.urlshortener.model.dto.ShortenResponse;
import com.umang.urlshortener.model.dto.UrlStatsResponse;
import com.umang.urlshortener.model.entity.UrlMapping;
import com.umang.urlshortener.repository.UrlMappingRepository;
import com.umang.urlshortener.util.Base62;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UrlService {

    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final String CACHE_PREFIX = "url:";

    private final UrlMappingRepository repository;
    private final StringRedisTemplate redis;
    private final String baseUrl;

    public UrlService(UrlMappingRepository repository,
                      StringRedisTemplate redis,
                      @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.repository = repository;
        this.redis = redis;
        this.baseUrl = baseUrl;
    }

    @Transactional
    public ShortenResponse shorten(ShortenRequest request, String createdBy) {
        Instant expiresAt = request.ttlSeconds() == null
                ? null
                : Instant.now().plusSeconds(request.ttlSeconds());

        UrlMapping mapping = UrlMapping.builder()
                .longUrl(request.url())
                .createdBy(createdBy)
                .clickCount(0L)
                .expiresAt(expiresAt)
                .createdAt(Instant.now())
                .build();

        if (request.customAlias() != null && !request.customAlias().isBlank()) {
            // Custom alias: use verbatim. Rely on the unique constraint to reject dupes
            // rather than a check-then-insert (which would have a TOCTOU race).
            mapping.setShortCode(request.customAlias());
            try {
                mapping = repository.saveAndFlush(mapping);
            } catch (DataIntegrityViolationException e) {
                throw new AliasTakenException(request.customAlias());
            }
        } else {
            // Auto code: save to get the identity id, then Base62-encode it as the code.
            // Two-step so the code is a pure function of the collision-free primary key.
            mapping = repository.saveAndFlush(mapping);
            mapping.setShortCode(Base62.encode(mapping.getId()));
            mapping = repository.save(mapping);
        }

        cachePut(mapping);
        return toResponse(mapping);
    }

    /**
     * Resolve a short code to its long URL using cache-aside:
     * 1) look in Redis; 2) on miss, read the DB and populate the cache; 3) return.
     * Reads dominate a URL shortener (~100:1), so this keeps almost all traffic off Postgres.
     */
    public String resolveAndCount(String shortCode) {
        String cached = redis.opsForValue().get(CACHE_PREFIX + shortCode);
        if (cached != null) {
            incrementAsync(shortCode);
            return cached;
        }

        UrlMapping mapping = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new NotFoundException("Short code not found: " + shortCode));

        if (mapping.getExpiresAt() != null && mapping.getExpiresAt().isBefore(Instant.now())) {
            throw new NotFoundException("Short code expired: " + shortCode);
        }

        cachePut(mapping);
        repository.incrementClickCount(mapping.getId());
        return mapping.getLongUrl();
    }

    /** Read-only stats lookup (no click increment) — always hits the DB for fresh counts. */
    public UrlStatsResponse getStats(String shortCode) {
        UrlMapping m = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new NotFoundException("Short code not found: " + shortCode));
        return new UrlStatsResponse(m.getShortCode(), m.getLongUrl(),
                m.getClickCount(), m.getCreatedAt(), m.getExpiresAt());
    }

    private void incrementAsync(String shortCode) {
        // Best-effort counter bump on cache hit. In production this would be an async
        // event/batched counter (e.g. Kafka + periodic flush) to avoid a DB write per read.
        repository.findByShortCode(shortCode)
                .ifPresent(m -> repository.incrementClickCount(m.getId()));
    }

    private void cachePut(UrlMapping mapping) {
        Duration ttl = CACHE_TTL;
        if (mapping.getExpiresAt() != null) {
            long secondsToExpiry = Duration.between(Instant.now(), mapping.getExpiresAt()).getSeconds();
            if (secondsToExpiry <= 0) {
                return;
            }
            ttl = Duration.ofSeconds(Math.min(CACHE_TTL.getSeconds(), secondsToExpiry));
        }
        redis.opsForValue().set(CACHE_PREFIX + mapping.getShortCode(), mapping.getLongUrl(),
                ttl.getSeconds(), TimeUnit.SECONDS);
    }

    private ShortenResponse toResponse(UrlMapping m) {
        return new ShortenResponse(
                m.getShortCode(),
                baseUrl + "/" + m.getShortCode(),
                m.getLongUrl(),
                m.getExpiresAt());
    }
}
