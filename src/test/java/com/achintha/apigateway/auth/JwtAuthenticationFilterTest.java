package com.achintha.apigateway.auth;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.achintha.apigateway.TestTokens;
import com.achintha.apigateway.support.RequestRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static com.achintha.apigateway.auth.JwtAuthenticationFilter.USER_ID_ATTR;
import static com.achintha.apigateway.auth.JwtAuthenticationFilter.USER_ID_HEADER;
import static com.achintha.apigateway.auth.JwtAuthenticationFilter.USER_ROLES_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter with the real decoder from {@link JwtConfig} (RS256, issuer, audience, 30 s skew), keys served from
 * memory instead of user-service's JWKS endpoint.
 */
class JwtAuthenticationFilterTest {

    static final AuthProperties PROPERTIES = new AuthProperties(
            URI.create("http://user-service.test/.well-known/jwks.json"),
            TestTokens.ISSUER, TestTokens.AUDIENCE, 30, Duration.ofMinutes(5), Duration.ofSeconds(10),
            List.of(
                    new RequestRule("POST", "/api/auth/login"),
                    new RequestRule("POST", "/api/auth/refresh"),
                    new RequestRule("POST", "/api/users/register/customer"),
                    new RequestRule("POST", "/api/users/register/merchant"),
                    new RequestRule("GET", "/.well-known/jwks.json"),
                    new RequestRule("GET", "/api/public/**")),
            List.of(
                    new AuthProperties.RoleRule("/api/customer/**", List.of("ROLE_CUSTOMER")),
                    new AuthProperties.RoleRule("/api/merchant/owner/**", List.of("ROLE_MERCHANT")),
                    new AuthProperties.RoleRule("/api/merchant/**", List.of("ROLE_MERCHANT", "ROLE_ASSISTANT")),
                    new AuthProperties.RoleRule("/api/admin/**", List.of("ROLE_ADMIN", "ROLE_SUPER_ADMIN")),
                    new AuthProperties.RoleRule("/api/super-admin/**", List.of("ROLE_SUPER_ADMIN"))));

    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
            JwtConfig.jwtDecoder(PROPERTIES, () -> Mono.just(TestTokens.jwks()), Clock.systemUTC()), PROPERTIES);

    /** The exchange the filter passed on, or null if it stopped the request. */
    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.set(exchange);
        return Mono.empty();
    };

    @Test
    void validRs256TokenPassesWithAuthorizationUnchangedAndNoIdentityHeaders() {
        String token = TestTokens.valid("ROLE_CUSTOMER");

        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/api/customer/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        HttpHeaders headers = forwarded.get().getRequest().getHeaders();
        assertThat(headers.get(HttpHeaders.AUTHORIZATION)).containsExactly("Bearer " + token);
        assertThat(headers.containsHeader(USER_ID_HEADER)).isFalse();
        assertThat(headers.containsHeader(USER_ROLES_HEADER)).isFalse();
        assertThat(forwarded.get().<String>getAttribute(USER_ID_ATTR)).isEqualTo(TestTokens.USER_ID);
    }

    @Test
    void spoofedIdentityHeadersAreStrippedOnProtectedRoutes() {
        run(MockServerHttpRequest.get("/api/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.valid("ROLE_CUSTOMER"))
                .header(USER_ID_HEADER, "someone-else")
                .header("x-user-roles", "ROLE_SUPER_ADMIN"));

        HttpHeaders headers = forwarded.get().getRequest().getHeaders();
        assertThat(headers.containsHeader(USER_ID_HEADER)).isFalse();
        assertThat(headers.containsHeader(USER_ROLES_HEADER)).isFalse();
    }

    @Test
    void spoofedIdentityHeadersAreStrippedOnPublicRoutes() {
        run(MockServerHttpRequest.post("/api/auth/login")
                .header(USER_ID_HEADER, "someone-else")
                .header(USER_ROLES_HEADER, "ROLE_SUPER_ADMIN"));

        HttpHeaders headers = forwarded.get().getRequest().getHeaders();
        assertThat(headers.containsHeader(USER_ID_HEADER)).isFalse();
        assertThat(headers.containsHeader(USER_ROLES_HEADER)).isFalse();
        assertThat(forwarded.get().<String>getAttribute(USER_ID_ATTR)).isNull();
    }

    @Test
    void missingAuthorizationHeaderIsRejected() {
        assertUnauthorized(run(MockServerHttpRequest.get("/api/users/me")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic dXNlcjpwYXNz", "Bearer", "Bearer   ", "Token abc.def.ghi", "Bearer not-a-jwt"})
    void malformedAuthorizationHeaderIsRejected(String header) {
        assertUnauthorized(run(MockServerHttpRequest.get("/api/users/me").header(HttpHeaders.AUTHORIZATION, header)));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        assertRejectedToken(TestTokens.signedWithOtherKey());
    }

    @Test
    void tokenWithUnknownKeyIdIsRejected() {
        assertRejectedToken(TestTokens.signedWithUnknownKid());
    }

    @Test
    void algNoneTokenIsRejected() {
        assertRejectedToken(TestTokens.algNone());
    }

    @Test
    void hs256TokenIsRejectedEvenWhenSignedWithThePublicKey() {
        assertRejectedToken(TestTokens.hs256WithPublicKey());
    }

    @Test
    void wrongIssuerIsRejected() {
        assertRejectedToken(TestTokens.rs256(claims -> claims.issuer("someone-else").claim("role", "ROLE_ADMIN")));
    }

    @Test
    void wrongAudienceIsRejected() {
        assertRejectedToken(TestTokens.rs256(claims -> claims.audience(List.of("other-app")).claim("role", "ROLE_ADMIN")));
    }

    @Test
    void missingAudienceIsRejected() {
        assertRejectedToken(TestTokens.rs256(claims -> claims.claims(c -> c.remove(JwtClaimNames.AUD))
                .claim("role", "ROLE_ADMIN")));
    }

    @Test
    void expiredTokenBeyondClockSkewIsRejected() {
        Instant expired = Instant.now().minusSeconds(31 + 5);
        assertRejectedToken(TestTokens.rs256(claims -> claims.issuedAt(expired.minusSeconds(600)).expiresAt(expired)
                .claim("role", "ROLE_CUSTOMER")));
    }

    @Test
    void tokenExpiredWithinClockSkewIsAccepted() {
        Instant expired = Instant.now().minusSeconds(10);
        String token = TestTokens.rs256(claims -> claims.issuedAt(expired.minusSeconds(600)).expiresAt(expired)
                .claim("role", "ROLE_CUSTOMER"));

        run(MockServerHttpRequest.get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));

        assertThat(forwarded.get()).isNotNull();
    }

    @Test
    void notYetValidTokenIsRejected() {
        Instant later = Instant.now().plusSeconds(120);
        assertRejectedToken(TestTokens.rs256(claims -> claims.notBefore(later).claim("role", "ROLE_CUSTOMER")));
    }

    @Test
    void tokenWithoutExpiryIsRejected() {
        assertRejectedToken(TestTokens.rs256(claims -> claims.claims(c -> c.remove(JwtClaimNames.EXP))
                .claim("role", "ROLE_CUSTOMER")));
    }

    @Test
    void tokenWithoutRoleIsRejected() {
        assertRejectedToken(TestTokens.rs256(claims -> { }));
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/auth/login",
            "POST, /api/auth/refresh",
            "POST, /api/users/register/customer",
            "POST, /api/users/register/merchant",
            "GET, /.well-known/jwks.json",
            "GET, /api/public/products",
            "GET, /api/public/stores/STR-2610-7K2M9Q/reviews"
    })
    void publicRoutesPassWithoutToken(String method, String path) {
        MockServerWebExchange exchange = run(MockServerHttpRequest.method(HttpMethod.valueOf(method), path));

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(forwarded.get()).isNotNull();
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /api/auth/login",
            "POST, /api/auth/logout",
            "POST, /api/public/products",
            "DELETE, /api/public/stores/STR-2610-7K2M9Q",
            "POST, /api/users/register/admin",
            "POST, /.well-known/jwks.json"
    })
    void otherMethodsOnPublicPathsNeedToken(String method, String path) {
        assertUnauthorized(run(MockServerHttpRequest.method(HttpMethod.valueOf(method), path)));
    }

    @ParameterizedTest
    @CsvSource({
            "ROLE_CUSTOMER,    /api/customer/cart",
            "ROLE_MERCHANT,    /api/merchant/orders",
            "ROLE_ASSISTANT,   /api/merchant/orders/ORD-2610-7K2M9Q/ship",
            "ROLE_MERCHANT,    /api/merchant/owner/assistants",
            "ROLE_ADMIN,       /api/admin/orders",
            "ROLE_SUPER_ADMIN, /api/admin/merchants/pending",
            "ROLE_SUPER_ADMIN, /api/super-admin/admins",
            "ROLE_CUSTOMER,    /api/users/me",
            "ROLE_ASSISTANT,   /api/users/me"
    })
    void allowedRolesPassThePrefixCheck(String role, String path) {
        MockServerWebExchange exchange = run(MockServerHttpRequest.get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.valid(role)));

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(forwarded.get()).isNotNull();
    }

    @ParameterizedTest
    @CsvSource({
            "ROLE_MERCHANT,    /api/customer/cart",
            "ROLE_ADMIN,       /api/customer/checkout",
            "ROLE_CUSTOMER,    /api/merchant/orders",
            "ROLE_ASSISTANT,   /api/merchant/owner/assistants",
            "ROLE_ASSISTANT,   /api/merchant/owner/bank-accounts",
            "ROLE_CUSTOMER,    /api/admin/orders",
            "ROLE_MERCHANT,    /api/admin/merchants/pending",
            "ROLE_ADMIN,       /api/super-admin/admins",
            "ROLE_SERVICE,     /api/admin/users"
    })
    void wrongRoleGets403WithStandardBody(String role, String path) {
        MockServerWebExchange exchange = run(MockServerHttpRequest.get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.valid(role)));

        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"status\":403", "\"error\":\"Forbidden\"", "\"code\":\"ACCESS_DENIED\"",
                        "\"message\":\"Access denied\"", "\"path\":\"" + path + "\"");
    }

    @Test
    void unreachableJwksGives503NotA401() {
        JwtAuthenticationFilter noKeys = new JwtAuthenticationFilter(JwtConfig.jwtDecoder(PROPERTIES,
                () -> Mono.error(new IllegalStateException("connection refused")), Clock.systemUTC()), PROPERTIES);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.valid("ROLE_CUSTOMER")));

        noKeys.filter(exchange, chain).block();

        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"code\":\"SERVICE_UNAVAILABLE\"");
    }

    private MockServerWebExchange run(MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        filter.filter(exchange, chain).block();
        return exchange;
    }

    private void assertRejectedToken(String token) {
        assertUnauthorized(run(MockServerHttpRequest.get("/api/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));
    }

    private void assertUnauthorized(MockServerWebExchange exchange) {
        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        // One body for every reason, in the platform's standard error shape
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"status\":401", "\"error\":\"Unauthorized\"", "\"code\":\"UNAUTHORIZED\"",
                        "\"message\":\"" + JwtAuthenticationFilter.UNAUTHORIZED_MESSAGE + "\"");
    }
}
