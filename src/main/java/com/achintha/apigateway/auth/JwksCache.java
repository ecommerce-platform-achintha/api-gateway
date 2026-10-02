package com.achintha.apigateway.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Key source for the gateway's {@code NimbusReactiveJwtDecoder}: user-service's JWKS, cached.
 * <ul>
 *   <li>The key set is fetched on first use and again on the first request after {@code ttl}.</li>
 *   <li>A token whose {@code kid} isn't in the cached set triggers a re-fetch (key rotation), at most once per
 *       {@code refreshCooldown}.</li>
 *   <li>Concurrent requests share one in-flight fetch.</li>
 *   <li>If a fetch fails, the previous key set keeps being used. With no key set at all,
 *       {@link JwksUnavailableException} is raised, which the filter answers with 503 rather than 401.</li>
 * </ul>
 */
public class JwksCache implements Function<SignedJWT, Flux<JWK>> {

    private static final Logger log = LoggerFactory.getLogger(JwksCache.class);

    private final Supplier<Mono<JWKSet>> fetcher;
    private final Duration ttl;
    private final Duration refreshCooldown;
    private final Clock clock;

    private volatile Snapshot snapshot;
    private Mono<Snapshot> inFlight; // guarded by this

    public JwksCache(Supplier<Mono<JWKSet>> fetcher, Duration ttl, Duration refreshCooldown, Clock clock) {
        this.fetcher = fetcher;
        this.ttl = ttl;
        this.refreshCooldown = refreshCooldown;
        this.clock = clock;
    }

    @Override
    public Flux<JWK> apply(SignedJWT jwt) {
        JWKSelector selector = new JWKSelector(JWKMatcher.forJWSHeader(jwt.getHeader()));
        return current()
                .flatMapMany(current -> {
                    List<JWK> keys = selector.select(current.keys());
                    if (!keys.isEmpty() || !mayRefresh(current)) {
                        return Flux.fromIterable(keys);
                    }
                    return refresh().flatMapIterable(refreshed -> selector.select(refreshed.keys()));
                });
    }

    private Mono<Snapshot> current() {
        Snapshot current = snapshot;
        if (current != null && current.fetchedAt().plus(ttl).isAfter(clock.instant())) {
            return Mono.just(current);
        }
        return refresh();
    }

    private boolean mayRefresh(Snapshot current) {
        return !current.fetchedAt().plus(refreshCooldown).isAfter(clock.instant());
    }

    private synchronized Mono<Snapshot> refresh() {
        if (inFlight == null) {
            inFlight = Mono.defer(fetcher)
                    .map(keys -> new Snapshot(keys, clock.instant()))
                    .doOnNext(fetched -> snapshot = fetched)
                    .onErrorResume(e -> {
                        Snapshot stale = snapshot;
                        if (stale == null) {
                            return Mono.error(new JwksUnavailableException(e));
                        }
                        log.warn("JWKS refresh failed, keeping the cached keys: {}", e.toString());
                        // Count the failed attempt as a fetch, so the cooldown and TTL also space out retries
                        Snapshot retried = new Snapshot(stale.keys(), clock.instant());
                        snapshot = retried;
                        return Mono.just(retried);
                    })
                    .doFinally(signal -> clearInFlight())
                    .cache();
        }
        return inFlight;
    }

    private synchronized void clearInFlight() {
        inFlight = null;
    }

    private record Snapshot(JWKSet keys, Instant fetchedAt) {
    }

    /** The signing keys could not be fetched and none are cached. */
    public static class JwksUnavailableException extends RuntimeException {

        JwksUnavailableException(Throwable cause) {
            super("JWKS unavailable: " + cause, cause);
        }
    }
}
