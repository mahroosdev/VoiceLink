package com.mahroosdev.voicelink.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import com.mahroosdev.voicelink.ai.ProviderFailure;
import org.junit.jupiter.api.Test;

class SpeechDeadlineBudgetTests {
    @Test void liveSpeechCapsTranslationAtFifteenSecondsAndReservesTts() {
        assertThat(SpeechTurnService.translationBudget(Duration.ofSeconds(45).toNanos(), true))
                .isEqualTo(Duration.ofSeconds(15));
        assertThat(SpeechTurnService.translationBudget(Duration.ofSeconds(20).toNanos(), true))
                .isEqualTo(Duration.ofMillis(4_750));
    }

    @Test void insufficientRemainingSpeechBudgetFailsBeforeTranslationStarts() {
        assertThatThrownBy(() -> SpeechTurnService.translationBudget(
                Duration.ofSeconds(16).toNanos(), true))
                .isInstanceOfSatisfying(ProviderFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ProviderFailure.Code.TIMEOUT);
                    assertThat(failure.outcome()).isEqualTo(ProviderFailure.Outcome.LOCAL_TIMEOUT);
                });
    }

    @Test void finishAndSendUsesTheRemainingBudgetWithoutSamePipelineTtsReservation() {
        assertThat(SpeechTurnService.translationBudget(Duration.ofSeconds(12).toNanos(), false))
                .isEqualTo(Duration.ofMillis(11_750));
    }
}
