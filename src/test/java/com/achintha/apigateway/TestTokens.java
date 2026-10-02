package com.achintha.apigateway;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Access tokens signed the way user-service signs them (RS256, kid header, iss/aud/sub/role claims), and forgeries. */
public final class TestTokens {

    public static final String ISSUER = "user-service";
    public static final String AUDIENCE = "marketplace";
    public static final String KEY_ID = "test-key-1";
    public static final String USER_ID = "3f2b8c1e-5d4a-4b6f-9e0a-1c2d3e4f5a6b";

    private static final KeyPair SIGNING_KEYS = rsaKeyPair();
    private static final KeyPair OTHER_KEYS = rsaKeyPair();

    private TestTokens() {
    }

    /** The JWKS user-service would publish: the public signing key only. */
    public static JWKSet jwks() {
        return new JWKSet(new RSAKey.Builder((RSAPublicKey) SIGNING_KEYS.getPublic()).keyID(KEY_ID).build());
    }

    public static String jwksJson() {
        return jwks().toString();
    }

    public static String valid(String role) {
        return rs256(SIGNING_KEYS, KEY_ID, claims -> claims.claim("role", role));
    }

    public static String validForUser(String userId, String role) {
        return rs256(SIGNING_KEYS, KEY_ID, claims -> claims.subject(userId).claim("role", role));
    }

    /** RS256 with the expected key; {@code customizer} changes the default claims. */
    public static String rs256(Consumer<JwtClaimsSet.Builder> customizer) {
        return rs256(SIGNING_KEYS, KEY_ID, customizer);
    }

    /** Signed with a different RSA key but claiming the real kid. */
    public static String signedWithOtherKey() {
        return rs256(OTHER_KEYS, KEY_ID, claims -> claims.claim("role", "ROLE_ADMIN"));
    }

    public static String signedWithUnknownKid() {
        return rs256(OTHER_KEYS, "unknown-kid", claims -> claims.claim("role", "ROLE_ADMIN"));
    }

    /** HS256 using the RSA public key bytes as the secret: the classic algorithm-confusion forgery. */
    public static String hs256WithPublicKey() {
        byte[] secret = SIGNING_KEYS.getPublic().getEncoded();
        NimbusJwtEncoder encoder = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256")).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                defaultClaims().claim("role", "ROLE_ADMIN").build())).getTokenValue();
    }

    /** Unsigned token: {"alg":"none"} and an empty signature. */
    public static String algNone() {
        Instant now = Instant.now();
        String header = "{\"alg\":\"none\",\"typ\":\"JWT\"}";
        String payload = "{\"iss\":\"" + ISSUER + "\",\"aud\":[\"" + AUDIENCE + "\"],\"sub\":\"" + USER_ID
                + "\",\"role\":\"ROLE_ADMIN\",\"iat\":" + now.getEpochSecond()
                + ",\"exp\":" + now.plusSeconds(600).getEpochSecond() + "}";
        return base64Url(header) + "." + base64Url(payload) + ".";
    }

    private static String rs256(KeyPair keys, String keyId, Consumer<JwtClaimsSet.Builder> customizer) {
        NimbusJwtEncoder encoder = NimbusJwtEncoder.withKeyPair((RSAPublicKey) keys.getPublic(),
                (RSAPrivateKey) keys.getPrivate()).jwkPostProcessor(builder -> builder.keyID(keyId)).build();
        JwtClaimsSet.Builder claims = defaultClaims();
        customizer.accept(claims);
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build(), claims.build())).getTokenValue();
    }

    private static JwtClaimsSet.Builder defaultClaims() {
        Instant now = Instant.now();
        return JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(USER_ID)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(10)))
                .id(UUID.randomUUID().toString())
                .claim("pid", "USR-2610-7K2M9Q")
                .claim("status", "ACTIVE")
                .claim("tv", 0);
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
