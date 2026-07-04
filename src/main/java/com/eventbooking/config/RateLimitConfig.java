package com.eventbooking.config;

import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class RateLimitConfig {

    @Value("${app.rate-limit.login.capacity:5}")
    private int loginCapacity;

    @Value("${app.rate-limit.login.window-seconds:60}")
    private int loginWindowSeconds;

    @Value("${app.rate-limit.signup.capacity:10}")
    private int signupCapacity;

    @Value("${app.rate-limit.signup.window-seconds:3600}")
    private int signupWindowSeconds;

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RedissonClient redissonClient,
                                                                               ObjectMapper objectMapper) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RateLimitFilter(redissonClient, objectMapper,
                loginCapacity, loginWindowSeconds, signupCapacity, signupWindowSeconds));
        registration.addUrlPatterns("/api/auth/login", "/api/users");
        // Must run before Spring Security's filter chain so blocked requests
        // never reach authentication logic, password hashing, or the DB.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}