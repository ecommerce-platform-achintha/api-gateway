package com.achintha.apigateway.web;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class RequestGuardFilterTest {

    private final RequestGuardFilter filter = new RequestGuardFilter(DataSize.ofKilobytes(1));
    private final AtomicBoolean passed = new AtomicBoolean();
    private final WebFilterChain chain = exchange -> {
        passed.set(true);
        return Mono.empty();
    };

    @ParameterizedTest
    @ValueSource(strings = {"/internal", "/internal/users/42/security-state", "/INTERNAL/x", "//internal/x",
            "/internal;v=1/x", "/%69nternal/x", "/fallback/user-service"})
    void internalAndFallbackPathsAre404(String path) {
        MockServerWebExchange exchange = run(rawGet(path));

        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"code\":\"NOT_FOUND\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/public/../../internal/x", "/api/./users/me", "/api/public/%2e%2e/admin",
            "/api/users%2Fme", "/api/users%5Cme"})
    void dotSegmentsAndEncodedSlashesAre400(String path) {
        MockServerWebExchange exchange = run(rawGet(path));

        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/users/me", "/api/admin/categories/internal", "/api/public/products/internal-use"})
    void ordinaryPathsPass(String path) {
        run(rawGet(path));

        assertThat(passed).isTrue();
    }

    @Test
    void bodyOverTheLimitIs413() {
        MockServerWebExchange exchange = run(MockServerHttpRequest.post("/api/customer/checkout")
                .contentLength(1025));

        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void bodyAtTheLimitPasses() {
        run(MockServerHttpRequest.post("/api/customer/checkout").contentLength(1024));

        assertThat(passed).isTrue();
    }

    @Test
    void chunkedBodyWithoutLengthIs411() {
        MockServerWebExchange exchange = run(MockServerHttpRequest.post("/api/customer/checkout")
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked"));

        assertThat(passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.LENGTH_REQUIRED);
    }

    /** The path exactly as sent on the wire: no re-encoding, and "//x" stays a path. */
    private static MockServerHttpRequest.BaseBuilder<?> rawGet(String path) {
        return MockServerHttpRequest.method(HttpMethod.GET, URI.create("http://localhost" + path));
    }

    private MockServerWebExchange run(MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        filter.filter(exchange, chain).block();
        return exchange;
    }
}
