package com.umang.urlshortener.model.dto;

import java.time.Instant;

public record ShortenResponse(
        String shortCode,
        String shortUrl,
        String longUrl,
        Instant expiresAt
) {
}
