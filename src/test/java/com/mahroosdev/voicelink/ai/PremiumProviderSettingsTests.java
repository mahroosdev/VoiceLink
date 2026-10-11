package com.mahroosdev.voicelink.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PremiumProviderSettingsTests {
    @Test void disabledOrMissingKeyBlocksPaidStagesWithoutRevealingConfiguration() {
        for (var settings : new PremiumProviderSettings[] {
                new PremiumProviderSettings("false", "synthetic-key"),
                new PremiumProviderSettings("TRUE", "synthetic-key"),
                new PremiumProviderSettings("true", ""),
                new PremiumProviderSettings("true", "  ") }) {
            assertThat(settings.available()).isFalse();
            assertThatThrownBy(settings::requireAvailable).isInstanceOfSatisfying(ProviderFailure.class,
                    failure -> {
                        assertThat(failure.code()).isEqualTo(ProviderFailure.Code.CONFIGURATION);
                        assertThat(failure.getMessage()).doesNotContain("synthetic-key");
                    });
            assertThatThrownBy(settings::geminiKey).isInstanceOf(ProviderFailure.class);
        }
        assertThat(new PremiumProviderSettings("true", " synthetic-key ").available()).isTrue();
    }
}
