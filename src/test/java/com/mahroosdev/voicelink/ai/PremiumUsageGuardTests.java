package com.mahroosdev.voicelink.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PremiumUsageGuardTests {
    private final AtomicLong now = new AtomicLong();
    private SimpleMeterRegistry metrics;

    @BeforeEach void setup() { metrics = new SimpleMeterRegistry(); }

    private PremiumUsageGuard guard(PremiumUsageGuard.Limits translation,
                                    PremiumUsageGuard.Limits tts, int concurrent) {
        return new PremiumUsageGuard(translation, tts, concurrent, 1500, 800,
                metrics, now::get);
    }

    @Test void costSaverOnAndOffEnforceDifferentServerPreviewIntervals() {
        UUID user = UUID.randomUUID(), room = UUID.randomUUID();
        var guard = guard(new PremiumUsageGuard.Limits(8, 16, 40),
                new PremiumUsageGuard.Limits(6, 12, 24), 2);
        try (var permit = guard.acquire(AiServiceProfile.PREMIUM, user, room,
                PremiumUsageGuard.Operation.TRANSLATION, true, true)) { permit.success(); }
        now.set(TimeUnit.MILLISECONDS.toNanos(900));
        assertThatThrownBy(() -> guard.acquire(AiServiceProfile.PREMIUM, user, room,
                PremiumUsageGuard.Operation.TRANSLATION, true, true))
                .isInstanceOfSatisfying(ProviderFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo(ProviderFailure.Code.RATE_LIMIT));

        UUID offRoom = UUID.randomUUID();
        try (var permit = guard.acquire(AiServiceProfile.PREMIUM, user, offRoom,
                PremiumUsageGuard.Operation.TRANSLATION, false, true)) { permit.success(); }
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(900));
        try (var permit = guard.acquire(AiServiceProfile.PREMIUM, user, offRoom,
                PremiumUsageGuard.Operation.TRANSLATION, false, true)) { permit.success(); }
    }

    @Test void paidTranslationAndTtsHaveSeparateUserRoomInstanceAndConcurrencyLimits() {
        var guard = guard(new PremiumUsageGuard.Limits(1, 2, 3),
                new PremiumUsageGuard.Limits(1, 1, 2), 1);
        UUID user = UUID.randomUUID(), room = UUID.randomUUID();
        var active = guard.acquire(AiServiceProfile.PREMIUM, user, room,
                PremiumUsageGuard.Operation.TRANSLATION, true, false);
        assertThatThrownBy(() -> guard.acquire(AiServiceProfile.PREMIUM, UUID.randomUUID(), room,
                PremiumUsageGuard.Operation.TTS, true, false))
                .isInstanceOf(ProviderFailure.class);
        active.success();
        active.close();
        try (var tts = guard.acquire(AiServiceProfile.PREMIUM, user, room,
                PremiumUsageGuard.Operation.TTS, true, false)) { tts.success(); }
        assertThatThrownBy(() -> guard.acquire(AiServiceProfile.PREMIUM, user, UUID.randomUUID(),
                PremiumUsageGuard.Operation.TTS, true, false))
                .isInstanceOf(ProviderFailure.class);
        assertThatThrownBy(() -> guard.acquire(AiServiceProfile.PREMIUM, user, UUID.randomUUID(),
                PremiumUsageGuard.Operation.TRANSLATION, true, false))
                .isInstanceOf(ProviderFailure.class);
    }

    @Test void standardBypassesPaidGuardAndMetricsRemainContentFree() {
        var guard = guard(new PremiumUsageGuard.Limits(1, 1, 1),
                new PremiumUsageGuard.Limits(1, 1, 1), 1);
        UUID user = UUID.randomUUID(), room = UUID.randomUUID();
        try (var ignored = guard.acquire(AiServiceProfile.STANDARD, user, room,
                PremiumUsageGuard.Operation.TRANSLATION, true, true)) { ignored.success(); }
        guard.recordTranslationUsage(AiServiceProfile.STANDARD, true, 100, 100, 100);
        guard.recordTranslationUsage(AiServiceProfile.PREMIUM, true, 10, 4, 2);
        assertThat(metrics.getMeters()).allSatisfy(meter -> meter.getId().getTags().forEach(tag -> {
            assertThat(tag.getValue()).doesNotContain(user.toString(), room.toString());
            assertThat(tag.getKey()).isIn("kind", "cost_saver", "operation", "outcome");
        }));
        assertThat(metrics.get("voicelink.premium.provider.tokens").tag("kind", "input")
                .counter().count()).isEqualTo(10);
    }
}
