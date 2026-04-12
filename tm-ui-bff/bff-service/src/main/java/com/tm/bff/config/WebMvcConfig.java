package com.tm.bff.config;

import com.tm.bff.auth.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.tm.bff.auth.LocalLoginFilter;

/**
 * Registers the rate-limit interceptor for /auth/** paths only.
 * Also disables servlet-level registration of LocalLoginFilter — it must only run
 * inside the Spring Security filter chain (added via OAuth2SecurityConfig), not twice.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/auth/**");
    }

    /**
     * Prevent Spring Boot from auto-registering LocalLoginFilter as a servlet filter.
     * It is wired into the Spring Security chain by OAuth2SecurityConfig instead.
     */
    @Bean
    public FilterRegistrationBean<LocalLoginFilter> localLoginFilterRegistration(
            LocalLoginFilter filter) {
        FilterRegistrationBean<LocalLoginFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }
}
