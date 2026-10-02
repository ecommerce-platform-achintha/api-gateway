package com.achintha.apigateway.ratelimit;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.achintha.apigateway.auth.JwtAuthenticationFilter;
import com.achintha.apigateway.support.RequestRule;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private static final RateLimitProperties.KeyType IP = RateLimitProperties.KeyType.IP;
    private static final RateLimitProperties.KeyType USER_OR_IP = RateLimitProperties.KeyType.USER_OR_IP;

    private final AtomicInteger passed = new AtomicInteger();
    private final GatewayFilterChain chain = exchange -> {
        passed.incrementAndGet();
        return Mono.empty();
    };

    private final RateLimitFilter filter = new RateLimitFilter(properties(List.of("10.0.0.0/8")));

    @Test
    void loginIsLimitedPerIpWith429AndRetryAfter() {
        for (int i = 0; i < 3; i++) {
            MockServerWebExchange ok = run(post("/api/auth/login", "203.0.113.1", null));
            assertThat(ok.getResponse().getStatusCode()).isNull();
            assertThat(ok.getResponse().getHeaders().getFirst(RateLimitFilter.REMAINING_HEADER))
                    .isEqualTo(String.valueOf(2 - i));
        }

        MockServerWebExchange limited = run(post("/api/auth/login", "203.0.113.1", null));

        assertThat(passed).hasValue(3);
        assertThat(limited.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        long retryAfter = Long.parseLong(limited.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        // 3 per minute, refilled gradually: the next token is ~20 s away
        assertThat(retryAfter).isBetween(1L, 20L);
        assertThat(limited.getResponse().getBodyAsString().block())
                .contains("\"status\":429", "\"code\":\"RATE_LIMITED\"", "\"path\":\"/api/auth/login\"");
    }

    @Test
    void clientsHaveSeparateBuckets() {
        for (int i = 0; i < 3; i++) {
            run(post("/api/auth/login", "203.0.113.1", null));
        }
        assertThat(status(run(post("/api/auth/login", "203.0.113.1", null)))).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(status(run(post("/api/auth/login", "203.0.113.2", null)))).isNull();
    }

    @Test
    void loginTierIsStricterThanDefaultTier() {
        for (int i = 0; i < 3; i++) {
            run(post("/api/auth/login", "203.0.113.1", null));
        }
        assertThat(status(run(post("/api/auth/login", "203.0.113.1", null)))).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Same client, other routes: the default tier (10) still has room
        for (int i = 0; i < 10; i++) {
            assertThat(status(run(get("/api/users/me", "203.0.113.1", null)))).isNull();
        }
        assertThat(status(run(get("/api/users/me", "203.0.113.1", null)))).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void authenticatedRequestsAreKeyedByUserNotIp() {
        for (int i = 0; i < 10; i++) {
            run(get("/api/users/me", "203.0.113.1", "user-a"));
        }
        assertThat(status(run(get("/api/users/me", "203.0.113.1", "user-a")))).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Another user behind the same IP (e.g. a shared NAT), and anonymous use of that IP, are unaffected
        assertThat(status(run(get("/api/users/me", "203.0.113.1", "user-b")))).isNull();
        assertThat(status(run(get("/api/public/products", "203.0.113.1", null)))).isNull();
    }

    @Test
    void ipKeyedTierIgnoresTheUser() {
        for (int i = 0; i < 3; i++) {
            run(post("/api/auth/login", "203.0.113.1", "user-" + i));
        }
        assertThat(status(run(post("/api/auth/login", "203.0.113.1", "user-x"))))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void forwardedForIsHonouredOnlyFromTrustedProxies() {
        // Through the trusted proxy 10.0.0.5, each X-Forwarded-For client gets its own bucket
        for (int i = 0; i < 3; i++) {
            run(post("/api/auth/login", "10.0.0.5", null).header("X-Forwarded-For", "198.51.100.1"));
        }
        assertThat(status(run(post("/api/auth/login", "10.0.0.5", null).header("X-Forwarded-For", "198.51.100.1"))))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(status(run(post("/api/auth/login", "10.0.0.5", null).header("X-Forwarded-For", "198.51.100.2"))))
                .isNull();

        // From an untrusted peer, a made-up X-Forwarded-For doesn't buy a fresh bucket
        for (int i = 0; i < 3; i++) {
            run(post("/api/auth/login", "203.0.113.9", null).header("X-Forwarded-For", "198.51.100." + (10 + i)));
        }
        assertThat(status(run(post("/api/auth/login", "203.0.113.9", null).header("X-Forwarded-For", "198.51.100.99"))))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void sensitiveTierCoversCheckoutAndPaymentSubmission() {
        RateLimitFilter strictCheckout = new RateLimitFilter(properties(List.of()));
        assertThat(status(run(strictCheckout, post("/api/customer/checkout", "203.0.113.1", "user-a")))).isNull();
        assertThat(status(run(strictCheckout, post("/api/customer/orders/ORD-2610-7K2M9Q/payments", "203.0.113.1",
                "user-a")))).isNull();
        assertThat(status(run(strictCheckout, post("/api/customer/checkout", "203.0.113.1", "user-a"))))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void clientIpResolverPicksRightMostUntrustedAddress() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8", "192.168.1.1"));

        assertThat(resolver.resolve(request("10.0.0.5", "1.1.1.1, 198.51.100.7, 192.168.1.1"))).isEqualTo("198.51.100.7");
        assertThat(resolver.resolve(request("203.0.113.1", "198.51.100.7"))).isEqualTo("203.0.113.1");
        assertThat(resolver.resolve(request("10.0.0.5", null))).isEqualTo("10.0.0.5");
        assertThat(resolver.resolve(request("10.0.0.5", "evil.example, 10.1.2.3"))).isEqualTo("10.1.2.3");
    }

    private static MockServerHttpRequest request(String peer, String forwardedFor) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/")
                .remoteAddress(new InetSocketAddress(peer, 40000));
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        return builder.build();
    }

    /** auth: 3/min by IP; sensitive: 2/min; public: 5/min; default: 10/min. */
    private static RateLimitProperties properties(List<String> trustedProxies) {
        Map<String, RateLimitProperties.Tier> tiers = new LinkedHashMap<>();
        tiers.put("auth", new RateLimitProperties.Tier(3, Duration.ofMinutes(1), IP, List.of(
                new RequestRule("POST", "/api/auth/login"),
                new RequestRule("POST", "/api/users/register/**"))));
        tiers.put("sensitive", new RateLimitProperties.Tier(2, Duration.ofMinutes(1), USER_OR_IP, List.of(
                new RequestRule("POST", "/api/customer/checkout"),
                new RequestRule("POST", "/api/customer/orders/*/payments/**"))));
        tiers.put("public", new RateLimitProperties.Tier(5, Duration.ofMinutes(1), USER_OR_IP, List.of(
                new RequestRule("GET", "/api/public/**"))));
        tiers.put("default", new RateLimitProperties.Tier(10, Duration.ofMinutes(1), USER_OR_IP, List.of()));
        return new RateLimitProperties(trustedProxies, tiers);
    }

    private MockServerHttpRequest.BaseBuilder<?> post(String path, String peer, String userId) {
        return withUser(MockServerHttpRequest.post(path).remoteAddress(new InetSocketAddress(peer, 40000)), userId);
    }

    private MockServerHttpRequest.BaseBuilder<?> get(String path, String peer, String userId) {
        return withUser(MockServerHttpRequest.get(path).remoteAddress(new InetSocketAddress(peer, 40000)), userId);
    }

    // The user id travels as a test-only header and is moved into the exchange attribute in run()
    private static MockServerHttpRequest.BaseBuilder<?> withUser(MockServerHttpRequest.BaseBuilder<?> builder,
                                                                 String userId) {
        return userId == null ? builder : builder.header("test-user", userId);
    }

    private MockServerWebExchange run(MockServerHttpRequest.BaseBuilder<?> request) {
        return run(filter, request);
    }

    private MockServerWebExchange run(RateLimitFilter target, MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        String userId = exchange.getRequest().getHeaders().getFirst("test-user");
        if (userId != null) {
            exchange.getAttributes().put(JwtAuthenticationFilter.USER_ID_ATTR, userId);
        }
        target.filter(exchange, chain).block();
        return exchange;
    }

    private static HttpStatus status(MockServerWebExchange exchange) {
        return (HttpStatus) exchange.getResponse().getStatusCode();
    }
}
