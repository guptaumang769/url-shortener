package com.umang.urlshortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.umang.urlshortener.exception.AliasTakenException;
import com.umang.urlshortener.exception.NotFoundException;
import com.umang.urlshortener.model.dto.ShortenRequest;
import com.umang.urlshortener.model.dto.ShortenResponse;
import com.umang.urlshortener.service.RateLimiterService;
import com.umang.urlshortener.service.UrlService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end against real Postgres + Redis: exercises Base62 code generation, cache-aside
 * resolution, custom-alias conflict handling, expiry, and the distributed rate limiter.
 */
@SpringBootTest
class UrlShortenerIT {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("urlshortener").withUsername("urls").withPassword("urls");
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // Small bucket so the rate-limit test is fast and deterministic.
        r.add("ratelimit.capacity", () -> "3");
        r.add("ratelimit.refill-per-sec", () -> "0");
    }

    @Autowired
    private UrlService urlService;
    @Autowired
    private RateLimiterService rateLimiter;

    @Test
    void shortenThenResolveRoundTrips() {
        ShortenResponse resp = urlService.shorten(
                new ShortenRequest("https://example.com/some/long/path", null, null), "tester");

        assertThat(resp.shortCode()).isNotBlank();
        assertThat(resp.shortUrl()).endsWith("/" + resp.shortCode());

        String resolved = urlService.resolveAndCount(resp.shortCode());
        assertThat(resolved).isEqualTo("https://example.com/some/long/path");
    }

    @Test
    void customAliasIsUsedVerbatimAndConflictsAreRejected() {
        urlService.shorten(new ShortenRequest("https://a.com", "myalias", null), "tester");

        assertThatThrownBy(() ->
                urlService.shorten(new ShortenRequest("https://b.com", "myalias", null), "tester"))
                .isInstanceOf(AliasTakenException.class);

        assertThat(urlService.resolveAndCount("myalias")).isEqualTo("https://a.com");
    }

    @Test
    void expiredUrlIsNotResolvable() {
        ShortenResponse resp = urlService.shorten(
                new ShortenRequest("https://expires.com", null, -1L), "tester"); // already expired
        assertThatThrownBy(() -> urlService.resolveAndCount(resp.shortCode()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void resolvingIncrementsTheClickCount() {
        ShortenResponse resp = urlService.shorten(
                new ShortenRequest("https://clicked.com", null, null), "tester");

        urlService.resolveAndCount(resp.shortCode());
        urlService.resolveAndCount(resp.shortCode());

        assertThat(urlService.getStats(resp.shortCode()).clickCount()).isEqualTo(2);
    }

    @Test
    void listByUserReturnsNewestFirstAndPages() {
        urlService.shorten(new ShortenRequest("https://one.com", null, null), "lister");
        urlService.shorten(new ShortenRequest("https://two.com", null, null), "lister");
        urlService.shorten(new ShortenRequest("https://three.com", null, null), "lister");

        var page = urlService.listByUser("lister", null, 2);

        assertThat(page).hasSize(2);
        assertThat(page.get(0).longUrl()).isEqualTo("https://three.com"); // newest id first
        assertThat(page.get(1).longUrl()).isEqualTo("https://two.com");
    }

    @Test
    void rateLimiterBlocksAfterBucketIsDrained() {
        String client = "1.2.3.4";
        // capacity=3, refill=0 → first 3 allowed, 4th denied.
        assertThat(rateLimiter.tryAcquire(client)).isTrue();
        assertThat(rateLimiter.tryAcquire(client)).isTrue();
        assertThat(rateLimiter.tryAcquire(client)).isTrue();
        assertThat(rateLimiter.tryAcquire(client)).isFalse();
    }
}
