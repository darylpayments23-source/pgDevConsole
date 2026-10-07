package com.example.deploymentconsole.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuthWebConfig implements WebMvcConfigurer {
    private final RequireAuthInterceptor requireAuthInterceptor;

    public AuthWebConfig(RequireAuthInterceptor requireAuthInterceptor) {
        this.requireAuthInterceptor = requireAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requireAuthInterceptor).addPathPatterns("/api/**");
    }
}
