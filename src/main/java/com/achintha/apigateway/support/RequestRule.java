package com.achintha.apigateway.support;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * An HTTP method plus a Spring path pattern, as written in configuration ({@code method: POST, path: /api/auth/login}).
 *
 * @param method HTTP method, or null for any method
 * @param path   Spring path pattern, e.g. {@code /api/auth/login} or {@code /api/public/**}
 */
public record RequestRule(String method, @NotBlank String path) {

    public Matcher matcher() {
        return new Matcher(method, PathPatternParser.defaultInstance.parse(path));
    }

    public record Matcher(String method, PathPattern path) {

        public boolean matches(ServerHttpRequest request) {
            return (method == null || method.equalsIgnoreCase(request.getMethod().name()))
                    && path.matches(request.getPath().pathWithinApplication());
        }
    }
}
