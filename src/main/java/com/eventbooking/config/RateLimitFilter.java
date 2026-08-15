package com.eventbooking.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final int loginCapacity;
    private final int loginWindowSeconds;
    private final int signupCapacity;
    private final int signupWindowSeconds;


    public RateLimitFilter(RedissonClient redissonClient, ObjectMapper objectMapper,
                           int loginCapacity, int loginWindowSeconds,
                           int signupCapacity, int signupWindowSeconds) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.loginCapacity = loginCapacity;
        this.loginWindowSeconds = loginWindowSeconds;
        this.signupCapacity = signupCapacity;
        this.signupWindowSeconds = signupWindowSeconds;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        RateLimitRule rule = ruleFor(request);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = clientIp(request);
        String counterKey = "rate-limit:count:" + rule.name() + ":" + clientIp;
        RAtomicLong counter = redissonClient.getAtomicLong(counterKey);

        long count = counter.incrementAndGet();
        if (count == 1) {
            // Only the request that starts a new window sets the TTL — this is
            // what makes it a *fixed window* (the window resets `windowSeconds`
            // after the first request in it, not after each individual one).
            counter.expire(rule.windowSeconds(), TimeUnit.SECONDS);
        }

        if (count <= rule.capacity()) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("Rate limit exceeded: rule={} ip={} path={} count={}",
                rule.name(), clientIp, request.getRequestURI(), count);
        writeTooManyRequests(response, request);
    }

    private RateLimitRule ruleFor(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("POST".equalsIgnoreCase(method) && path.equals("/api/auth/login")) {
            return new RateLimitRule("login", loginCapacity, loginWindowSeconds);
        }
        if ("POST".equalsIgnoreCase(method) && path.equals("/api/users")) {
            return new RateLimitRule("signup", signupCapacity, signupWindowSeconds);
        }
        if ("POST".equalsIgnoreCase(method) && path.equals("/api/auth/resend-otp")) {
            return new RateLimitRule("otp-resend", signupCapacity, signupWindowSeconds);
        }
        return null;
    }


    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void writeTooManyRequests(HttpServletResponse response, HttpServletRequest request) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", 429);
        body.put("error", "Too Many Requests");
        body.put("message", "Too many attempts. Please wait and try again.");
        body.put("path", request.getRequestURI());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private record RateLimitRule(String name, int capacity, int windowSeconds) {}
}