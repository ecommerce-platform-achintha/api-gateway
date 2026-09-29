package com.achintha.apigateway.auth;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for {@link JwtAuthenticationFilter} ({@code api-gateway.auth.*} in application.yml).
 *
 * @param secretProperty name of the property holding the HS256 secret (not the secret itself). It is the same key
 *                       user-service reads, and the Config Server supplies it.
 * @param issuer         expected {@code iss} claim, matching user-service's {@code security.jwt.issuer}
 * @param publicRoutes   requests that need no token
 */
@Validated
@ConfigurationProperties("api-gateway.auth")
public record AuthProperties(
        @NotBlank String secretProperty,
        @NotBlank String issuer,
        @NotNull List<@Valid PublicRoute> publicRoutes) {

    /**
     * @param method HTTP method, or null for any method
     * @param path   Spring path pattern, e.g. {@code /api/auth/login} or {@code /api/public/**}
     */
    public record PublicRoute(String method, @NotBlank String path) {
    }
}
