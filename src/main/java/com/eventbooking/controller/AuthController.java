package com.eventbooking.controller;

import com.eventbooking.dto.UserDto;
import com.eventbooking.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private static final String GOOGLE_JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> GOOGLE_ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final JwtEncoder jwtEncoder;

    @Value("${app.jwt.expiration-minutes:120}")
    private long expirationMinutes;

    @Value("${app.google.client-id:}")
    private String googleClientId;

    private volatile JwtDecoder googleIdTokenDecoder;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email().trim().toLowerCase(), request.password()));
        userService.requireVerified(authentication.getName());
        UserDto.Response user = userService.getByEmail(authentication.getName());
        return issueToken(user);
    }

    @PostMapping("/verify-otp")
    public LoginResponse verifyOtp(@Valid @RequestBody UserDto.VerifyOtpRequest request) {
        UserDto.Response user = userService.verifyOtp(request.getEmail(), request.getOtp());
        return issueToken(user);
    }

    @PostMapping("/resend-otp")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void resendOtp(@Valid @RequestBody UserDto.ResendOtpRequest request) {
        userService.resendOtp(request.getEmail());
    }

    @PostMapping("/google")
    public LoginResponse google(@Valid @RequestBody GoogleLoginRequest request) {
        if (googleClientId == null || googleClientId.isBlank()) {
            throw new IllegalStateException("Google sign-in is not configured on this server");
        }
        Jwt googleToken;
        try {
            googleToken = googleIdTokenDecoder().decode(request.credential());
        } catch (JwtException ex) {
            throw new BadCredentialsException("Invalid Google sign-in token");
        }
        String email = googleToken.getClaimAsString("email");
        Boolean emailVerified = googleToken.getClaimAsBoolean("email_verified");
        String name = googleToken.getClaimAsString("name");
        if (email == null || Boolean.FALSE.equals(emailVerified)) {
            throw new BadCredentialsException("Google account email is not verified");
        }
        UserDto.Response user = userService.findOrCreateOAuthUser(email, (name != null && !name.isBlank()) ? name : email);
        return issueToken(user);
    }

    @GetMapping("/me")
    public UserDto.Response me(Authentication authentication) {
        return userService.getByEmail(authentication.getName());
    }

    private LoginResponse issueToken(UserDto.Response user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(expirationMinutes, ChronoUnit.MINUTES);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("eventbooking")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(user.getEmail())
                .claim("roles", List.of(user.getRole().name()))
                .claim("userId", user.getId())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return new LoginResponse(token, "Bearer", expiresAt, user);
    }


    private JwtDecoder googleIdTokenDecoder() {
        JwtDecoder decoder = googleIdTokenDecoder;
        if (decoder == null) {
            synchronized (this) {
                decoder = googleIdTokenDecoder;
                if (decoder == null) {
                    NimbusJwtDecoder nimbusDecoder = NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWKS_URI).build();
                    OAuth2TokenValidator<Jwt> googleValidator = token -> {
                        String issuer = token.getIssuer() != null ? token.getIssuer().toString() : "";
                        boolean issuerOk = GOOGLE_ISSUERS.contains(issuer);
                        boolean audienceOk = token.getAudience() != null && token.getAudience().contains(googleClientId);
                        if (issuerOk && audienceOk) {
                            return OAuth2TokenValidatorResult.success();
                        }
                        return OAuth2TokenValidatorResult.failure(
                                new OAuth2Error("invalid_token", "Google ID token issuer/audience mismatch", null));
                    };
                    nimbusDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                            JwtValidators.createDefault(), googleValidator));
                    decoder = nimbusDecoder;
                    googleIdTokenDecoder = decoder;
                }
            }
        }
        return decoder;
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}
    public record GoogleLoginRequest(@NotBlank String credential) {}
    public record LoginResponse(String token, String tokenType, Instant expiresAt, UserDto.Response user) {}
}