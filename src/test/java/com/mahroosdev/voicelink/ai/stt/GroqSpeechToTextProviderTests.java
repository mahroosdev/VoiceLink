package com.mahroosdev.voicelink.ai.stt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mahroosdev.voicelink.ai.ProviderFailure;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GroqSpeechToTextProviderTests {
    private final ObjectMapper json = new ObjectMapper();

    @Test void parsesSyntheticTranscriptAndRejectsBadResponses() {
        assertThat(GroqSpeechToTextProvider.parseTranscript(json, "{\"text\":\" வணக்கம் \"}"))
                .isEqualTo("வணக்கம்");
        assertThatThrownBy(() -> GroqSpeechToTextProvider.parseTranscript(json, "{\"error\":{}}"))
                .isInstanceOf(ProviderFailure.class);
        assertThatThrownBy(() -> GroqSpeechToTextProvider.parseTranscript(json, "not-json"))
                .isInstanceOf(ProviderFailure.class);
    }
}
