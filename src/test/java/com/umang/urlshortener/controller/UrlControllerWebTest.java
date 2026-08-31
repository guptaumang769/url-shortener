package com.umang.urlshortener.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.umang.urlshortener.exception.NotFoundException;
import com.umang.urlshortener.model.dto.ShortenResponse;
import com.umang.urlshortener.service.RateLimiterService;
import com.umang.urlshortener.service.UrlService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer tests for the HTTP contract: redirect status, validation, and rate limiting. */
@WebMvcTest(UrlController.class)
class UrlControllerWebTest {

    @Autowired private MockMvc mvc;
    @MockBean private UrlService urlService;
    @MockBean private RateLimiterService rateLimiter;

    @Test
    void redirectReturns302WithLocationHeader() throws Exception {
        when(urlService.resolveAndCount("abc123")).thenReturn("https://example.com/target");

        mvc.perform(get("/abc123"))
                .andExpect(status().isFound()) // 302, not 301 — every click reaches the counter
                .andExpect(header().string("Location", "https://example.com/target"));
    }

    @Test
    void unknownCodeReturns404() throws Exception {
        when(urlService.resolveAndCount("missing1"))
                .thenThrow(new NotFoundException("Short code not found: missing1"));

        mvc.perform(get("/missing1")).andExpect(status().isNotFound());
    }

    @Test
    void shortenReturns201WithBody() throws Exception {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
        when(urlService.shorten(any(), anyString())).thenReturn(
                new ShortenResponse("abc123", "http://localhost:8080/abc123",
                        "https://example.com", null));

        mvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("abc123"));
    }

    @Test
    void invalidUrlReturns400() throws Exception {
        // @Pattern on the url field rejects a non-http(s) value before the handler runs.
        mvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"not-a-url\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void drainedBucketReturns429() throws Exception {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(false);

        mvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isTooManyRequests());
    }
}
