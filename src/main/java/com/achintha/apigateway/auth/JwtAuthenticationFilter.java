package com.achintha.apigateway.auth;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Requires a valid user-service access token on every routed request except the configured public routes, and
 * passes the caller's identity downstream as {@value #USER_ID_HEADER} / {@value #USER_ROLES_HEADER}.
 * <p>
 * Identity headers sent by the client are always removed first, on public routes too, so only the gateway can set
 * them.
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLES_HEADER = "X-User-Roles";

    /**
     * Before the route filters (RequestRateLimiter, CircuitBreaker: order 1 and up) and the routing filters
     * (10000 and up). Rejected requests therefore never reach a service and don't use up a route's rate limit.
     */
    public static final int ORDER = -100;

    // Same claim user-service writes (SecurityConfig.ROLES_CLAIM): e.g. ["ROLE_CUSTOMER"]
    private static final String ROLES_CLAIM = "roles";
    private static final String BEARER_PREFIX = "Bearer ";

    private static final byte[] AUTHENTICATION_REQUIRED =
            "{\"error\":\"Authentication required\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INVALID_TOKEN =
            "{\"error\":\"Invalid or expired token\"}".getBytes(StandardCharsets.UTF_8);

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final ReactiveJwtDecoder jwtDecoder;
    private final List<PublicRouteMatcher> publicRoutes;

    public JwtAuthenticationFilter(ReactiveJwtDecoder jwtDecoder, AuthProperties properties) {
        this.jwtDecoder = jwtDecoder;
        this.publicRoutes = properties.publicRoutes().stream().map(PublicRouteMatcher::of).toList();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(USER_ID_HEADER);
                    headers.remove(USER_ROLES_HEADER);
                })
                .build();

        if (isPublic(request)) {
            return chain.filter(exchange.mutate().request(request).build());
        }

        String token = bearerToken(request);
        if (token == null) {
            return reject(exchange, AUTHENTICATION_REQUIRED);
        }

        return jwtDecoder.decode(token)
                .map(JwtAuthenticationFilter::identity)
                .map(Optional::of)
                // Bad signature, expired, wrong issuer, unparseable, missing subject: all get the same answer
                .onErrorResume(JwtException.class, e -> {
                    log.debug("Rejected token for {} {}: {}", request.getMethod(), request.getPath(), e.getMessage());
                    return Mono.just(Optional.empty());
                })
                .flatMap(identity -> identity
                        .map(id -> chain.filter(exchange.mutate().request(withIdentity(request, id)).build()))
                        .orElseGet(() -> reject(exchange, INVALID_TOKEN)));
    }

    private boolean isPublic(ServerHttpRequest request) {
        return publicRoutes.stream().anyMatch(route -> route.matches(request));
    }

    /** The token from "Authorization: Bearer &lt;token&gt;", or null if the header is missing or malformed. */
    private static String bearerToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private static Identity identity(Jwt jwt) {
        String userId = jwt.getSubject();
        if (!StringUtils.hasText(userId)) {
            throw new BadJwtException("Token has no subject");
        }
        List<String> roles;
        try {
            roles = jwt.getClaimAsStringList(ROLES_CLAIM);
        }
        catch (RuntimeException e) {
            throw new BadJwtException("Malformed roles claim", e);
        }
        return new Identity(userId, roles == null ? "" : String.join(",", roles));
    }

    private static ServerHttpRequest withIdentity(ServerHttpRequest request, Identity identity) {
        return request.mutate()
                .headers(headers -> {
                    headers.set(USER_ID_HEADER, identity.userId());
                    headers.set(USER_ROLES_HEADER, identity.roles());
                })
                .build();
    }

    private static Mono<Void> reject(ServerWebExchange exchange, byte[] body) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    private record Identity(String userId, String roles) {
    }

    private record PublicRouteMatcher(String method, PathPattern path) {

        static PublicRouteMatcher of(AuthProperties.PublicRoute route) {
            return new PublicRouteMatcher(route.method(), PathPatternParser.defaultInstance.parse(route.path()));
        }

        boolean matches(ServerHttpRequest request) {
            return (method == null || method.equalsIgnoreCase(request.getMethod().name()))
                    && path.matches(request.getPath().pathWithinApplication());
        }
    }
}
