package com.achintha.apigateway.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static com.achintha.apigateway.auth.JwtAuthenticationFilter.USER_ID_HEADER;
import static com.achintha.apigateway.auth.JwtAuthenticationFilter.USER_ROLES_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter with a real decoder built by {@link JwtConfig}, and tokens signed the way user-service signs them
 * (NimbusJwtEncoder, HS256, UTF-8 secret bytes, sub = user id, roles claim).
 */
class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-only-jwt-secret-0123456789-abcdefghij";
    private static final String ISSUER = "user-service";
    private static final String USER_ID = "3f2b8c1e-5d4a-4b6f-9e0a-1c2d3e4f5a6b";

    private final AuthProperties properties = new AuthProperties("security.jwt.secret", ISSUER, List.of(
            new AuthProperties.PublicRoute("POST", "/api/auth/login"),
            new AuthProperties.PublicRoute("POST", "/api/users/register")));

    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
            new JwtConfig().jwtDecoder(new MockEnvironment().withProperty("security.jwt.secret", SECRET), properties),
            properties);

    /** The exchange the filter passed on, or null if it stopped the request. */
    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.set(exchange);
        return Mono.empty();
    };

    @Test
    void validTokenPassesThroughWithIdentityHeaders() {
        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(SECRET, ISSUER, Instant.now().plusSeconds(900),
                        List.of("ROLE_ADMIN", "ROLE_CUSTOMER"))));

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        HttpHeaders headers = forwarded.get().getRequest().getHeaders();
        assertThat(headers.get(USER_ID_HEADER)).containsExactly(USER_ID);
        assertThat(headers.get(USER_ROLES_HEADER)).containsExactly("ROLE_ADMIN,ROLE_CUSTOMER");
    }

    @Test
    void identityHeadersFromClientAreReplacedNotForwarded() {
        run(MockServerHttpRequest.get("/api/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(SECRET, ISSUER, Instant.now().plusSeconds(900),
                        List.of("ROLE_CUSTOMER")))
                .header(USER_ID_HEADER, "someone-else")
                .header("x-user-roles", "ROLE_ADMIN"));

        HttpHeaders headers = forwarded.get().getRequest().getHeaders();
        assertThat(headers.get(USER_ID_HEADER)).containsExactly(USER_ID);
        assertThat(headers.get(USER_ROLES_HEADER)).containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void tokenWithoutRolesClaimForwardsEmptyRoles() {
        run(MockServerHttpRequest.get("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(SECRET, ISSUER, Instant.now().plusSeconds(900),
                        null)));

        assertThat(forwarded.get().getRequest().getHeaders().get(USER_ROLES_HEADER)).containsExactly("");
    }

    @Test
    void missingTokenOnProtectedRouteIsRejected() {
        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/api/orders"));

        assertRejected(exchange, "{\"error\":\"Authentication required\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic dXNlcjpwYXNz", "Bearer", "Bearer   ", "Token abc.def.ghi"})
    void malformedAuthorizationHeaderIsRejected(String header) {
        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, header));

        assertRejected(exchange, "{\"error\":\"Authentication required\"}");
    }

    @Test
    void expiredTokenIsRejected() {
        // Past the decoder's 60 s clock-skew allowance
        String expired = token(SECRET, ISSUER, Instant.now().minus(Duration.ofMinutes(5)), List.of("ROLE_CUSTOMER"));

        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired));

        assertRejected(exchange, "{\"error\":\"Invalid or expired token\"}");
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String forged = token("some-other-secret-that-is-long-enough-0123456789", ISSUER,
                Instant.now().plusSeconds(900), List.of("ROLE_ADMIN"));

        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged));

        assertRejected(exchange, "{\"error\":\"Invalid or expired token\"}");
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        String other = token(SECRET, "someone-else", Instant.now().plusSeconds(900), List.of("ROLE_CUSTOMER"));

        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + other));

        assertRejected(exchange, "{\"error\":\"Invalid or expired token\"}");
    }

    @Test
    void unparseableTokenIsRejected() {
        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"));

        assertRejected(exchange, "{\"error\":\"Invalid or expired token\"}");
    }

    @Test
    void publicRoutesPassWithoutTokenAndWithoutClientIdentityHeaders() {
        for (String path : List.of("/api/auth/login", "/api/users/register")) {
            forwarded.set(null);
            MockServerWebExchange exchange = run(MockServerHttpRequest.post(path)
                    .header(USER_ID_HEADER, "someone-else")
                    .header(USER_ROLES_HEADER, "ROLE_ADMIN"));

            assertThat(exchange.getResponse().getStatusCode()).as(path).isNull();
            HttpHeaders headers = forwarded.get().getRequest().getHeaders();
            assertThat(headers.containsHeader(USER_ID_HEADER)).as(path).isFalse();
            assertThat(headers.containsHeader(USER_ROLES_HEADER)).as(path).isFalse();
        }
    }

    @Test
    void publicPathWithOtherMethodStillNeedsToken() {
        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/api/auth/login"));

        assertRejected(exchange, "{\"error\":\"Authentication required\"}");
    }

    private MockServerWebExchange run(MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        filter.filter(exchange, chain).block();
        return exchange;
    }

    private void assertRejected(MockServerWebExchange exchange, String body) {
        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(body);
    }

    /** Signed like user-service's JwtService; {@code roles} null leaves the claim out. */
    private static String token(String secret, String issuer, Instant expiresAt, List<String> roles) {
        NimbusJwtEncoder encoder = NimbusJwtEncoder.withSecretKey(
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")).build();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(USER_ID)
                .issuedAt(expiresAt.minus(Duration.ofMinutes(15)))
                .expiresAt(expiresAt)
                .claim("email", "jane@example.com");
        if (roles != null) {
            claims.claim("roles", roles);
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }
}
