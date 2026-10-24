package com.mahroosdev.voicelink.ai;

import com.mahroosdev.voicelink.ai.translation.GeminiTranslationProvider;
import com.mahroosdev.voicelink.ai.translation.TranslationProvider;
import com.mahroosdev.voicelink.ai.translation.TranslationReliabilitySettings;
import com.mahroosdev.voicelink.ai.tts.GeminiTextToSpeechProvider;
import com.mahroosdev.voicelink.ai.tts.TextToSpeechProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class AiProviderConfiguration {
    @Bean("premiumTranslationProvider")
    TranslationProvider premiumTranslationProvider(PremiumProviderSettings settings,
            TranslationReliabilitySettings reliability, AiOperationDiagnostics diagnostics,
            ObjectMapper json) {
        return GeminiTranslationProvider.premium(settings, reliability, diagnostics, json);
    }

    @Bean("premiumTextToSpeechProvider")
    TextToSpeechProvider premiumTextToSpeechProvider(PremiumProviderSettings settings, ObjectMapper json) {
        return GeminiTextToSpeechProvider.premium(settings, json);
    }
}
