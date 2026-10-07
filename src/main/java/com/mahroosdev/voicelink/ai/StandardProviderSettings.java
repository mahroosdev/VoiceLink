package com.mahroosdev.voicelink.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StandardProviderSettings {
    private final boolean freeTierConfirmed;
    private final String groqKey;
    private final String translatorKey;
    private final String translatorRegion;
    private final String translatorEndpoint;
    private final String speechKey;
    private final String speechRegion;

    public StandardProviderSettings(
            @Value("${voicelink.standard.free-tier-confirmed:false}") boolean freeTierConfirmed,
            @Value("${voicelink.standard.groq-key:}") String groqKey,
            @Value("${voicelink.standard.translator-key:}") String translatorKey,
            @Value("${voicelink.standard.translator-region:}") String translatorRegion,
            @Value("${voicelink.standard.translator-endpoint:https://api.cognitive.microsofttranslator.com}") String translatorEndpoint,
            @Value("${voicelink.standard.speech-key:}") String speechKey,
            @Value("${voicelink.standard.speech-region:}") String speechRegion) {
        this.freeTierConfirmed = freeTierConfirmed;
        this.groqKey = groqKey;
        this.translatorKey = translatorKey;
        this.translatorRegion = translatorRegion;
        this.translatorEndpoint = translatorEndpoint;
        this.speechKey = speechKey;
        this.speechRegion = speechRegion;
    }

    public String groqKey() { requireFreeTier(); return required(groqKey); }
    public String translatorKey() { requireFreeTier(); return required(translatorKey); }
    public String translatorRegion() { return required(translatorRegion); }
    public String translatorEndpoint() { return required(translatorEndpoint); }
    public String speechKey() { requireFreeTier(); return required(speechKey); }
    public String speechRegion() { return required(speechRegion); }

    private void requireFreeTier() {
        if (!freeTierConfirmed) throw new ProviderFailure(ProviderFailure.Code.CONFIGURATION);
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new ProviderFailure(ProviderFailure.Code.CONFIGURATION);
        return value.strip();
    }
}
