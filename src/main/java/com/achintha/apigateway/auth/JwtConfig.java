package com.achintha.apigateway.auth;

import java.net.URI;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Verifies access tokens the way every service does (docs/marketplace-design.md 3.3): RS256 only, keys from
 * user-service's JWKS, {@code iss} and {@code aud} checks, {@code exp} required, {@code exp}/{@code nbf} with the
 * configured skew (30 s), and a non-blank {@code sub} and {@code role}.
 * <p>
 * Restricting the algorithm to RS256 also rejects unsigned ({@code alg=none}) tokens and HS256 tokens, including
 * HS256 tokens "signed" with the public key.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class JwtConfig {

    static final String ROLE_CLAIM = "role";

    private static final Duration JWKS_FETCH_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    ReactiveJwtDecoder jwtDecoder(AuthProperties properties,
                                  ObjectProvider<ReactorLoadBalancerExchangeFilterFunction> loadBalancer) {
        return jwtDecoder(properties, jwksFetcher(properties.jwksUri(), loadBalancer), Clock.systemUTC());
    }

    /** The decoder with an explicit key source; tests pass an in-memory one. */
    static ReactiveJwtDecoder jwtDecoder(AuthProperties properties, Supplier<Mono<JWKSet>> fetcher, Clock clock) {
        JwksCache keys = new JwksCache(fetcher, properties.jwksCacheTtl(), properties.jwksRefreshCooldown(), clock);
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSource(keys)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(validator(properties, clock));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> validator(AuthProperties properties, Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(properties.clockSkewSeconds()));
        timestamps.setClock(clock);
        return new DelegatingOAuth2TokenValidator<>(List.of(
                timestamps,
                // JwtTimestampValidator accepts a token without exp; access tokens must always expire
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtIssuerValidator(properties.issuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(properties.audience())),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, StringUtils::hasText),
                new JwtClaimValidator<String>(ROLE_CLAIM, StringUtils::hasText)));
    }

    /**
     * Fetches the JWKS. An {@code lb://user-service/...} URI is resolved through Eureka (handy in Docker); an
     * {@code http(s)://} URI is called directly.
     */
    private static Supplier<Mono<JWKSet>> jwksFetcher(
            URI jwksUri, ObjectProvider<ReactorLoadBalancerExchangeFilterFunction> loadBalancer) {
        WebClient.Builder builder = WebClient.builder();
        URI target = jwksUri;
        if ("lb".equalsIgnoreCase(jwksUri.getScheme())) {
            ReactorLoadBalancerExchangeFilterFunction lb = loadBalancer.getIfAvailable();
            if (lb == null) {
                throw new IllegalStateException("api-gateway.auth.jwks-uri uses lb:// but no load balancer is set up");
            }
            builder.filter(lb);
            target = URI.create("http" + jwksUri.toString().substring("lb".length()));
        }
        WebClient client = builder.build();
        URI uri = target;
        return () -> client.get().uri(uri)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(JWKS_FETCH_TIMEOUT)
                .map(JwtConfig::parseJwks);
    }

    private static JWKSet parseJwks(String body) {
        try {
            return JWKSet.parse(body);
        }
        catch (ParseException e) {
            throw new IllegalStateException("JWKS response is not a valid JWK set", e);
        }
    }
}
