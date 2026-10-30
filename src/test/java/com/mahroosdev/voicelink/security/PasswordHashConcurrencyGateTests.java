package com.mahroosdev.voicelink.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mahroosdev.voicelink.auth.PasswordResetSettings;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class PasswordHashConcurrencyGateTests {
    @Test void boundsConcurrentHashesAndReleasesPermits() {
        var gate = new PasswordHashConcurrencyGate(2);
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isFalse();
        gate.release();
        assertThat(gate.tryAcquire()).isTrue();
        gate.release();
        gate.release();
    }

    @Test void conservativeSingleHashBudgetRejectsOverlapAndRecovers() {
        var gate = new PasswordHashConcurrencyGate(1);
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isFalse();
        gate.release();
        assertThat(gate.tryAcquire()).isTrue();
        gate.release();
    }

    @Test void concurrentAcquisitionNeverExceedsTheConfiguredLimit() throws Exception {
        var gate = new PasswordHashConcurrencyGate(1);
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> {
                if (!gate.tryAcquire()) throw new AssertionError("first permit was unavailable");
                acquired.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test release timed out");
                } finally {
                    gate.release();
                }
                return null;
            });
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.tryAcquire()).isFalse();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(gate.tryAcquire()).isTrue();
            gate.release();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test void rejectsUnsafeConcurrencyConfiguration() {
        assertThatThrownBy(() -> new PasswordHashConcurrencyGate(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PasswordHashConcurrencyGate(5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void saturatedHashRouteReturnsGenericNoStore429WithoutCallingTheHandler() throws Exception {
        var limiter = new EndpointAbuseRateLimiter(new EndpointAbuseRateLimiter.Limits(
                10, 100, 10, 100, 10, 100, 10, 100, 10, 10, 100, 10, 100), System::nanoTime);
        var gate = new PasswordHashConcurrencyGate(1);
        assertThat(gate.tryAcquire()).isTrue();
        var filter = new EndpointAbuseFilter(limiter, mock(PasswordResetSettings.class), gate);
        var request = new MockHttpServletRequest("POST", "/register");
        request.setServletPath("/register");
        var response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        FilterChain chain = (req, res) -> invoked.set(true);
        SecurityContextHolder.clearContext();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getContentAsString()).isEqualTo("Too many requests. Please wait before trying again.");
        assertThat(invoked).isFalse();
        gate.release();
    }

    @Test void accountDeletionIsAlsoCoveredAndPermitIsReleasedWhenHandlerThrows() throws Exception {
        var limiter = new EndpointAbuseRateLimiter(new EndpointAbuseRateLimiter.Limits(
                10, 100, 10, 100, 10, 100, 10, 100, 10, 10, 100, 10, 100), System::nanoTime);
        var gate = new PasswordHashConcurrencyGate(1);
        var filter = new EndpointAbuseFilter(limiter, mock(PasswordResetSettings.class), gate);
        var request = new MockHttpServletRequest("POST", "/account/delete");
        request.setServletPath("/account/delete");
        var response = new MockHttpServletResponse();
        FilterChain failure = (req, res) -> { throw new IllegalStateException("test failure"); };
        SecurityContextHolder.clearContext();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> filter.doFilter(request, response, failure))
                .isInstanceOf(IllegalStateException.class);
        assertThat(gate.tryAcquire()).isTrue();
        gate.release();
    }

    @Test void permitIsReleasedAfterSuccessfulInterruptedHandlerCompletion() throws Exception {
        var limiter = new EndpointAbuseRateLimiter(new EndpointAbuseRateLimiter.Limits(
                10, 100, 10, 100, 10, 100, 10, 100, 10, 10, 100, 10, 100), System::nanoTime);
        var gate = new PasswordHashConcurrencyGate(1);
        var filter = new EndpointAbuseFilter(limiter, mock(PasswordResetSettings.class), gate);
        var request = new MockHttpServletRequest("POST", "/account/delete");
        request.setServletPath("/account/delete");
        var response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();

        filter.doFilter(request, response, (req, res) -> Thread.currentThread().interrupt());

        assertThat(Thread.interrupted()).isTrue();
        assertThat(gate.tryAcquire()).isTrue();
        gate.release();
    }
}
