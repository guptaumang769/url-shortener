package com.umang.urlshortener.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param url         the long URL to shorten (required, must look like http(s)://…)
 * @param customAlias optional user-chosen code; if set, used verbatim instead of the
 *                    Base62-of-id code. Alphanumeric, 3–16 chars.
 * @param ttlSeconds  optional expiry; null = never expires.
 */
public record ShortenRequest(
        @NotBlank
        @Pattern(regexp = "^https?://.+", message = "url must start with http:// or https://")
        @Size(max = 2048)
        String url,

        @Pattern(regexp = "^[A-Za-z0-9]{3,16}$", message = "alias must be 3-16 alphanumeric chars")
        String customAlias,

        Long ttlSeconds
) {
}
