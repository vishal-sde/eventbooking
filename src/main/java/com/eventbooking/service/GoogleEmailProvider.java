package com.eventbooking.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;


@Component
@Slf4j
public class GoogleEmailProvider implements EmailProvider {

    private static final String SEND_URL =
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";

    private final RestClient restClient = RestClient.create();
    private final GoogleTokenService tokenService;
    private final String fromEmail;
    private final String fromName;

    public GoogleEmailProvider(
            GoogleTokenService tokenService,
            @Value("${app.mail.from-email}") String fromEmail,
            @Value("${app.mail.from-name}") String fromName
    ) {
        this.tokenService = tokenService;
        this.fromEmail = fromEmail;
        this.fromName = fromName;
    }

    @Override
    public void send(String to, String subject, String htmlBody) {
        String accessToken = tokenService.getAccessToken();
        String raw = buildRawMessage(to, subject, htmlBody);

        restClient.post()
                .uri(SEND_URL)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("raw", raw))
                .retrieve()
                .toBodilessEntity();
    }


    private String buildRawMessage(String to, String subject, String htmlBody) {
        String encodedSubject = "=?UTF-8?B?"
                + Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8))
                + "?=";

        String message = "From: " + fromName + " <" + fromEmail + ">\r\n"
                + "To: " + to + "\r\n"
                + "Subject: " + encodedSubject + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "\r\n"
                + htmlBody;

        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(message.getBytes(StandardCharsets.UTF_8));
    }
}