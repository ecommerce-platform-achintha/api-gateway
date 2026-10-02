package com.achintha.apigateway.web;

import java.time.Duration;
import java.util.List;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * CORS, answered at the gateway only ({@code security.cors.*}; origins come from the Config Server per environment).
 *
 * @param allowedOrigins   exact origins, e.g. {@code https://shop.example.lk}; wildcards are refused at startup
 * @param allowCredentials sent only to the listed origins, since there is never a wildcard
 */
@Validated
@ConfigurationProperties("security.cors")
public record CorsProperties(
        @NotNull List<String> allowedOrigins,
        @DefaultValue("GET,POST,PUT,PATCH,DELETE,OPTIONS") List<String> allowedMethods,
        @DefaultValue("Authorization,Content-Type,Idempotency-Key") List<String> allowedHeaders,
        @DefaultValue("Retry-After,X-RateLimit-Remaining") List<String> exposedHeaders,
        @DefaultValue("true") boolean allowCredentials,
        @DefaultValue("PT1H") Duration maxAge) {

    public CorsProperties {
        allowedOrigins = allowedOrigins.stream().map(String::trim).filter(origin -> !origin.isEmpty()).toList();
        for (String origin : allowedOrigins) {
            if (origin.contains("*") || origin.equalsIgnoreCase("null")) {
                throw new IllegalArgumentException("security.cors.allowed-origins must list exact origins, got '"
                        + origin + "'");
            }
        }
        for (List<String> list : List.of(allowedMethods, allowedHeaders, exposedHeaders)) {
            if (list.contains("*")) {
                throw new IllegalArgumentException("security.cors.* lists must not contain '*'");
            }
        }
    }
}
