package com.achintha.apigateway;

import java.util.List;
import java.util.Map;

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
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Config-level checks of the routes in application.yml. Runs without Config Server or Eureka: no service
 * instances are registered, so lb:// calls fail and exercise each route's circuit breaker fallback.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.config.enabled=false",
                "eureka.client.enabled=false"
        })
class RouteConfigurationTest {

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
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .get().uri(path)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().exists("X-RateLimit-Remaining")
                .expectBody()
                .jsonPath("$.error").isEqualTo(service + " is temporarily unavailable");
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
