package com.mahroosdev.voicelink.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class RecentContextTests {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    private RecentContext.Candidate prior(long index, long ageSeconds, String text, boolean translated) {
        return new RecentContext.Candidate(index, NOW.minusSeconds(ageSeconds),
                index % 2 == 0 ? "ta" : "en", index % 2 == 0 ? "en" : "ta", text, translated);
    }

    @Test void zeroOneAndNewestThreeAppearOldestToNewest() {
        assertThat(RecentContext.select(List.of(), 1, NOW)).isEmpty();
        assertThat(RecentContext.select(List.of(prior(1, 1, "one", true)), 2, NOW))
                .extracting(turn -> turn.turnIndex()).containsExactly(1L);
        var context = RecentContext.select(List.of(prior(4, 1, "four", true), prior(1, 4, "one", true),
                prior(3, 2, "three", true), prior(2, 3, "two", true)), 5, NOW);
        assertThat(context).extracting(turn -> turn.turnIndex()).containsExactly(2L, 3L, 4L);
        assertThat(context.get(0).sourceTranscript()).isEqualTo("two");
    }

    @Test void excludesAgeOversizeUntranslatedAndCurrentTurn() {
        var context = RecentContext.select(List.of(prior(1, 301, "old", true),
                prior(2, 300, "edge", true), prior(3, 10, "x".repeat(401), true),
                prior(4, 5, "failed", false), prior(5, 0, "current", true)), 5, NOW);
        assertThat(context).extracting(turn -> turn.turnIndex()).containsExactly(2L);
    }

    @Test void countsUnicodeCodePointsAndNeverCutsAnUtterance() {
        String emoji400 = "😀".repeat(400);
        var context = RecentContext.select(List.of(prior(1, 2, emoji400, true),
                prior(2, 1, "a".repeat(400), true), prior(3, 0, "b".repeat(400), true)), 4, NOW);
        assertThat(context).hasSize(3);
        assertThat(context.stream().mapToInt(turn ->
                turn.sourceTranscript().codePointCount(0, turn.sourceTranscript().length())).sum()).isEqualTo(1200);
        assertThat(context.getFirst().sourceTranscript()).isEqualTo(emoji400);
        assertThat(RecentContext.select(List.of(prior(1, 0, emoji400 + "😀", true)), 2, NOW)).isEmpty();
    }

    @Test void snapshotUsesOnlyProvidedRoomStateAndImmutableCopy() {
        var roomA = IntStream.rangeClosed(1, 2).mapToObj(i -> prior(i, 0, "room A " + i, true)).toList();
        var roomB = List.of(prior(1, 0, "room B", true));
        assertThat(RecentContext.select(roomA, 3, NOW)).allSatisfy(turn ->
                assertThat(turn.sourceTranscript()).startsWith("room A"));
        assertThat(RecentContext.select(roomB, 2, NOW)).extracting(turn -> turn.sourceTranscript())
                .containsExactly("room B");
    }
}
