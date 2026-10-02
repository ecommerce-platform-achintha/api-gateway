package com.achintha.apigateway.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The platform's standard JSON error body ({@code timestamp, status, error, code, message, path}, see
 * docs/marketplace-design.md section 1), written by the gateway's own filters and fallbacks so clients see the same
 * shape whether the gateway or a service answered.
 */
public final class ApiErrors {

    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    public static final String LENGTH_REQUIRED = "LENGTH_REQUIRED";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

    private ApiErrors() {
    }

    /** Sets the status and writes the body for the exchange's own request path. */
    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = json(status, code, message, exchange.getRequest().getPath().value())
                .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    public static String json(HttpStatus status, String code, String message, String path) {
        return "{\"timestamp\":\"" + Instant.now()
                + "\",\"status\":" + status.value()
                + ",\"error\":\"" + escape(status.getReasonPhrase())
                + "\",\"code\":\"" + escape(code)
                + "\",\"message\":\"" + escape(message)
                + "\",\"path\":\"" + escape(path) + "\"}";
    }

    // The path is client input, so everything JSON treats specially is escaped
    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20 || c == '<' || c == '>' || c == '&') {
                        out.append(String.format("\\u%04x", (int) c));
                    }
                    else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
