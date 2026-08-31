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
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
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
            // Let the unique constraint reject a taken alias; a check-then-insert would race.
            mapping.setShortCode(request.customAlias());
            try {
                mapping = repository.saveAndFlush(mapping);
            } catch (DataIntegrityViolationException e) {
                throw new AliasTakenException(request.customAlias());
            }
        } else {
            // Placeholder satisfies NOT NULL; replaced with Base62(id) once the insert assigns it.
            mapping.setShortCode(java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            mapping = repository.saveAndFlush(mapping);
            mapping.setShortCode(Base62.encode(mapping.getId()));
            mapping = repository.saveAndFlush(mapping);
        }

        cachePut(mapping);
        return toResponse(mapping);
    }

    public String resolveAndCount(String shortCode) {
        String cached = redis.opsForValue().get(CACHE_PREFIX + shortCode);
        if (cached != null) {
            repository.incrementClickCountByShortCode(shortCode);
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

    /** Read-only stats lookup (no click increment) — always hits the DB for a fresh count. */
    public UrlStatsResponse getStats(String shortCode) {
        UrlMapping m = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new NotFoundException("Short code not found: " + shortCode));
        return toStats(m);
    }

    /** A caller's URLs, newest first, using keyset pagination (afterId = last id seen). */
    public List<UrlStatsResponse> listByUser(String user, Long afterId, int limit) {
        long cursor = afterId == null ? Long.MAX_VALUE : afterId;
        int pageSize = Math.min(Math.max(limit, 1), 100);
        return repository.findPageByUser(user, cursor, Limit.of(pageSize)).stream()
                .map(this::toStats)
                .toList();
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

    private UrlStatsResponse toStats(UrlMapping m) {
        return new UrlStatsResponse(m.getShortCode(), m.getLongUrl(),
                m.getClickCount(), m.getCreatedAt(), m.getExpiresAt());
    }
}
