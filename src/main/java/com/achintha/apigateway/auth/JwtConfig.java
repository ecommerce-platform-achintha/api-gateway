package com.achintha.apigateway.auth;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * Verifies tokens exactly as user-service does: HS256 with the UTF-8 bytes of the shared secret, plus
 * signature, exp/nbf (60 s clock skew) and issuer checks.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class JwtConfig {

    // HS256 requires a key of at least 256 bits; user-service enforces the same minimum
    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    ReactiveJwtDecoder jwtDecoder(Environment environment, AuthProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(signingKey(environment, properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    // No local default on purpose (like user-service): without the secret the gateway refuses to start
    private static SecretKey signingKey(Environment environment, AuthProperties properties) {
        String secret = environment.getProperty(properties.secretProperty());
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT secret '" + properties.secretProperty() + "' is not set. It comes "
                    + "from the Config Server (config-repo/api-gateway.yml): is the Config Server running?");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT secret '" + properties.secretProperty()
                    + "' must be at least " + MIN_SECRET_BYTES + " bytes (256 bits) for HS256");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
