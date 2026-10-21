package com.mahroosdev.voicelink.ai.translation;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class TranslationReliabilitySettings {
    public static final Duration MAXIMUM_TIMEOUT = Duration.ofSeconds(15);
    private final Duration timeout;

    public TranslationReliabilitySettings(
            @Value("${voicelink.translation.timeout:15s}") Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(MAXIMUM_TIMEOUT) > 0) {
            throw new IllegalArgumentException("Translation timeout must be between 1ms and 15s.");
        }
        this.timeout = timeout;
    }

    public Duration timeout() {
        return timeout;
    }
}
