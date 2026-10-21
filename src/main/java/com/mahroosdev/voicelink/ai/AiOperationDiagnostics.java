package com.mahroosdev.voicelink.ai;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Content-free, bounded-cardinality provider outcome counters. */
@Component
public class AiOperationDiagnostics {
    private final MeterRegistry registry;

    public AiOperationDiagnostics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String operation, AiServiceProfile profile, String provider, String model,
                       String outcome, ProviderFailure.Origin origin, int attempt, long elapsedNanos) {
        if (registry == null) return;
        registry.counter("voicelink.ai.operations",
                "operation", operation,
                "profile", profile.name(),
                "provider", provider,
                "model", model,
                "outcome", outcome,
                "origin", origin.name(),
                "duration", durationBucket(elapsedNanos),
                "attempt", Integer.toString(Math.max(1, Math.min(2, attempt))))
                .increment();
    }

    public static AiOperationDiagnostics noop() {
        return new AiOperationDiagnostics(null);
    }

    private static String durationBucket(long elapsedNanos) {
        long millis = TimeUnit.NANOSECONDS.toMillis(Math.max(0, elapsedNanos));
        if (millis < 1_000) return "LT_1S";
        if (millis < 5_000) return "1_TO_5S";
        if (millis < 10_000) return "5_TO_10S";
        if (millis < 15_000) return "10_TO_15S";
        return "GE_15S";
    }
}
