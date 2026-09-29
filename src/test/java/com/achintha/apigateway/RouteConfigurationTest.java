package com.achintha.apigateway;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.discovery.DiscoveryLocatorProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Config-level checks of the routes in application.yml. Runs without Config Server or Eureka: no service
 * instances are registered, so lb:// calls fail and exercise each route's circuit breaker fallback. The JWT secret
 * normally comes from the Config Server, so it is set here directly.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.config.enabled=false",
                "eureka.client.enabled=false",
                "security.jwt.secret=" + RouteConfigurationTest.JWT_SECRET
        })
class RouteConfigurationTest {

    static final String JWT_SECRET = "test-only-jwt-secret-0123456789-abcdefghij";

    // Route ids the discovery locator generates start with the discovery client's name
    private static final String DISCOVERY_ROUTE_PREFIX = "ReactiveCompositeDiscoveryClient_";

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private GatewayProperties gatewayProperties;

    @Autowired
    private DiscoveryLocatorProperties discoveryLocatorProperties;

    @Value("${local.server.port}")
    private int port;

    @Test
    void explicitRoutesPointAtEurekaServicesWithExpectedPaths() {
        Map<String, RouteDefinition> routes = explicitRouteDefinitions();

        assertThat(routes).containsOnlyKeys("user-service", "product-service", "order-service");
        assertRoute(routes.get("user-service"), "lb://user-service", "/api/users/**", "/api/auth/**");
        assertRoute(routes.get("product-service"), "lb://product-service", "/api/products/**", "/api/categories/**");
        assertRoute(routes.get("order-service"), "lb://order-service", "/api/orders/**");
    }

    @ParameterizedTest
    @CsvSource({
            "user-service, userService",
            "product-service, productService",
            "order-service, orderService"
    })
    void eachRouteHasCircuitBreakerWithFallback(String routeId, String breakerName) {
        FilterDefinition breaker = explicitRouteDefinitions().get(routeId).getFilters().stream()
                .filter(filter -> filter.getName().equals("CircuitBreaker"))
                .findFirst()
                .orElseThrow();

        assertThat(breaker.getArgs())
                .containsEntry("name", breakerName)
                .containsEntry("fallbackUri", "forward:/fallback/" + routeId);
    }

    @Test
    void rateLimiterAndDiscoveryLocatorAreEnabled() {
        assertThat(gatewayProperties.getDefaultFilters())
                .anySatisfy(filter -> {
                    assertThat(filter.getName()).isEqualTo("RequestRateLimiter");
                    assertThat(filter.getArgs())
                            .containsEntry("bucket4j-rate-limiter.capacity", "100")
                            .containsEntry("bucket4j-rate-limiter.refillPeriod", "1s");
                });
        assertThat(discoveryLocatorProperties.isEnabled()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "/api/users/42, user-service",
            "/api/users/me/addresses, user-service",
            "/api/auth/login, user-service",
            "/api/products/7, product-service",
            "/api/categories, product-service",
            "/api/orders, order-service",
            "/api/orders/abc/status, order-service"
    })
    void requestPathsMatchExactlyOneExpectedRoute(String path, String expectedRouteId) {
        List<String> matching = Flux.fromIterable(explicitRoutes())
                .filterWhen(route -> Mono.from(route.getPredicate()
                        .apply(MockServerWebExchange.from(MockServerHttpRequest.get(path)))))
                .map(Route::getId)
                .collectList()
                .block();

        assertThat(matching).containsExactly(expectedRouteId);
    }

    @Test
    void unroutedPathsMatchNoExplicitRoute() {
        List<String> matching = Flux.fromIterable(explicitRoutes())
                .filterWhen(route -> Mono.from(route.getPredicate()
                        .apply(MockServerWebExchange.from(MockServerHttpRequest.get("/api/unknown")))))
                .map(Route::getId)
                .collectList()
                .block();

        assertThat(matching).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "/api/users/42, user-service",
            "/api/products, product-service",
            "/api/orders, order-service"
    })
    void unavailableServiceReturns503FallbackInsteadOfHanging(String path, String service) {
        client().get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().exists("X-RateLimit-Remaining")
                .expectBody()
                .jsonPath("$.error").isEqualTo(service + " is temporarily unavailable");
    }

    @Test
    void protectedRouteWithoutTokenIsRejectedBeforeRouting() {
        client().get().uri("/api/orders")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Authentication required");
    }

    @Test
    void publicRouteIsRoutedWithoutToken() {
        // Reaches the (unavailable) user-service route, so the fallback answers rather than the auth filter
        client().post().uri("/api/auth/login")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private static String validToken() {
        NimbusJwtEncoder encoder = NimbusJwtEncoder.withSecretKey(
                new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("user-service")
                .subject("3f2b8c1e-5d4a-4b6f-9e0a-1c2d3e4f5a6b")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .claim("roles", List.of("ROLE_CUSTOMER"))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private Map<String, RouteDefinition> explicitRouteDefinitions() {
        return routeDefinitionLocator.getRouteDefinitions()
                .filter(route -> !route.getId().startsWith(DISCOVERY_ROUTE_PREFIX))
                .collectMap(RouteDefinition::getId)
                .block();
    }

    private List<Route> explicitRoutes() {
        // Building the routes also proves every filter (CircuitBreaker, RequestRateLimiter, ...) resolved
        return routeLocator.getRoutes()
                .filter(route -> !route.getId().startsWith(DISCOVERY_ROUTE_PREFIX))
                .collectList()
                .block();
    }

    private static void assertRoute(RouteDefinition route, String uri, String... paths) {
        assertThat(route.getUri()).hasToString(uri);
        assertThat(route.getPredicates())
                .singleElement()
                .satisfies(predicate -> {
                    assertThat(predicate.getName()).isEqualTo("Path");
                    assertThat(predicate.getArgs().values().stream().toList())
                            .containsExactlyInAnyOrder(paths);
                });
    }
}
