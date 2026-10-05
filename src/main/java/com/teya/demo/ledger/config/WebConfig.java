package com.teya.demo.ledger.config;

import com.teya.demo.ledger.interceptor.IdempotencyHeaderValidatorInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final IdempotencyHeaderValidatorInterceptor idempotencyHeaderValidatorInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(idempotencyHeaderValidatorInterceptor)
                .addPathPatterns("/api/**");
    }
}
