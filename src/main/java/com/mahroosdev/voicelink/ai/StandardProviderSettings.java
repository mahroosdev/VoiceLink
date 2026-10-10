package com.mahroosdev.voicelink.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StandardProviderSettings {
    private final boolean freeTierConfirmed;
    private final String groqKey;
    private final String geminiKey;

    public StandardProviderSettings(
            @Value("${voicelink.standard.free-tier-confirmed:false}") boolean freeTierConfirmed,
            @Value("${voicelink.standard.groq-key:}") String groqKey,
            @Value("${voicelink.standard.gemini-key:}") String geminiKey) {
        this.freeTierConfirmed = freeTierConfirmed;
        this.groqKey = groqKey;
        this.geminiKey = geminiKey;
    }

    public String groqKey() { requireFreeTier(); return required(groqKey); }
    public String geminiKey() { requireFreeTier(); return required(geminiKey); }

    private void requireFreeTier() {
        if (!freeTierConfirmed) throw new ProviderFailure(ProviderFailure.Code.CONFIGURATION);
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new ProviderFailure(ProviderFailure.Code.CONFIGURATION);
        return value.strip();
    }
}
