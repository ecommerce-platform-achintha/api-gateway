package com.achintha.apigateway.auth;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import com.achintha.apigateway.support.RequestRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for {@link JwtAuthenticationFilter} ({@code api-gateway.auth.*}). The Config Server supplies the token
 * settings from the shared {@code security.jwt.*} keys (config-repo/api-gateway.yml).
 *
 * @param jwksUri              user-service's JWKS ({@code /.well-known/jwks.json}); {@code lb://user-service/...}
 *                             resolves through Eureka
 * @param issuer               expected {@code iss}
 * @param audience             value {@code aud} must contain
 * @param clockSkewSeconds     tolerance for {@code exp}/{@code nbf}
 * @param jwksCacheTtl         keys are re-fetched on the next request after this long
 * @param jwksRefreshCooldown  minimum time between fetches triggered by an unknown {@code kid}, so tokens with made-up
 *                             key ids can't turn the gateway into a JWKS request flood against user-service
 * @param publicRoutes         requests that need no token
 * @param roleRules            coarse path-prefix role checks; the first rule whose path matches decides
 */
@Validated
@ConfigurationProperties("api-gateway.auth")
public record AuthProperties(
        @NotNull URI jwksUri,
        @NotBlank String issuer,
        @NotBlank String audience,
        @PositiveOrZero long clockSkewSeconds,
        @DefaultValue("PT5M") Duration jwksCacheTtl,
        @DefaultValue("PT10S") Duration jwksRefreshCooldown,
        @NotNull List<@Valid RequestRule> publicRoutes,
        @NotNull List<@Valid RoleRule> roleRules) {

    /**
     * @param path  Spring path pattern, e.g. {@code /api/admin/**}
     * @param roles roles allowed under it (the token's {@code role} claim must be one of them)
     */
    public record RoleRule(@NotBlank String path, @NotEmpty List<String> roles) {
    }
}
