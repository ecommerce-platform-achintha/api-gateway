package com.achintha.apigateway.ratelimit;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.achintha.apigateway.support.RequestRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Per-client rate limit tiers ({@code api-gateway.rate-limit.*}).
 *
 * @param trustedProxies IPs or CIDR ranges whose {@code X-Forwarded-For} is believed. Empty: the TCP peer address is
 *                       the client, and {@code X-Forwarded-For} is ignored.
 * @param tiers          checked in order; the first tier with a matching route applies. A tier named
 *                       {@value #DEFAULT_TIER} (no routes) covers every other request.
 */
@Validated
@ConfigurationProperties("api-gateway.rate-limit")
public record RateLimitProperties(
        @DefaultValue List<String> trustedProxies,
        @DefaultValue Map<String, @Valid Tier> tiers) {

    public static final String DEFAULT_TIER = "default";

    public RateLimitProperties {
        trustedProxies = trustedProxies.stream().map(String::trim).filter(proxy -> !proxy.isEmpty()).toList();
        tiers = new LinkedHashMap<>(tiers);
    }

    /**
     * @param capacity     requests allowed per {@code refillPeriod} (also the burst size)
     * @param refillPeriod time to refill the whole capacity; tokens come back gradually
     * @param key          who shares a bucket
     * @param routes       requests this tier applies to (ignored for {@value #DEFAULT_TIER})
     */
    public record Tier(
            @Positive long capacity,
            @NotNull Duration refillPeriod,
            @DefaultValue("user-or-ip") KeyType key,
            @DefaultValue List<@Valid RequestRule> routes) {
    }

    public enum KeyType {
        /** Always the client IP, even when a token is sent: for anonymous endpoints like login. */
        IP,
        /** The token's user id when the request is authenticated, else the client IP. */
        USER_OR_IP
    }
}
