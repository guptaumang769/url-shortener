package com.umang.urlshortener.controller;

import com.umang.urlshortener.exception.RateLimitExceededException;
import com.umang.urlshortener.model.dto.ShortenRequest;
import com.umang.urlshortener.model.dto.ShortenResponse;
import com.umang.urlshortener.model.dto.UrlStatsResponse;
import com.umang.urlshortener.service.RateLimiterService;
import com.umang.urlshortener.service.UrlService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UrlController {

    private final UrlService urlService;
    private final RateLimiterService rateLimiter;

    public UrlController(UrlService urlService, RateLimiterService rateLimiter) {
        this.urlService = urlService;
        this.rateLimiter = rateLimiter;
    }

    /** Create a short URL. Rate-limited per client IP. */
    @PostMapping("/api/v1/shorten")
    public ResponseEntity<ShortenResponse> shorten(@Valid @RequestBody ShortenRequest request,
                                                   HttpServletRequest http) {
        String clientId = clientId(http);
        if (!rateLimiter.tryAcquire(clientId)) {
            throw new RateLimitExceededException("Too many requests from " + clientId);
        }
        ShortenResponse response = urlService.shorten(request, clientId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Read-only stats for a short code (no redirect, no click increment). */
    @GetMapping("/api/v1/urls/{shortCode}")
    public ResponseEntity<UrlStatsResponse> stats(@PathVariable String shortCode) {
        return ResponseEntity.ok(urlService.getStats(shortCode));
    }

    /** Redirect a short code to its long URL (301). This is the read-heavy hot path. */
    @GetMapping("/{shortCode:[A-Za-z0-9]{3,16}}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String longUrl = urlService.resolveAndCount(shortCode);
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .location(URI.create(longUrl))
                .build();
    }

    private String clientId(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
