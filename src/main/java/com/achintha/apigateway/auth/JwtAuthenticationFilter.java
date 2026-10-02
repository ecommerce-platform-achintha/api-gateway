package com.achintha.apigateway.auth;

import java.util.List;

import com.achintha.apigateway.support.ApiErrors;
import com.achintha.apigateway.support.RequestRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * First line of defence for every routed request (each service validates the JWT again and enforces the real
 * authorization rules):
 * <ol>
 *   <li>Removes any client-supplied {@value #USER_ID_HEADER} / {@value #USER_ROLES_HEADER}, on every request. The
 *       gateway no longer sets them, and services must not rely on them.</li>
 *   <li>Lets the configured public routes through without a token.</li>
 *   <li>Otherwise requires a valid RS256 access token (see {@link JwtConfig}): 401 if missing or invalid.</li>
 *   <li>Applies the coarse path-prefix role rules: 403 if the token's {@code role} isn't allowed there.</li>
 * </ol>
 * The {@code Authorization} header is forwarded unchanged. The caller's user id is stored in
 * {@link #USER_ID_ATTR} for the rate limiter. Tokens are never logged.
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLES_HEADER = "X-User-Roles";

    /** Exchange attribute: {@code sub} of a validated token. Absent on public routes. */
    public static final String USER_ID_ATTR = JwtAuthenticationFilter.class.getName() + ".userId";

    /**
     * Before the rate limiter, the route filters (CircuitBreaker: order 1 and up) and the routing filters (10000 and
     * up), so rejected requests never reach a service.
     */
    public static final int ORDER = -100;

    // Same message for every 401 (missing, malformed, bad signature, expired, wrong issuer/audience...), as the services
    static final String UNAUTHORIZED_MESSAGE = "Authentication required: missing, invalid or expired token";
    static final String FORBIDDEN_MESSAGE = "Access denied";

    private static final String BEARER_PREFIX = "Bearer ";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final ReactiveJwtDecoder jwtDecoder;
    private final List<RequestRule.Matcher> publicRoutes;
    private final List<RoleRuleMatcher> roleRules;

    public JwtAuthenticationFilter(ReactiveJwtDecoder jwtDecoder, AuthProperties properties) {
        this.jwtDecoder = jwtDecoder;
        this.publicRoutes = properties.publicRoutes().stream().map(RequestRule::matcher).toList();
        this.roleRules = properties.roleRules().stream().map(RoleRuleMatcher::of).toList();
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
        ServerWebExchange stripped = exchange.mutate().request(request).build();

        if (isPublic(request)) {
            return chain.filter(stripped);
        }

        String token = bearerToken(request);
        if (token == null) {
            return unauthorized(stripped);
        }

        return jwtDecoder.decode(token)
                .map(Outcome::valid)
                // Nimbus reports a key-source failure as IllegalStateException, not as a JwtException
                .onErrorResume(e -> e instanceof JwtException || causedBy(e, JwksCache.JwksUnavailableException.class),
                        e -> Mono.just(Outcome.invalid(e)))
                .flatMap(outcome -> {
                    if (outcome.jwt() == null) {
                        return rejectInvalid(stripped, outcome.error());
                    }
                    Jwt jwt = outcome.jwt();
                    if (!roleAllowed(request, jwt.getClaimAsString(JwtConfig.ROLE_CLAIM))) {
                        return ApiErrors.write(stripped, HttpStatus.FORBIDDEN, ApiErrors.ACCESS_DENIED,
                                FORBIDDEN_MESSAGE);
                    }
                    stripped.getAttributes().put(USER_ID_ATTR, jwt.getSubject());
                    return chain.filter(stripped);
                });
    }

    private boolean isPublic(ServerHttpRequest request) {
        return publicRoutes.stream().anyMatch(route -> route.matches(request));
    }

    private boolean roleAllowed(ServerHttpRequest request, String role) {
        return roleRules.stream()
                .filter(rule -> rule.path().matches(request.getPath().pathWithinApplication()))
                .findFirst()
                .map(rule -> rule.roles().contains(role))
                .orElse(true);
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

    private static Mono<Void> rejectInvalid(ServerWebExchange exchange, Throwable error) {
        // Without signing keys no token can be checked: that's the gateway's problem, not the caller's
        if (causedBy(error, JwksCache.JwksUnavailableException.class)) {
            log.warn("Cannot verify tokens: {}", error.toString());
            return ApiErrors.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, ApiErrors.SERVICE_UNAVAILABLE,
                    "Authentication is temporarily unavailable");
        }
        // The reason only, never the token
        log.debug("Rejected token for {} {}: {}", exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(), error.getMessage());
        return unauthorized(exchange);
    }

    private static Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return ApiErrors.write(exchange, HttpStatus.UNAUTHORIZED, ApiErrors.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private record Outcome(Jwt jwt, Throwable error) {

        static Outcome valid(Jwt jwt) {
            return new Outcome(jwt, null);
        }

        static Outcome invalid(Throwable error) {
            return new Outcome(null, error);
        }
    }

    private record RoleRuleMatcher(PathPattern path, List<String> roles) {

        static RoleRuleMatcher of(AuthProperties.RoleRule rule) {
            return new RoleRuleMatcher(PathPatternParser.defaultInstance.parse(rule.path()), List.copyOf(rule.roles()));
        }
    }
}
