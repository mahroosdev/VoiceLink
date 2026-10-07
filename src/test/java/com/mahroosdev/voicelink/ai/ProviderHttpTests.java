package com.mahroosdev.voicelink.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderHttpTests {
    @Test void mapsProviderStatusWithoutReturningRawResponses() {
        assertThat(ProviderHttp.codeForStatus(401)).isEqualTo(ProviderFailure.Code.CONFIGURATION);
        assertThat(ProviderHttp.codeForStatus(429)).isEqualTo(ProviderFailure.Code.RATE_LIMIT);
        assertThat(ProviderHttp.codeForStatus(456)).isEqualTo(ProviderFailure.Code.QUOTA);
        assertThat(ProviderHttp.codeForStatus(504)).isEqualTo(ProviderFailure.Code.TIMEOUT);
        assertThat(ProviderHttp.codeForStatus(503)).isEqualTo(ProviderFailure.Code.UNAVAILABLE);
    }

    @Test void missingFreeTierConfirmationBlocksAllStandardKeys() {
        var settings = new StandardProviderSettings(false, "groq", "translator", "eastus",
                "https://api.cognitive.microsofttranslator.com", "speech", "eastus");
        org.assertj.core.api.Assertions.assertThatThrownBy(settings::groqKey)
                .isInstanceOf(ProviderFailure.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(settings::translatorKey)
                .isInstanceOf(ProviderFailure.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(settings::speechKey)
                .isInstanceOf(ProviderFailure.class);
    }
}
