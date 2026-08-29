package com.umang.urlshortener.model.dto;

import java.time.Instant;

/** Read-only stats for a short code — powers the dashboard's "my links" list. */
public record UrlStatsResponse(
        String shortCode,
        String longUrl,
        long clickCount,
        Instant createdAt,
        Instant expiresAt
) {
}
