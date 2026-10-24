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
        assertThat(ProviderFailure.forHttpStatus(408).outcome())
                .isEqualTo(ProviderFailure.Outcome.PROVIDER_HTTP_408);
        assertThat(ProviderFailure.forHttpStatus(429).outcome())
                .isEqualTo(ProviderFailure.Outcome.PROVIDER_HTTP_429);
        assertThat(ProviderFailure.forHttpStatus(456).outcome())
                .isEqualTo(ProviderFailure.Outcome.PROVIDER_QUOTA);
        assertThat(ProviderFailure.forHttpStatus(502).outcome())
                .isEqualTo(ProviderFailure.Outcome.PROVIDER_HTTP_5XX);
        assertThat(ProviderFailure.forHttpStatus(502).retryable()).isTrue();
        assertThat(ProviderFailure.forHttpStatus(503).retryable()).isTrue();
        assertThat(ProviderFailure.forHttpStatus(500).retryable()).isFalse();
        assertThat(ProviderFailure.forHttpStatus(504).outcome())
                .isEqualTo(ProviderFailure.Outcome.PROVIDER_HTTP_5XX);
        assertThat(ProviderFailure.forHttpStatus(504).retryable()).isFalse();
        assertThat(ProviderFailure.localTimeout().outcome())
                .isEqualTo(ProviderFailure.Outcome.LOCAL_TIMEOUT);
        assertThat(ProviderFailure.networkIo().outcome())
                .isEqualTo(ProviderFailure.Outcome.NETWORK_IO);
        assertThat(ProviderFailure.networkIo().retryable()).isTrue();
    }

    @Test void missingFreeTierConfirmationBlocksAllStandardKeys() {
        var settings = new StandardProviderSettings(false, "groq", "gemini");
        org.assertj.core.api.Assertions.assertThatThrownBy(settings::groqKey)
                .isInstanceOf(ProviderFailure.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(settings::geminiKey)
                .isInstanceOf(ProviderFailure.class);
    }
}
