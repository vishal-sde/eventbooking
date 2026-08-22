package com.eventbooking.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;


@Component
@Slf4j
public class GoogleTokenService {

    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";

    private final RestClient restClient = RestClient.create();
    private final ReentrantLock lock = new ReentrantLock();

    // Deliberately separate from app.google.client-id (used by AuthController
    // for "Sign in with Google" ID token verification). These belong to the
    // "evently-mailer" OAuth client — a different client than the login one —
    // so they must never share a property name with the login client's ID.
    @Value("${app.google.mail-client-id:}")
    private String clientId;

    @Value("${app.google.mail-client-secret:}")
    private String clientSecret;

    @Value("${app.google.mail-refresh-token:}")
    private String refreshToken;

    private volatile String cachedAccessToken;
    private volatile Instant cachedExpiry = Instant.EPOCH;

    public String getAccessToken() {
        // Fast path: still valid, no lock needed.
        if (cachedAccessToken != null && Instant.now().isBefore(cachedExpiry)) {
            return cachedAccessToken;
        }

        lock.lock();
        try {
            // Re-check inside the lock in case another thread just refreshed it.
            if (cachedAccessToken != null && Instant.now().isBefore(cachedExpiry)) {
                return cachedAccessToken;
            }
            return refreshAccessToken();
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private String refreshAccessToken() {
        if (clientId.isBlank() || clientSecret.isBlank() || refreshToken.isBlank()) {
            throw new IllegalStateException(
                    "Google OAuth2 mail credentials not set — need GOOGLE_MAIL_CLIENT_ID, "
                            + "GOOGLE_MAIL_CLIENT_SECRET, and GOOGLE_MAIL_REFRESH_TOKEN to send email.");
        }

        Map<String, Object> body = restClient.post()
                .uri(TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(
                        "client_id=" + clientId
                                + "&client_secret=" + clientSecret
                                + "&refresh_token=" + refreshToken
                                + "&grant_type=refresh_token"
                )
                .retrieve()
                .body(Map.class);

        if (body == null || !body.containsKey("access_token")) {
            throw new IllegalStateException("Google token refresh returned no access_token: " + body);
        }

        String accessToken = (String) body.get("access_token");
        // expires_in is seconds; knock 60s off as a safety margin.
        int expiresInSeconds = body.get("expires_in") instanceof Number n ? n.intValue() : 3300;

        this.cachedAccessToken = accessToken;
        this.cachedExpiry = Instant.now().plusSeconds(Math.max(60, expiresInSeconds - 60));

        log.info("Refreshed Google OAuth2 access token, valid ~{}s", expiresInSeconds);
        return accessToken;
    }

    /** Surfaced separately so HttpHeaders.setBasicAuth-style helpers aren't needed inline. */
    HttpHeaders authHeader(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }
}