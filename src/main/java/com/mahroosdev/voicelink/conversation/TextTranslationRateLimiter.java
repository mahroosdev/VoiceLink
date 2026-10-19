package com.mahroosdev.voicelink.conversation;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Instance-local, bounded rolling minute limit for provider-backed text translation. */
@Component
public class TextTranslationRateLimiter {
    private final int perUser;
    private final int perInstance;
    private final ArrayDeque<Entry> accepted = new ArrayDeque<>();
    private final Map<UUID, Integer> counts = new HashMap<>();

    public TextTranslationRateLimiter(
            @Value("${voicelink.text.max-reviews-per-user-per-minute:8}") int perUser,
            @Value("${voicelink.text.max-reviews-per-instance-per-minute:40}") int perInstance) {
        if (perUser < 1 || perInstance < perUser || perInstance > 1000)
            throw new IllegalArgumentException("Invalid text translation limits");
        this.perUser = perUser;
        this.perInstance = perInstance;
    }

    public synchronized boolean tryAcquire(UUID userId) {
        long now = System.nanoTime();
        while (!accepted.isEmpty() && now - accepted.peekFirst().time >= TimeUnit.MINUTES.toNanos(1)) {
            Entry old = accepted.removeFirst();
            counts.computeIfPresent(old.userId, (id, count) -> count == 1 ? null : count - 1);
        }
        if (accepted.size() >= perInstance || counts.getOrDefault(userId, 0) >= perUser) return false;
        accepted.addLast(new Entry(userId, now));
        counts.merge(userId, 1, Integer::sum);
        return true;
    }

    private record Entry(UUID userId, long time) {}
}
