package com.mahroosdev.voicelink.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.mahroosdev.voicelink.room.RoomClosedEvent;
import org.junit.jupiter.api.Test;

class SharedConversationContextTests {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-19T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final SharedConversationContext context = new SharedConversationContext(clock);

    @Test void onlyNewestThreeEligibleTurnsAreUsedInFinalizationOrder() {
        UUID room = UUID.randomUUID();
        for (int i = 0; i < 5; i++) context.finalized(room, "en", "ta", "turn " + i);
        assertThat(context.snapshot(room)).extracting(turn -> turn.sourceTranscript())
                .containsExactly("turn 2", "turn 3", "turn 4");
    }

    @Test void oversizedAndExpiredTurnsDoNotEnterContext() {
        UUID room = UUID.randomUUID();
        context.finalized(room, "en", "ta", "😀".repeat(401));
        assertThat(context.snapshot(room)).isEmpty();
        context.finalized(room, "en", "ta", "Hello");
        now.set(now.get().plusSeconds(301));
        assertThat(context.snapshot(room)).isEmpty();
    }

    @Test void closureRemovesContextAndPreventsLateCompletionFromRestoringIt() {
        UUID room = UUID.randomUUID();
        context.finalized(room, "en", "ta", "Hello");
        context.onClosed(new RoomClosedEvent(room));
        context.finalized(room, "ta", "en", "Late speech result");
        assertThat(context.snapshot(room)).isEmpty();
    }

    @Test void roomIsolationKeepsOtherConversationUnchanged() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        context.finalized(first, "en", "ta", "First room");
        context.finalized(second, "ta", "en", "Second room");
        assertThat(context.snapshot(first)).extracting(turn -> turn.sourceTranscript()).containsExactly("First room");
        assertThat(context.snapshot(second)).extracting(turn -> turn.sourceTranscript()).containsExactly("Second room");
    }
}
