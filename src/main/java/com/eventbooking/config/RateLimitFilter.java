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

/**
 * Per-IP rate limiting for the two public, unauthenticated endpoints that are
 * the classic abuse targets: login (brute force / credential stuffing) and
 * registration (signup spam).
 *
 * Runs as a plain servlet filter *before* Spring Security and the rest of the
 * app, so an attacker gets rejected with 429 before any authentication logic,
 * database queries, or password hashing runs.
 *
 * Implemented as a plain Redis counter (INCR + EXPIRE — the standard "fixed
 * window" rate-limiting pattern), not Redisson's RRateLimiter. RRateLimiter's
 * Lua scripting proved unreliable in this environment (spurious "permits
 * cannot exceed rate" / "not initialized" errors even on a well-formed first
 * request), so a plain atomic counter is used instead — fewer moving parts,
 * same Redis-backed cross-instance correctness.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final int loginCapacity;
    private final int loginWindowSeconds;
    private final int signupCapacity;
    private final int signupWindowSeconds;

    /**
     * Config values are resolved by Spring in RateLimitConfig (a real bean,
     * where @Value works) and passed in here explicitly. This filter is
     * constructed manually with `new` inside RateLimitConfig's @Bean method,
     * so it is never itself a Spring-managed bean — @Value annotations placed
     * directly on its fields are never processed and silently stay at 0,
     * which made every request exceed the "limit" instantly. Do not put
     * @Value back on fields in this class.
     */
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
        return null;
    }

    /**
     * Respects X-Forwarded-For since the app sits behind a reverse proxy/load
     * balancer in every real deployment target (Railway, Render, etc.) — without
     * this every request looks like it comes from the proxy's IP and the limit
     * becomes shared across all users instead of per-client.
     *
     * NOTE: only trust this header when the app is actually behind a proxy that
     * sets/overwrites it. If you ever expose this app directly to the internet
     * without a proxy in front, remove this so it can't be spoofed by clients.
     */
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