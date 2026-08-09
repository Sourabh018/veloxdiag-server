package com.veloxdiag.server.config; // adjust to your actual base package

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    // Locked to exact deployed origins — dashboard, AgroMart frontend, AgroMart
    // admin — plus localhost for local dev. Wildcard *.vercel.app removed
    // (Roadmap P0 #3).
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "https://veloxdiag.vercel.app",
                        "https://agromart-frontend-one.vercel.app",
                        "https://agromart-admin-red.vercel.app"
                )
                .allowedOriginPatterns(
                        "http://localhost:*",
                        "https://veloxdiag-dashboard-*.vercel.app"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}