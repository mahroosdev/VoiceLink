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

    @Test void sendsTheSelectedLanguageAndOnlyBoundedGlossaryHintsInOfflineRequestBody() {
        String tamilBody = new String(GroqSpeechToTextProvider.multipart("boundary", new byte[] {1, 2},
                "ta", "Recognize these room terminology hints when clearly heard: வணக்கம், REST API"));
        assertThat(tamilBody).contains("name=\"model\"", "whisper-large-v3",
                "name=\"language\"", "\r\n\r\nta\r\n", "name=\"prompt\"",
                "வணக்கம், REST API", "name=\"file\"", "Content-Type: audio/webm");
        String englishBody = new String(GroqSpeechToTextProvider.multipart("boundary", new byte[] {1}, "en", ""));
        assertThat(englishBody).contains("\r\n\r\nen\r\n").doesNotContain("name=\"prompt\"");
        String bounded = new String(GroqSpeechToTextProvider.multipart("boundary", new byte[] {1},
                "en", "x".repeat(500)));
        assertThat(bounded).contains("x".repeat(224)).doesNotContain("x".repeat(225));
    }
}
