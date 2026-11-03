package com.jezrel.syncnotes.config;

import com.jezrel.syncnotes.common.ClientIp;
import com.jezrel.syncnotes.common.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Limits login, register and refresh per client and per endpoint (section 10.4), before the request
 * body is read or any password is hashed. Logout isn't limited: signing out should always work.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfig implements WebMvcConfigurer {

    private static final List<String> LIMITED = List.of("login", "register", "refresh");

    private final RateLimiter limiter;

    public RateLimitConfig(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // A fixed scope per endpoint, never the raw URL: /logi%6E reaches the same handler as /login
        // and must not get a bucket of its own.
        for (String scope : LIMITED) {
            registry.addInterceptor(new HandlerInterceptor() {
                @Override
                public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                    limiter.check(scope, ClientIp.of(request));
                    return true;
                }
            }).addPathPatterns("/api/v1/auth/" + scope);
        }
    }
}
