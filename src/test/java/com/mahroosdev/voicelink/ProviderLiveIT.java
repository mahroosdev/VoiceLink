package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.mahroosdev.voicelink.ai.stt.SpeechToTextProvider;
import com.mahroosdev.voicelink.ai.translation.TranslationProvider;
import com.mahroosdev.voicelink.ai.tts.TextToSpeechProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Runs only with the explicitly selected Maven provider-live profile. */
@SpringBootTest(properties = "voicelink.standard.free-tier-confirmed=${VOICELINK_STANDARD_FREE_TIER_CONFIRMED:false}")
@ActiveProfiles("test")
class ProviderLiveIT {
    @Autowired SpeechToTextProvider stt;
    @Autowired TranslationProvider translation;
    @Autowired TextToSpeechProvider tts;

    @Test
    void validatesStandardEnglishAndTamilWithOwnerSuppliedNonSensitiveClips() throws Exception {
        assertThat(System.getenv("VOICELINK_STANDARD_FREE_TIER_CONFIRMED"))
                .as("Confirm Groq Free and Azure F0 resources before this live test")
                .isEqualTo("true");
        for (String language : List.of("en", "ta")) {
            String path = System.getenv("VOICELINK_LIVE_AUDIO_" + language.toUpperCase());
            assertThat(path).as("Provide a non-sensitive WebM/Opus clip path for " + language).isNotBlank();
            byte[] bytes = Files.readAllBytes(Path.of(path));
            assertThat(bytes.length).isBetween(32, 1024 * 1024);
            String target = "en".equals(language) ? "ta" : "en";
            String transcript = stt.transcribe(new SpeechToTextProvider.Input(bytes, language)).transcript();
            assertThat(transcript).isNotBlank();
            String translated = translation.translate(new TranslationProvider.Input(
                    transcript, language, target, List.of(), Map.of())).translatedText();
            assertThat(translated).isNotBlank();
            var audio = tts.synthesize(new TextToSpeechProvider.Input(translated, target));
            assertThat(audio.audio()).isNotEmpty();
            assertThat(audio.mediaType()).isEqualTo("audio/mpeg");
        }
    }
}
