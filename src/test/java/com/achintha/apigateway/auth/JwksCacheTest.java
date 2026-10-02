package com.achintha.apigateway.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.achintha.apigateway.TestTokens;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwksCacheTest {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration COOLDOWN = Duration.ofSeconds(10);

    private final MutableClock clock = new MutableClock();
    private final AtomicInteger fetches = new AtomicInteger();
    private final AtomicReference<Mono<JWKSet>> response = new AtomicReference<>(Mono.just(TestTokens.jwks()));
    private final JwksCache cache = new JwksCache(() -> {
        fetches.incrementAndGet();
        return response.get();
    }, TTL, COOLDOWN, clock);

    @Test
    void keysAreFetchedOnceAndCached() {
        for (int i = 0; i < 5; i++) {
            assertThat(keysFor(TestTokens.valid("ROLE_CUSTOMER"))).isEqualTo(1);
        }
        assertThat(fetches).hasValue(1);
    }

    @Test
    void keysAreRefetchedAfterTtl() {
        keysFor(TestTokens.valid("ROLE_CUSTOMER"));
        clock.advance(TTL.plusSeconds(1));

        keysFor(TestTokens.valid("ROLE_CUSTOMER"));

        assertThat(fetches).hasValue(2);
    }

    @Test
    void unknownKidTriggersRefetchAtMostOncePerCooldown() {
        keysFor(TestTokens.valid("ROLE_CUSTOMER"));
        clock.advance(COOLDOWN.plusSeconds(1));

        assertThat(keysFor(TestTokens.signedWithUnknownKid())).isZero();
        assertThat(keysFor(TestTokens.signedWithUnknownKid())).isZero();
        assertThat(fetches).as("second unknown kid is within the cooldown").hasValue(2);

        clock.advance(COOLDOWN.plusSeconds(1));
        keysFor(TestTokens.signedWithUnknownKid());
        assertThat(fetches).hasValue(3);
    }

    @Test
    void unknownKidRightAfterAFetchDoesNotRefetch() {
        keysFor(TestTokens.valid("ROLE_CUSTOMER"));

        keysFor(TestTokens.signedWithUnknownKid());

        assertThat(fetches).hasValue(1);
    }

    @Test
    void failedRefreshKeepsCachedKeys() {
        keysFor(TestTokens.valid("ROLE_CUSTOMER"));
        response.set(Mono.error(new IllegalStateException("user-service down")));
        clock.advance(TTL.plusSeconds(1));

        assertThat(keysFor(TestTokens.valid("ROLE_CUSTOMER"))).isEqualTo(1);
        assertThat(fetches).hasValue(2);
    }

    @Test
    void noKeysAtAllIsReportedAsUnavailable() {
        response.set(Mono.error(new IllegalStateException("user-service down")));

        assertThatThrownBy(() -> keysFor(TestTokens.valid("ROLE_CUSTOMER")))
                .isInstanceOf(JwksCache.JwksUnavailableException.class);
    }

    private int keysFor(String token) {
        try {
            return cache.apply(SignedJWT.parse(token)).collectList().block().size();
        }
        catch (java.text.ParseException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
