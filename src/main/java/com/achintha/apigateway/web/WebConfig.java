package com.achintha.apigateway.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.util.unit.DataSize;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * WebFilters that run for every request, routed or not, in this order: security headers (so even CORS rejections get
 * them), CORS (answers preflight requests itself), then the request guard. Gateway global filters (auth, rate limit)
 * run after these, for routed requests only.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
public class WebConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityHeadersFilter securityHeadersFilter() {
        return new SecurityHeadersFilter();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    CorsWebFilter corsWebFilter(CorsProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(properties.allowedMethods());
        cors.setAllowedHeaders(properties.allowedHeaders());
        cors.setExposedHeaders(properties.exposedHeaders());
        cors.setAllowCredentials(properties.allowCredentials());
        cors.setMaxAge(properties.maxAge());
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return new CorsWebFilter(source);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 20)
    RequestGuardFilter requestGuardFilter(@Value("${api-gateway.request.max-body-size:1MB}") DataSize maxBodySize) {
        return new RequestGuardFilter(maxBodySize);
    }
}
