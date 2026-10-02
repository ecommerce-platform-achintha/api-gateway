package com.achintha.apigateway;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.discovery.DiscoveryLocatorProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole gateway with application.yml, without Config Server, Eureka or any service: lb:// calls find no
 * instance, so every routed request that passes the gateway's checks ends in its route's circuit breaker fallback.
 * The JWKS comes from {@link JwksTestServer}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.config.enabled=false",
                "eureka.client.enabled=false",
                // The test client connects from loopback: trusting it lets each test use its own X-Forwarded-For client
                "api-gateway.rate-limit.trusted-proxies=127.0.0.1,::1"
        })
class RouteConfigurationTest {

    private static final String CUSTOMER = TestTokens.valid("ROLE_CUSTOMER");

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private DiscoveryLocatorProperties discoveryLocatorProperties;

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry registry) {
        registry.add("api-gateway.auth.jwks-uri", JwksTestServer::jwksUri);
    }

    // ---- Route configuration ----

    @Test
    void oneRoutePerServiceThroughEureka() {
        Map<String, RouteDefinition> routes = routeDefinitions();

        assertThat(routes).containsOnlyKeys("user-service", "store-service", "product-service", "order-service");
        routes.forEach((id, route) -> assertThat(route.getUri()).hasToString("lb://" + id));
    }

    @ParameterizedTest
    @CsvSource({
            "user-service, userService",
            "store-service, storeService",
            "product-service, productService",
            "order-service, orderService"
    })
    void eachRouteHasItsOwnCircuitBreakerWithFallback(String routeId, String breakerName) {
        FilterDefinition breaker = routeDefinitions().get(routeId).getFilters().stream()
                .filter(filter -> filter.getName().equals("CircuitBreaker"))
                .findFirst()
                .orElseThrow();

        assertThat(breaker.getArgs())
                .containsEntry("name", breakerName)
                .containsEntry("fallbackUri", "forward:/fallback/" + routeId);
    }

    @Test
    void discoveryLocatorIsOffOutsideTheLocalProfile() {
        assertThat(discoveryLocatorProperties.isEnabled()).isFalse();
        assertThat(routes()).extracting(Route::getId)
                .containsExactlyInAnyOrder("user-service", "store-service", "product-service", "order-service");
    }

    /** Every sample path matches exactly one route: the route patterns never overlap. */
    @ParameterizedTest
    @CsvSource({
            // user-service
            "POST, /api/auth/login, user-service",
            "POST, /api/auth/refresh, user-service",
            "POST, /api/auth/change-password, user-service",
            "POST, /api/users/register/customer, user-service",
            "GET, /api/users/me, user-service",
            "GET, /api/users/me/addresses/ADR-1, user-service",
            "POST, /api/merchant/application/resubmit, user-service",
            "POST, /api/merchant/owner/assistants, user-service",
            "PUT, /api/merchant/owner/assistants/USR-2610-7K2M9Q/permissions, user-service",
            "GET, /api/admin/users, user-service",
            "POST, /api/admin/users/USR-2610-7K2M9Q/assistant-ban, user-service",
            "GET, /api/admin/merchants/pending, user-service",
            "POST, /api/admin/merchants/USR-2610-7K2M9Q/approve, user-service",
            "POST, /api/admin/merchants/USR-2610-7K2M9Q/reject, user-service",
            "POST, /api/admin/merchants/USR-2610-7K2M9Q/ban, user-service",
            "POST, /api/admin/merchants/USR-2610-7K2M9Q/unban, user-service",
            "POST, /api/admin/customers/USR-2610-7K2M9Q/ban, user-service",
            "POST, /api/admin/customers/USR-2610-7K2M9Q/unban, user-service",
            "POST, /api/super-admin/admins, user-service",
            "POST, /api/super-admin/admins/USR-2610-7K2M9Q/ban, user-service",
            "GET, /.well-known/jwks.json, user-service",
            // store-service
            "GET, /api/public/stores, store-service",
            "GET, /api/public/stores/STR-2610-7K2M9Q/reviews, store-service",
            "PUT, /api/merchant/store, store-service",
            "PUT, /api/merchant/store/cod, store-service",
            "GET, /api/merchant/owner/bank-accounts/banks, store-service",
            "GET, /api/merchant/owner/couriers/available, store-service",
            "POST, /api/merchant/owner/shipping-templates, store-service",
            "GET, /api/merchant/owner/metrics, store-service",
            "POST, /api/merchant/reviews/REV-2610-7K2M9Q/reply, store-service",
            "POST, /api/customer/stores/STR-2610-7K2M9Q/reviews, store-service",
            "GET, /api/admin/stores/STR-2610-7K2M9Q/score, store-service",
            "POST, /api/admin/stores/reviews/REV-2610-7K2M9Q/hide, store-service",
            "GET, /api/admin/merchants/at-risk, store-service",
            "PUT, /api/admin/banks/BOC, store-service",
            "GET, /api/admin/couriers/DOMEX, store-service",
            "PUT, /api/super-admin/settings/timers.ship-by-hours, store-service",
            "POST, /api/super-admin/holidays, store-service",
            "POST, /api/super-admin/merchants/STR-2610-7K2M9Q/credits, store-service",
            // product-service
            "GET, /api/public/products, product-service",
            "GET, /api/public/products/ITM-2610-7K2M9Q/reviews, product-service",
            "GET, /api/public/categories, product-service",
            "POST, /api/merchant/products, product-service",
            "GET, /api/merchant/products/reviews, product-service",
            "POST, /api/merchant/products/reviews/REV-2610-7K2M9Q/reply, product-service",
            "PATCH, /api/merchant/variants/VAR-2610-7K2M9Q/stock, product-service",
            "POST, /api/merchant/discounts, product-service",
            "POST, /api/customer/products/ITM-2610-7K2M9Q/reviews, product-service",
            "GET, /api/admin/products/ITM-2610-7K2M9Q, product-service",
            "PUT, /api/admin/categories/phones, product-service",
            "POST, /api/admin/reviews/REV-2610-7K2M9Q/hide, product-service",
            // order-service
            "POST, /api/customer/cart/items, order-service",
            "POST, /api/customer/checkout, order-service",
            "GET, /api/customer/orders, order-service",
            "POST, /api/customer/orders/ORD-2610-7K2M9Q/payments, order-service",
            "POST, /api/customer/complaints, order-service",
            "POST, /api/merchant/orders/ORD-2610-7K2M9Q/quote, order-service",
            "POST, /api/merchant/orders/complaints/CMP-2610-7K2M9Q/response, order-service",
            "DELETE, /api/merchant/customer-blocks/USR-2610-7K2M9Q, order-service",
            "POST, /api/admin/orders/ORD-2610-7K2M9Q/resolve, order-service",
            "GET, /api/admin/orders/cod-objections, order-service",
            "POST, /api/admin/complaints/CMP-2610-7K2M9Q/decision, order-service",
            "GET, /api/admin/flagged-references, order-service",
            "GET, /api/admin/customers/USR-2610-7K2M9Q/score, order-service"
    })
    void samplePathMatchesExactlyOneRoute(String method, String path, String expectedRouteId) {
        assertThat(matchingRoutes(method, path)).containsExactly(expectedRouteId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/internal/users/42/security-state", "/internal/reservations", "/api/unknown",
            "/api/admin/customers/USR-2610-7K2M9Q", "/api/admin/merchants/USR-2610-7K2M9Q/delete", "/api/orders",
            "/api/products", "/api/merchant/owner", "/api/public"})
    void otherPathsMatchNoRoute(String path) {
        assertThat(matchingRoutes("GET", path)).isEmpty();
    }

    // ---- Requests through the running gateway ----

    @ParameterizedTest
    @CsvSource({
            "/api/users/me, user-service",
            "/api/customer/stores/STR-2610-7K2M9Q/reviews, store-service",
            "/api/customer/products/ITM-2610-7K2M9Q/reviews, product-service",
            "/api/customer/orders, order-service"
    })
    void unavailableServiceReturns503Fallback(String path, String service) {
        client().get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + CUSTOMER)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().exists("X-RateLimit-Remaining")
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo(service + " is temporarily unavailable")
                .jsonPath("$.path").isEqualTo(path);
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/auth/login",
            "POST, /api/auth/refresh",
            "POST, /api/users/register/customer",
            "POST, /api/users/register/merchant",
            "GET, /.well-known/jwks.json",
            "GET, /api/public/products",
            "GET, /api/public/stores"
    })
    void publicRoutesAreRoutedWithoutToken(String method, String path) {
        // Reaches the (unavailable) service, so the fallback answers rather than the auth filter
        client().method(HttpMethod.valueOf(method)).uri(path)
                .header("X-Forwarded-For", "198.51.100.10")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void protectedRouteWithoutTokenIs401BeforeRouting() {
        client().get().uri("/api/customer/orders")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.path").isEqualTo("/api/customer/orders");
    }

    @Test
    void forgedTokenIs401() {
        client().get().uri("/api/admin/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.signedWithOtherKey())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @ParameterizedTest
    @CsvSource({
            "ROLE_CUSTOMER, /api/admin/orders",
            "ROLE_CUSTOMER, /api/merchant/orders",
            "ROLE_MERCHANT, /api/customer/cart",
            "ROLE_ASSISTANT, /api/merchant/owner/bank-accounts",
            "ROLE_ADMIN, /api/super-admin/settings"
    })
    void wrongRoleForPrefixIs403(String role, String path) {
        client().get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.valid(role))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("ACCESS_DENIED")
                .jsonPath("$.path").isEqualTo(path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/internal/users/42/security-state", "/internal/auth/service-token",
            "/fallback/order-service", "/USER-SERVICE/internal/users/42/security-state"})
    void internalAndFallbackPathsAre404(String path) {
        client().get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + CUSTOMER)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void pathTraversalTowardsInternalIs400() {
        client().get().uri("/api/public/products/../../../internal/reservations")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void gatewayActuatorIsNotExposedOutsideLocal() {
        client().get().uri("/actuator/gateway/routes")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void authResponsesAreNotCachedAndCarrySecurityHeaders() {
        client().post().uri("/api/auth/login")
                .header("X-Forwarded-For", "198.51.100.11")
                .exchange()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer");
    }

    @Test
    void loginIsLimitedTo10PerMinutePerClient() {
        for (int i = 0; i < 10; i++) {
            client().post().uri("/api/auth/login")
                    .header("X-Forwarded-For", "198.51.100.20")
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        client().post().uri("/api/auth/login")
                .header("X-Forwarded-For", "198.51.100.20")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectBody()
                .jsonPath("$.code").isEqualTo("RATE_LIMITED");

        // Another client is unaffected
        client().post().uri("/api/auth/login")
                .header("X-Forwarded-For", "198.51.100.21")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ---- CORS ----

    @Test
    void preflightFromAllowedOriginIsAnswered() {
        client().options().uri("/api/customer/checkout")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type, Idempotency-Key")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        headers -> assertThat(headers).containsIgnoringCase("idempotency-key"));
    }

    @Test
    void preflightFromUnknownOriginIsRejected() {
        client().options().uri("/api/customer/checkout")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void preflightWithUnlistedHeaderIsRejected() {
        client().options().uri("/api/customer/checkout")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-User-Id")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void actualCorsRequestExposesRateLimitHeaders() {
        client().get().uri("/api/public/products")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header("X-Forwarded-For", "198.51.100.30")
                .exchange()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173")
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        headers -> assertThat(headers).contains("Retry-After", "X-RateLimit-Remaining"));
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private List<String> matchingRoutes(String method, String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.valueOf(method), path));
        return Flux.fromIterable(routes())
                .filterWhen(route -> Mono.from(route.getPredicate().apply(exchange)))
                .map(Route::getId)
                .collectList()
                .block();
    }

    private Map<String, RouteDefinition> routeDefinitions() {
        return routeDefinitionLocator.getRouteDefinitions().collectMap(RouteDefinition::getId).block();
    }

    private List<Route> routes() {
        // Building the routes also proves every filter (CircuitBreaker, ...) resolved
        return routeLocator.getRoutes().collectList().block();
    }
}
