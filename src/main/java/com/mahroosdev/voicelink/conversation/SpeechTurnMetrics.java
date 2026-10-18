package com.mahroosdev.voicelink.conversation;

import java.util.concurrent.TimeUnit;

import com.mahroosdev.voicelink.ai.AiServiceProfile;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/** Bounded-cardinality timing and count measurements; no user, room, text, or audio tags. */
@Component
public class SpeechTurnMetrics {
    private final MeterRegistry registry;

    public SpeechTurnMetrics(MeterRegistry registry) { this.registry = registry; }

    public void count(AiServiceProfile profile, String event) {
        registry.counter("voicelink.speech.turns", "profile", profile.name(), "event", event).increment();
    }

    public void duration(AiServiceProfile profile, String stage, long startedNanos) {
        Timer.builder("voicelink.speech.duration")
                .tags("profile", profile.name(), "stage", stage)
                .register(registry)
                .record(Math.max(0, System.nanoTime() - startedNanos), TimeUnit.NANOSECONDS);
    }
}
