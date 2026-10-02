package com.achintha.apigateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.discovery.DiscoveryLocatorProperties;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The local profile turns on the discovery-locator debug routes. A static discovery entry stands in for Eureka; its
 * instance isn't running, which doesn't matter: /internal/** must be refused before any call is made.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.config.enabled=false",
                "eureka.client.enabled=false",
                "spring.cloud.discovery.client.simple.instances.user-service[0].uri=http://localhost:1"
        })
@ActiveProfiles("local")
class LocalProfileTest {

    @Autowired
    private DiscoveryLocatorProperties discoveryLocatorProperties;

    @Autowired
    private RouteLocator routeLocator;

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry registry) {
        registry.add("api-gateway.auth.jwks-uri", JwksTestServer::jwksUri);
    }

    @Test
    void discoveryLocatorIsOnInLocalProfile() {
        assertThat(discoveryLocatorProperties.isEnabled()).isTrue();
        assertThat(routeLocator.getRoutes().map(Route::getId).collectList().block())
                .anySatisfy(id -> assertThat(id).contains("user-service").isNotEqualTo("user-service"));
    }

    @Test
    void discoveryRouteCannotReachInternalEndpoints() {
        client().get().uri("/user-service/internal/users/42/security-state")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.valid("ROLE_CUSTOMER"))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void discoveryRouteNeedsToken() {
        client().get().uri("/user-service/api/users/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }
}
