package com.mahroosdev.voicelink.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PremiumProviderSettings {
    private final boolean enabled;
    private final String geminiKey;

    public PremiumProviderSettings(@Value("${voicelink.premium.enabled:false}") String enabled,
                                   @Value("${voicelink.premium.gemini-key:}") String geminiKey) {
        this.enabled = "true".equals(enabled);
        this.geminiKey = geminiKey;
    }

    public boolean available() { return enabled && geminiKey != null && !geminiKey.isBlank(); }

    public String geminiKey() {
        if (!available()) throw new ProviderFailure(ProviderFailure.Code.CONFIGURATION);
        return geminiKey.strip();
    }

    public void requireAvailable() {
        if (!available()) throw new ProviderFailure(ProviderFailure.Code.CONFIGURATION);
    }
}
