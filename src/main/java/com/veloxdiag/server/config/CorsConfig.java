package com.veloxdiag.server.config; // adjust to your actual base package

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    // Dashboard currently only runs locally (`npm run dev`), so localhost-only
    // is correct for now. Revisit this the moment the dashboard gets deployed
    // anywhere else (Vercel, Render static site, etc.) — until this is updated,
    // a deployed dashboard's requests will fail with an opaque browser CORS
    // error, not an obvious server-side log.
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}