package com.mahroosdev.voicelink.conversation;

import static org.assertj.core.api.Assertions.assertThat;

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
}
