package com.mahroosdev.voicelink.conversation;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mahroosdev.voicelink.ai.translation.TranslationProvider;
import com.mahroosdev.voicelink.room.RoomClosedEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Temporary, instance-local context shared by completed speech and sent text turns. */
@Component
public class SharedConversationContext {
    private static final int MAX_ROOMS = 64;
    private static final int MAX_TURNS = 16;
    private final Map<UUID, ArrayDeque<RecentContext.Candidate>> rooms = new HashMap<>();
    private final LinkedHashMap<UUID, Instant> recentlyClosed = new LinkedHashMap<>();
    private final Clock clock;
    private long sequence;

    public SharedConversationContext() { this(Clock.systemUTC()); }

    SharedConversationContext(Clock clock) { this.clock = clock; }

    public synchronized List<TranslationProvider.ContextTurn> snapshot(UUID roomId) {
        expire(clock.instant());
        if (recentlyClosed.containsKey(roomId)) return List.of();
        ArrayDeque<RecentContext.Candidate> turns = rooms.get(roomId);
        return turns == null ? List.of() : RecentContext.select(new ArrayList<>(turns),
                Long.MAX_VALUE, clock.instant());
    }

    public synchronized void finalized(UUID roomId, String source, String target, String original) {
        Instant now = clock.instant();
        expire(now);
        if (recentlyClosed.containsKey(roomId)) return;
        // Oversized turns are never sent as context, so there is no reason to retain them.
        if (original == null || original.isBlank() || original.codePointCount(0, original.length()) > 400) return;
        ArrayDeque<RecentContext.Candidate> turns = rooms.get(roomId);
        if (turns == null) {
            if (rooms.size() >= MAX_ROOMS) return;
            turns = new ArrayDeque<>();
            rooms.put(roomId, turns);
        }
        turns.addLast(new RecentContext.Candidate(++sequence, now, source, target, original, true));
        while (turns.size() > MAX_TURNS) turns.removeFirst();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public synchronized void onClosed(RoomClosedEvent event) {
        rooms.remove(event.roomId());
        recentlyClosed.put(event.roomId(), clock.instant());
        while (recentlyClosed.size() > MAX_ROOMS) recentlyClosed.remove(recentlyClosed.keySet().iterator().next());
    }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void prune() { expire(clock.instant()); }

    private void expire(Instant now) {
        rooms.values().forEach(turns -> turns.removeIf(turn -> turn.acceptedAt().isBefore(now.minusSeconds(300))));
        rooms.values().removeIf(ArrayDeque::isEmpty);
        recentlyClosed.values().removeIf(closedAt -> closedAt.isBefore(now.minusSeconds(300)));
    }
}
