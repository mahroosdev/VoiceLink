package com.mahroosdev.voicelink.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TextTranslationRateLimiterTests {
    @Test void limitsBothIndividualUsersAndTotalInstanceRequests() {
        var limiter = new TextTranslationRateLimiter(2, 3);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
        assertThat(limiter.tryAcquire(first)).isTrue();
        assertThat(limiter.tryAcquire(first)).isTrue();
        assertThat(limiter.tryAcquire(first)).isFalse();
        assertThat(limiter.tryAcquire(second)).isTrue();
        assertThat(limiter.tryAcquire(third)).isFalse();
    }

    @Test void appliesIndependentBoundedInvalidationLimits() {
        var limiter = new TextTranslationRateLimiter(1, 2, 2, 3);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();

        assertThat(limiter.tryAcquire(first)).isTrue();
        assertThat(limiter.tryAcquireInvalidation(first)).isTrue();
        assertThat(limiter.tryAcquireInvalidation(first)).isTrue();
        assertThat(limiter.tryAcquireInvalidation(first)).isFalse();
        assertThat(limiter.tryAcquireInvalidation(second)).isTrue();
        assertThat(limiter.tryAcquireInvalidation(third)).isFalse();
        assertThat(limiter.tryAcquire(second)).isTrue();
    }

    @Test void rejectsInvalidInvalidationLimitConfiguration() {
        assertThatThrownBy(() -> new TextTranslationRateLimiter(1, 2, 0, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextTranslationRateLimiter(1, 2, 2, 10_001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextTranslationRateLimiter(1, 2, 3, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
