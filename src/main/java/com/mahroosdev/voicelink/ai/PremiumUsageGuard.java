package com.mahroosdev.voicelink.ai;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Instance-local paid Gemini request guard. It records only bounded numeric counters and
 * bounded-cardinality tags; user, room, text, glossary, audio, and credentials are never metrics.
 */
@Component
public class PremiumUsageGuard {
    private static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);

    public enum Operation { TRANSLATION, TTS }

    private final Limits translation;
    private final Limits tts;
    private final int maxConcurrent;
    private final long saverOnPreviewNanos;
    private final long saverOffPreviewNanos;
    private final MeterRegistry metrics;
    private final LongSupplier ticker;
    private final ArrayDeque<Entry> accepted = new ArrayDeque<>();
    private final java.util.LinkedHashMap<PreviewKey, Long> lastPreview = new java.util.LinkedHashMap<>();
    private int concurrent;

    @Autowired
    public PremiumUsageGuard(
            @Value("${voicelink.premium.limits.translation-per-user-per-minute:8}") int translationPerUser,
            @Value("${voicelink.premium.limits.translation-per-room-per-minute:16}") int translationPerRoom,
            @Value("${voicelink.premium.limits.translation-per-instance-per-minute:40}") int translationPerInstance,
            @Value("${voicelink.premium.limits.tts-per-user-per-minute:6}") int ttsPerUser,
            @Value("${voicelink.premium.limits.tts-per-room-per-minute:12}") int ttsPerRoom,
            @Value("${voicelink.premium.limits.tts-per-instance-per-minute:24}") int ttsPerInstance,
            @Value("${voicelink.premium.limits.max-concurrent-operations:2}") int maxConcurrent,
            @Value("${voicelink.premium.cost-saver.preview-minimum-interval-on-ms:1500}") long saverOnPreviewMillis,
            @Value("${voicelink.premium.cost-saver.preview-minimum-interval-off-ms:800}") long saverOffPreviewMillis,
            MeterRegistry metrics) {
        this(new Limits(translationPerUser, translationPerRoom, translationPerInstance),
                new Limits(ttsPerUser, ttsPerRoom, ttsPerInstance), maxConcurrent,
                saverOnPreviewMillis, saverOffPreviewMillis, metrics, System::nanoTime);
    }

    PremiumUsageGuard(Limits translation, Limits tts, int maxConcurrent,
                      long saverOnPreviewMillis, long saverOffPreviewMillis,
                      MeterRegistry metrics, LongSupplier ticker) {
        validate(translation);
        validate(tts);
        if (maxConcurrent < 1 || maxConcurrent > 32
                || saverOnPreviewMillis < 800 || saverOnPreviewMillis > 10_000
                || saverOffPreviewMillis < 250 || saverOffPreviewMillis > saverOnPreviewMillis) {
            throw new IllegalArgumentException("Invalid Premium usage limits");
        }
        this.translation = translation;
        this.tts = tts;
        this.maxConcurrent = maxConcurrent;
        this.saverOnPreviewNanos = TimeUnit.MILLISECONDS.toNanos(saverOnPreviewMillis);
        this.saverOffPreviewNanos = TimeUnit.MILLISECONDS.toNanos(saverOffPreviewMillis);
        this.metrics = metrics;
        this.ticker = ticker;
    }

    public Permit acquire(AiServiceProfile profile, UUID userId, UUID roomId,
                          Operation operation, boolean costSaverEnabled, boolean preview) {
        if (profile != AiServiceProfile.PREMIUM) return Permit.noop();
        if (userId == null || roomId == null || operation == null) {
            throw new ProviderFailure(ProviderFailure.Code.CONFIGURATION);
        }
        synchronized (this) {
            long now = ticker.getAsLong();
            prune(now);
            Limits limits = operation == Operation.TRANSLATION ? translation : tts;
            long userCount = accepted.stream().filter(entry -> entry.operation == operation
                    && entry.userId.equals(userId)).count();
            long roomCount = accepted.stream().filter(entry -> entry.operation == operation
                    && entry.roomId.equals(roomId)).count();
            long instanceCount = accepted.stream().filter(entry -> entry.operation == operation).count();
            if (userCount >= limits.perUser || roomCount >= limits.perRoom
                    || instanceCount >= limits.perInstance || concurrent >= maxConcurrent) {
                count(operation, "rejected", costSaverEnabled);
                throw new ProviderFailure(ProviderFailure.Code.RATE_LIMIT);
            }
            if (preview && operation == Operation.TRANSLATION) {
                PreviewKey key = new PreviewKey(userId, roomId);
                Long previous = lastPreview.get(key);
                long minimum = costSaverEnabled ? saverOnPreviewNanos : saverOffPreviewNanos;
                if (previous != null && now - previous < minimum) {
                    count(operation, "preview_throttled", costSaverEnabled);
                    throw new ProviderFailure(ProviderFailure.Code.RATE_LIMIT);
                }
                lastPreview.put(key, now);
                trimPreviewKeys();
            }
            accepted.addLast(new Entry(userId, roomId, operation, now));
            concurrent++;
            count(operation, "accepted", costSaverEnabled);
            return new Permit(this, operation, costSaverEnabled);
        }
    }

    public void recordTranslationUsage(AiServiceProfile profile, boolean costSaverEnabled, long inputTokens,
                                       long outputTokens, long thinkingTokens) {
        if (profile != AiServiceProfile.PREMIUM) return;
        recordTokens("input", inputTokens, costSaverEnabled);
        recordTokens("output", outputTokens, costSaverEnabled);
        recordTokens("thinking", thinkingTokens, costSaverEnabled);
    }

    private void recordTokens(String kind, long tokens, boolean costSaverEnabled) {
        if (tokens < 0) return;
        metrics.counter("voicelink.premium.provider.tokens", "kind", kind,
                "cost_saver", state(costSaverEnabled)).increment(tokens);
    }

    private synchronized void release(Operation operation, boolean costSaverEnabled, boolean success) {
        concurrent--;
        count(operation, success ? "completed" : "failed", costSaverEnabled);
    }

    private void prune(long now) {
        while (!accepted.isEmpty() && now - accepted.peekFirst().atNanos >= WINDOW_NANOS) {
            accepted.removeFirst();
        }
        lastPreview.entrySet().removeIf(entry -> now - entry.getValue() >= WINDOW_NANOS);
    }

    private void trimPreviewKeys() {
        int maximum = translation.perInstance * 2;
        while (lastPreview.size() > maximum) {
            var iterator = lastPreview.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private void count(Operation operation, String outcome, boolean costSaverEnabled) {
        metrics.counter("voicelink.premium.provider.requests", "operation",
                operation.name().toLowerCase(java.util.Locale.ROOT), "outcome", outcome,
                "cost_saver", state(costSaverEnabled)).increment();
    }

    private static String state(boolean enabled) { return enabled ? "on" : "off"; }

    private static void validate(Limits limits) {
        if (limits.perUser < 1 || limits.perRoom < limits.perUser
                || limits.perInstance < limits.perRoom || limits.perInstance > 10_000) {
            throw new IllegalArgumentException("Invalid Premium usage limits");
        }
    }

    public static PremiumUsageGuard unlimitedForTests() {
        return new PremiumUsageGuard(new Limits(10_000, 10_000, 10_000),
                new Limits(10_000, 10_000, 10_000), 32, 800, 250,
                new SimpleMeterRegistry(), System::nanoTime);
    }

    public record Limits(int perUser, int perRoom, int perInstance) {}
    private record Entry(UUID userId, UUID roomId, Operation operation, long atNanos) {}
    private record PreviewKey(UUID userId, UUID roomId) {}

    public static final class Permit implements AutoCloseable {
        private final PremiumUsageGuard owner;
        private final Operation operation;
        private final boolean costSaverEnabled;
        private boolean success;
        private boolean closed;

        private Permit(PremiumUsageGuard owner, Operation operation, boolean costSaverEnabled) {
            this.owner = owner;
            this.operation = operation;
            this.costSaverEnabled = costSaverEnabled;
        }

        private static Permit noop() { return new Permit(null, null, true); }
        public void success() { success = true; }

        @Override public void close() {
            if (owner != null && !closed) {
                closed = true;
                owner.release(operation, costSaverEnabled, success);
            }
        }
    }
}
