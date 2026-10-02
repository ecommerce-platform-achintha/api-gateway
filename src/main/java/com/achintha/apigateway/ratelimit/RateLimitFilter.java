package com.achintha.apigateway.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.achintha.apigateway.auth.JwtAuthenticationFilter;
import com.achintha.apigateway.support.ApiErrors;
import com.achintha.apigateway.support.RequestRule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Per-client rate limiting for every routed request. The first configured tier with a matching route applies (else
 * the {@code default} tier), and its bucket is keyed by the authenticated user id or the client IP (see
 * {@link RateLimitProperties.KeyType}). Over the limit: 429 with {@code Retry-After} (seconds).
 * <p>
 * Every limited response carries {@value #REMAINING_HEADER}. Buckets live in this gateway instance's memory; several
 * instances need a shared (Redis-backed) limiter to enforce one global limit.
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitFilter implements GlobalFilter, Ordered {

    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    /** After authentication (the user id is known), before the route filters and routing. */
    public static final int ORDER = JwtAuthenticationFilter.ORDER + 10;

    private static final int MAX_BUCKETS = 100_000;

    private final List<NamedTier> tiers;
    private final NamedTier defaultTier;
    private final ClientIpResolver clientIpResolver;
    private final Cache<String, Bucket> buckets;

    public RateLimitFilter(RateLimitProperties properties) {
        this.tiers = properties.tiers().entrySet().stream()
                .filter(entry -> !entry.getKey().equals(RateLimitProperties.DEFAULT_TIER))
                .map(NamedTier::of)
                .toList();
        this.defaultTier = properties.tiers().entrySet().stream()
                .filter(entry -> entry.getKey().equals(RateLimitProperties.DEFAULT_TIER))
                .map(NamedTier::of)
                .findFirst()
                .orElse(null);
        this.clientIpResolver = new ClientIpResolver(properties.trustedProxies());
        // An idle bucket is full again after its refill period, so dropping it then changes nothing
        Duration longestRefill = properties.tiers().values().stream()
                .map(RateLimitProperties.Tier::refillPeriod)
                .max(Duration::compareTo)
                .orElse(Duration.ofMinutes(1));
        this.buckets = Caffeine.newBuilder()
                .maximumSize(MAX_BUCKETS)
                .expireAfterAccess(longestRefill)
                .build();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        NamedTier tier = tierFor(request);
        if (tier == null) {
            return chain.filter(exchange);
        }

        String bucketKey = tier.name() + "|" + clientKey(exchange, tier.config().key());
        Bucket bucket = buckets.get(bucketKey, key -> newBucket(tier.config()));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set(REMAINING_HEADER, Long.toString(probe.getRemainingTokens()));
        if (probe.isConsumed()) {
            return chain.filter(exchange);
        }
        long retryAfterSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(
                probe.getNanosToWaitForRefill() + TimeUnit.SECONDS.toNanos(1) - 1));
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return ApiErrors.write(exchange, HttpStatus.TOO_MANY_REQUESTS, ApiErrors.RATE_LIMITED,
                "Too many requests, retry after " + retryAfterSeconds + " s");
    }

    private NamedTier tierFor(ServerHttpRequest request) {
        return tiers.stream()
                .filter(tier -> tier.routes().stream().anyMatch(route -> route.matches(request)))
                .findFirst()
                .orElse(defaultTier);
    }

    private String clientKey(ServerWebExchange exchange, RateLimitProperties.KeyType keyType) {
        String userId = exchange.getAttribute(JwtAuthenticationFilter.USER_ID_ATTR);
        if (keyType == RateLimitProperties.KeyType.USER_OR_IP && userId != null) {
            return "user:" + userId;
        }
        return "ip:" + clientIpResolver.resolve(exchange.getRequest());
    }

    private static Bucket newBucket(RateLimitProperties.Tier tier) {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(tier.capacity()).refillGreedy(tier.capacity(), tier.refillPeriod()))
                .build();
    }

    private record NamedTier(String name, RateLimitProperties.Tier config, List<RequestRule.Matcher> routes) {

        static NamedTier of(Map.Entry<String, RateLimitProperties.Tier> entry) {
            return new NamedTier(entry.getKey(), entry.getValue(),
                    entry.getValue().routes().stream().map(RequestRule::matcher).toList());
        }
    }
}
