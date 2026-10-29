package com.mahroosdev.voicelink.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class EndpointAbuseRateLimiterTests {
    @Test void appliesAddressAndAccountLimitsWithoutChargingRejectedRequests() {
        var limiter = limiter(2, 4, 2, 4, 2, 4, 1, 3, 2, 2, 4, new AtomicLong());
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(limiter.tryAcquire(EndpointAbuseRateLimiter.Route.LOGIN, "192.0.2.1", first)).isTrue();
        assertThat(limiter.tryAcquire(EndpointAbuseRateLimiter.Route.LOGIN, "192.0.2.1", second)).isTrue();
        assertThat(limiter.tryAcquire(EndpointAbuseRateLimiter.Route.LOGIN, "192.0.2.1", second)).isFalse();
        assertThat(limiter.tryAcquire(EndpointAbuseRateLimiter.Route.LOGIN, "192.0.2.2", second)).isTrue();

        assertThat(limiter.tryAcquire(EndpointAbuseRateLimiter.Route.ROOM_CREATION, "192.0.2.1", first)).isTrue();
        assertThat(limiter.tryAcquire(EndpointAbuseRateLimiter.Route.ROOM_CREATION, "192.0.2.2", first)).isFalse();
        assertThat(limiter.tryAcquire(EndpointAbuseRateLimiter.Route.ROOM_CREATION, "192.0.2.2", second)).isTrue();

        assertThat(limiter.tryAcquire(EndpointAbuseRateLimiter.Route.INVITATION, "192.0.2.1", first)).isTrue();
        assertThat(limiter.tryAcquire(EndpointAbuseRateLimiter.Route.INVITATION, "192.0.2.2", first)).isTrue();
        assertThat(limiter.tryAcquire(EndpointAbuseRateLimiter.Route.INVITATION, "192.0.2.3", first)).isFalse();
        assertThat(limiter.tryAcquire(EndpointAbuseRateLimiter.Route.INVITATION, "192.0.2.1", second)).isTrue();
    }

    @Test void expiresEntriesAndCleansIdentityState() {
        var ticker = new AtomicLong();
        var limiter = limiter(1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 2, ticker);
        var route = EndpointAbuseRateLimiter.Route.REGISTRATION;

        assertThat(limiter.tryAcquire(route, "192.0.2.1", null)).isTrue();
        assertThat(limiter.tryAcquire(route, "192.0.2.1", null)).isFalse();
        assertThat(limiter.trackedAttempts(route)).isEqualTo(1);
        ticker.set(TimeUnit.MINUTES.toNanos(1));
        assertThat(limiter.tryAcquire(route, "192.0.2.1", null)).isTrue();
        assertThat(limiter.trackedAttempts(route)).isEqualTo(1);
        assertThat(limiter.trackedIdentities(route)).isEqualTo(1);
    }

    @Test void concurrentRequestsCannotExceedTheAddressOrInstanceLimit() throws Exception {
        var limiter = limiter(5, 100, 5, 100, 5, 100, 5, 100, 5, 10, 100, new AtomicLong());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(24)) {
            List<java.util.concurrent.Future<Boolean>> requests = new ArrayList<>();
            for (int i = 0; i < 48; i++) {
                requests.add(executor.submit(() -> {
                    start.await();
                    return limiter.tryAcquire(EndpointAbuseRateLimiter.Route.LOGIN, "198.51.100.9", null);
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var request : requests) if (request.get(5, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(5);
        }
    }

    @Test void concurrentRequestsFromDifferentAddressesCannotExceedInstanceLimit() throws Exception {
        var limiter = limiter(5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, new AtomicLong());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(24)) {
            List<java.util.concurrent.Future<Boolean>> requests = new ArrayList<>();
            for (int i = 0; i < 48; i++) {
                String address = "198.51.100." + i;
                requests.add(executor.submit(() -> {
                    start.await();
                    return limiter.tryAcquire(EndpointAbuseRateLimiter.Route.LOGIN, address, null);
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var request : requests) if (request.get(5, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(5);
            assertThat(limiter.trackedAttempts(EndpointAbuseRateLimiter.Route.LOGIN)).isEqualTo(5);
        }
    }

    @Test void identityStateCannotGrowPastTheConfiguredInstanceWindow() {
        var ticker = new AtomicLong();
        var limiter = limiter(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, ticker);
        var route = EndpointAbuseRateLimiter.Route.LOGIN;
        for (int i = 0; i < 40; i++) {
            limiter.tryAcquire(route, "198.51.100." + i, null);
        }
        assertThat(limiter.trackedAttempts(route)).isEqualTo(4);
        assertThat(limiter.trackedIdentities(route)).isEqualTo(4);
        ticker.set(TimeUnit.MINUTES.toNanos(1));
        assertThat(limiter.trackedAttempts(route)).isZero();
        assertThat(limiter.trackedIdentities(route)).isZero();
    }

    @Test void rejectsUnsafeConfiguredLimits() {
        assertThatThrownBy(() -> limiter(0, 2, 1, 2, 1, 2, 1, 2, 1, 2, 2, new AtomicLong()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(2, 1, 1, 2, 1, 2, 1, 2, 1, 2, 2, new AtomicLong()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(1, 10_001, 1, 2, 1, 2, 1, 2, 1, 2, 2, new AtomicLong()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static EndpointAbuseRateLimiter limiter(int loginAddress, int loginInstance,
            int registrationAddress, int registrationInstance, int resetAddress, int resetInstance,
            int roomAccount, int roomInstance, int invitationAddress, int invitationAccount,
            int invitationInstance, AtomicLong ticker) {
        return new EndpointAbuseRateLimiter(new EndpointAbuseRateLimiter.Limits(loginAddress, loginInstance,
                registrationAddress, registrationInstance, resetAddress, resetInstance,
                roomAccount, roomInstance, invitationAddress, invitationAccount, invitationInstance), ticker::get);
    }
}
