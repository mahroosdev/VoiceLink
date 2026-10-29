package com.mahroosdev.voicelink.conversation;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Instance-local, bounded rolling minute limits for text translation and live invalidation. */
@Component
public class TextTranslationRateLimiter {
    private final int perUser;
    private final int perInstance;
    private final int invalidationsPerUser;
    private final int invalidationsPerInstance;
    private final ArrayDeque<Entry> accepted = new ArrayDeque<>();
    private final Map<UUID, Integer> counts = new HashMap<>();
    private final ArrayDeque<Entry> acceptedInvalidations = new ArrayDeque<>();
    private final Map<UUID, Integer> invalidationCounts = new HashMap<>();

    @Autowired
    public TextTranslationRateLimiter(
            @Value("${voicelink.text.max-reviews-per-user-per-minute:8}") int perUser,
            @Value("${voicelink.text.max-reviews-per-instance-per-minute:40}") int perInstance,
            @Value("${voicelink.text.max-invalidations-per-user-per-minute:300}") int invalidationsPerUser,
            @Value("${voicelink.text.max-invalidations-per-instance-per-minute:1200}") int invalidationsPerInstance) {
        if (perUser < 1 || perInstance < perUser || perInstance > 1000)
            throw new IllegalArgumentException("Invalid text translation limits");
        if (invalidationsPerUser < 1 || invalidationsPerInstance < invalidationsPerUser
                || invalidationsPerInstance > 10_000)
            throw new IllegalArgumentException("Invalid live invalidation limits");
        this.perUser = perUser;
        this.perInstance = perInstance;
        this.invalidationsPerUser = invalidationsPerUser;
        this.invalidationsPerInstance = invalidationsPerInstance;
    }

    TextTranslationRateLimiter(int perUser, int perInstance) {
        this(perUser, perInstance, 300, 1200);
    }

    public synchronized boolean tryAcquire(UUID userId) {
        prune(accepted, counts);
        if (accepted.size() >= perInstance || counts.getOrDefault(userId, 0) >= perUser) return false;
        accept(accepted, counts, userId);
        return true;
    }

    public synchronized boolean tryAcquireInvalidation(UUID userId) {
        prune(acceptedInvalidations, invalidationCounts);
        if (acceptedInvalidations.size() >= invalidationsPerInstance
                || invalidationCounts.getOrDefault(userId, 0) >= invalidationsPerUser) return false;
        accept(acceptedInvalidations, invalidationCounts, userId);
        return true;
    }

    private static void prune(ArrayDeque<Entry> entries, Map<UUID, Integer> userCounts) {
        long now = System.nanoTime();
        while (!entries.isEmpty() && now - entries.peekFirst().time >= TimeUnit.MINUTES.toNanos(1)) {
            Entry old = entries.removeFirst();
            userCounts.computeIfPresent(old.userId, (id, count) -> count == 1 ? null : count - 1);
        }
    }

    private static void accept(ArrayDeque<Entry> entries, Map<UUID, Integer> userCounts, UUID userId) {
        entries.addLast(new Entry(userId, System.nanoTime()));
        userCounts.merge(userId, 1, Integer::sum);
    }

    private record Entry(UUID userId, long time) {}
}
