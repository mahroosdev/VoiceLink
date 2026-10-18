package com.mahroosdev.voicelink.conversation;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Instance-local sliding window for accepted speech turns; its memory is bounded by the global limit. */
@Component
public class SpeechTurnRateLimiter {
    private static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final int perUserLimit;
    private final int globalLimit;
    private final LongSupplier ticker;
    private final ArrayDeque<Entry> accepted = new ArrayDeque<>();
    private final Map<UUID, Integer> perUser = new HashMap<>();

    @Autowired
    public SpeechTurnRateLimiter(
            @Value("${voicelink.speech.max-turns-per-user-per-minute:12}") int perUserLimit,
            @Value("${voicelink.speech.max-turns-per-instance-per-minute:60}") int globalLimit) {
        this(perUserLimit, globalLimit, System::nanoTime);
    }

    SpeechTurnRateLimiter(int perUserLimit, int globalLimit, LongSupplier ticker) {
        if (perUserLimit < 1 || globalLimit < perUserLimit || globalLimit > 1000) {
            throw new IllegalArgumentException("Invalid speech rate limits");
        }
        this.perUserLimit = perUserLimit;
        this.globalLimit = globalLimit;
        this.ticker = ticker;
    }

    public synchronized boolean tryAcquire(UUID userId) {
        long now = ticker.getAsLong();
        while (!accepted.isEmpty() && now - accepted.peekFirst().atNanos >= WINDOW_NANOS) {
            Entry expired = accepted.removeFirst();
            perUser.computeIfPresent(expired.userId, (id, count) -> count == 1 ? null : count - 1);
        }
        if (accepted.size() >= globalLimit || perUser.getOrDefault(userId, 0) >= perUserLimit) return false;
        accepted.addLast(new Entry(userId, now));
        perUser.merge(userId, 1, Integer::sum);
        return true;
    }

    synchronized int trackedUsers() { return perUser.size(); }

    private record Entry(UUID userId, long atNanos) {}
}
