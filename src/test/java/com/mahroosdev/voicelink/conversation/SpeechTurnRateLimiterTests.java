package com.mahroosdev.voicelink.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class SpeechTurnRateLimiterTests {
    @Test void slidingWindowAppliesPerUserAndGlobalLimitsAtExactBoundary() {
        AtomicLong now = new AtomicLong(1);
        SpeechTurnRateLimiter limiter = new SpeechTurnRateLimiter(2, 3, now::get);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        assertThat(limiter.tryAcquire(a)).isTrue();
        assertThat(limiter.tryAcquire(a)).isTrue();
        assertThat(limiter.tryAcquire(a)).isFalse();
        assertThat(limiter.tryAcquire(b)).isTrue();
        assertThat(limiter.tryAcquire(c)).isFalse();
        assertThat(limiter.trackedUsers()).isEqualTo(2);
        now.addAndGet(TimeUnit.MINUTES.toNanos(1) - 1);
        assertThat(limiter.tryAcquire(c)).isFalse();
        now.incrementAndGet();
        assertThat(limiter.tryAcquire(c)).isTrue();
        assertThat(limiter.trackedUsers()).isEqualTo(1);
    }

    @Test void manySequentialUsersNeverLeaveUnboundedAccounting() {
        AtomicLong now = new AtomicLong();
        SpeechTurnRateLimiter limiter = new SpeechTurnRateLimiter(1, 3, now::get);
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire(UUID.randomUUID())).isTrue();
            assertThat(limiter.trackedUsers()).isEqualTo(1);
            now.addAndGet(TimeUnit.MINUTES.toNanos(1));
        }
    }

    @Test void rejectsUnsafeConfiguration() {
        assertThatThrownBy(() -> new SpeechTurnRateLimiter(0, 60, System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpeechTurnRateLimiter(12, 11, System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpeechTurnRateLimiter(12, 1001, System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
