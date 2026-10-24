package com.mahroosdev.voicelink.ai.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class TranslationReliabilitySettingsTests {
    @Test void acceptsFiniteTimeoutsThroughTheFifteenSecondCeiling() {
        assertThat(new TranslationReliabilitySettings(Duration.ofSeconds(15)).timeout())
                .isEqualTo(Duration.ofSeconds(15));
        assertThat(new TranslationReliabilitySettings(Duration.ofSeconds(12)).timeout())
                .isEqualTo(Duration.ofSeconds(12));
    }

    @Test void rejectsNonPositiveAndAboveCeilingTimeouts() {
        assertThatThrownBy(() -> new TranslationReliabilitySettings(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TranslationReliabilitySettings(Duration.ofSeconds(16)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
