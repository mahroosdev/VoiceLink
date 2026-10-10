package com.mahroosdev.voicelink;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.mahroosdev.voicelink.ai.ProviderFailure;
import com.mahroosdev.voicelink.ai.stt.SpeechToTextProvider;
import com.mahroosdev.voicelink.ai.StandardProfile;
import com.mahroosdev.voicelink.ai.translation.TranslationProvider;
import com.mahroosdev.voicelink.ai.tts.TextToSpeechProvider;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Runs only with the explicitly selected Maven provider-live profile. */
@SpringBootTest(properties = "voicelink.standard.free-tier-confirmed=${VOICELINK_STANDARD_FREE_TIER_CONFIRMED:false}")
@ActiveProfiles("test")
class ProviderLiveIT {
    private static final Logger log = LoggerFactory.getLogger(ProviderLiveIT.class);
    @Autowired SpeechToTextProvider stt;
    @Autowired TranslationProvider translation;
    @Autowired TextToSpeechProvider tts;

    @Test
    void validatesStandardEnglishAndTamilWithOwnerSuppliedNonSensitiveClips() throws Exception {
        assertThat(System.getenv("VOICELINK_STANDARD_FREE_TIER_CONFIRMED"))
                .as("Confirm Groq Free and Gemini API Free Tier before this live test")
                .isEqualTo("true");
        assertThat(System.getenv("VOICELINK_GROQ_API_KEY")).as("Groq Free key required").isNotBlank();
        assertThat(System.getenv("VOICELINK_GEMINI_API_KEY")).as("Gemini API Free Tier key required").isNotBlank();
        for (String language : List.of("en", "ta")) {
            String path = System.getenv("VOICELINK_LIVE_AUDIO_" + language.toUpperCase());
            assertThat(path).as("Provide a non-sensitive WebM/Opus clip path for " + language).isNotBlank();
            byte[] bytes = Files.readAllBytes(Path.of(path));
            assertThat(bytes.length).isBetween(32, 1024 * 1024);
            String target = "en".equals(language) ? "ta" : "en";
            var recognized = callStage("STT", StandardProfile.STT_PROVIDER, StandardProfile.STT_MODEL,
                    () -> stt.transcribe(new SpeechToTextProvider.Input(bytes, language)));
            assertThat(recognized.transcript()).isNotBlank();
            assertThat(recognized.providerId()).isEqualTo(StandardProfile.STT_PROVIDER);
            assertThat(recognized.modelId()).isEqualTo(StandardProfile.STT_MODEL);
            var translated = callStage("TRANSLATION", StandardProfile.TRANSLATION_PROVIDER,
                    StandardProfile.TRANSLATION_SERVICE, () -> translation.translate(new TranslationProvider.Input(
                            recognized.transcript(), language, target, List.of(), Map.of())));
            assertThat(translated.translatedText()).isNotBlank();
            assertThat(translated.providerId()).isEqualTo(StandardProfile.TRANSLATION_PROVIDER);
            assertThat(translated.serviceId()).isEqualTo(StandardProfile.TRANSLATION_SERVICE);
            var audio = callStage("TTS", StandardProfile.TTS_PROVIDER, StandardProfile.TTS_SERVICE,
                    () -> tts.synthesize(new TextToSpeechProvider.Input(translated.translatedText(), target)));
            assertThat(audio.audio()).isNotEmpty();
            assertThat(audio.mediaType()).isEqualTo("audio/wav");
            assertThat(audio.providerId()).isEqualTo(StandardProfile.TTS_PROVIDER);
            log.info("{} to {}: STT {}/{} {} ms, translation {}/{} {} ms, TTS {}/{} {} ms: success",
                    language, target, recognized.providerId(), recognized.modelId(), recognized.elapsedMillis(),
                    translated.providerId(), translated.serviceId(), translated.elapsedMillis(),
                    audio.providerId(), StandardProfile.TTS_SERVICE, audio.elapsedMillis());
        }
    }

    private static <T> T callStage(String stage, String provider, String model, Supplier<T> call) {
        long start = System.nanoTime();
        try {
            return call.get();
        } catch (ProviderFailure ex) {
            log.warn("{} {}/{} failed after {} ms: {}", stage, provider, model,
                    Duration.ofNanos(System.nanoTime() - start).toMillis(), ex.code());
            throw ex;
        }
    }
}
