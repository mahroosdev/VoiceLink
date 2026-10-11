package com.mahroosdev.voicelink.ai;

import com.mahroosdev.voicelink.ai.stt.SpeechToTextProvider;
import com.mahroosdev.voicelink.ai.translation.TranslationProvider;
import com.mahroosdev.voicelink.ai.tts.TextToSpeechProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class AiProviderResolver {
    private final AiProviderBundle standard;
    private final AiProviderBundle premium;
    private final PremiumProviderSettings premiumSettings;

    public AiProviderResolver(SpeechToTextProvider stt,
            @Qualifier("geminiTranslationProvider") TranslationProvider standardTranslation,
            @Qualifier("geminiTextToSpeechProvider") TextToSpeechProvider standardTts,
            @Qualifier("premiumTranslationProvider") TranslationProvider premiumTranslation,
            @Qualifier("premiumTextToSpeechProvider") TextToSpeechProvider premiumTts,
            PremiumProviderSettings premiumSettings) {
        this.standard = new AiProviderBundle(stt, standardTranslation, standardTts);
        this.premium = new AiProviderBundle(stt, premiumTranslation, premiumTts);
        this.premiumSettings = premiumSettings;
    }

    public AiProviderBundle resolve(AiServiceProfile profile) {
        return switch (profile) {
            case STANDARD -> standard;
            case PREMIUM -> premium;
        };
    }

    public void requirePaidStage(AiServiceProfile profile) {
        if (profile == AiServiceProfile.PREMIUM) premiumSettings.requireAvailable();
    }
}
