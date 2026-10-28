package com.mahroosdev.voicelink.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TextSharingStateTests {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-19T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final UUID room = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID participant = UUID.randomUUID();

    @Test void sharingIsDisabledUntilExplicitOptIn() {
        TextSharingState state = new TextSharingState(clock);
        assertThatThrownBy(() -> state.begin(room, user, participant, "session-a",
                UUID.randomUUID(), 1, ignored -> {}))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(state.size()).isZero();
    }

    @Test void newerRevisionRejectsLatePublicationAndReplacesOneCurrentDraft() {
        TextSharingState state = new TextSharingState(clock);
        var view = state.start(room, user, participant, "session-a", ignored -> {});
        var old = state.begin(room, user, participant, "session-a", view.sharingEpoch(), 1, ignored -> {});
        var latest = state.begin(room, user, participant, "session-a", view.sharingEpoch(), 2, ignored -> {});
        var updates = new ArrayList<TextSharingState.DraftUpdate>();
        assertThat(state.publish(old, "old", "en", "ta", update -> {
            updates.add(update); return true;
        })).isFalse();
        assertThat(state.publish(latest, "new", "en", "ta", update -> {
            updates.add(update); return true;
        })).isTrue();
        assertThat(updates).singleElement().extracting(TextSharingState.DraftUpdate::translatedText)
                .isEqualTo("new");
    }

    @Test void anotherSessionSupersedesAndClearsThePreviousShare() {
        TextSharingState state = new TextSharingState(clock);
        var first = state.start(room, user, participant, "session-a", ignored -> {});
        var lease = state.begin(room, user, participant, "session-a", first.sharingEpoch(), 1, ignored -> {});
        state.publish(lease, "draft", "en", "ta", ignored -> true);
        var clears = new ArrayList<TextSharingState.DraftClear>();
        var second = state.start(room, user, participant, "session-b", clears::add);
        assertThat(clears).singleElement().extracting(TextSharingState.DraftClear::reason)
                .isEqualTo("REPLACED");
        assertThat(state.current(room, user, participant, "session-a", first.sharingEpoch(), 1)).isFalse();
        assertThat(state.begin(room, user, participant, "session-b",
                second.sharingEpoch(), 2, clears::add)).isNotNull();
    }

    @Test void inputClearAndOptOutEmitAtMostOneClearAndInvalidateTheEpoch() {
        TextSharingState state = new TextSharingState(clock);
        var view = state.start(room, user, participant, "session-a", ignored -> {});
        var lease = state.begin(room, user, participant, "session-a", view.sharingEpoch(), 1, ignored -> {});
        state.publish(lease, "draft", "en", "ta", ignored -> true);
        var clears = new ArrayList<TextSharingState.DraftClear>();
        state.clearDraft(room, user, participant, "session-a", view.sharingEpoch(), 2, clears::add);
        state.stop(room, user, participant, "session-a", view.sharingEpoch(), clears::add);
        assertThat(clears).hasSize(1);
        assertThat(clears.getFirst().reason()).isEqualTo("INPUT_CLEARED");
        assertThat(state.size()).isZero();
    }

    @Test void contentFreeInvalidationRejectsAProviderResultAlreadyInFlight() {
        TextSharingState state = new TextSharingState(clock);
        var view = state.start(room, user, participant, "session-a", ignored -> {});
        var stale = state.begin(room, user, participant, "session-a",
                view.sharingEpoch(), 1, ignored -> {});
        state.invalidate(room, user, participant, "session-a",
                view.sharingEpoch(), 2, ignored -> {});
        assertThat(state.publish(stale, "late", "en", "ta", ignored -> true)).isFalse();
        assertThat(state.current(room, user, participant, "session-a",
                view.sharingEpoch(), 2)).isTrue();
    }

    @Test void invalidationClearsPublishedDraftAndFencesOlderLeaseAndLowerInvalidation() {
        TextSharingState state = new TextSharingState(clock);
        var view = state.start(room, user, participant, "session-a", ignored -> {});
        var old = state.begin(room, user, participant, "session-a", view.sharingEpoch(), 1, ignored -> {});
        var updates = new ArrayList<TextSharingState.DraftUpdate>();
        var clears = new ArrayList<TextSharingState.DraftClear>();
        assertThat(state.publish(old, "obsolete", "en", "ta", update -> {
            updates.add(update); return true;
        })).isTrue();
        assertThat(state.invalidate(room, user, participant, "session-a", view.sharingEpoch(), 2, clears::add)).isTrue();
        assertThat(clears).singleElement().extracting(TextSharingState.DraftClear::reason)
                .isEqualTo("REVISION_UPDATED");
        assertThat(state.publish(old, "late", "en", "ta", update -> {
            updates.add(update); return true;
        })).isFalse();
        assertThat(state.invalidate(room, user, participant, "session-a", view.sharingEpoch(), 1, clears::add)).isFalse();
        var next = state.begin(room, user, participant, "session-a", view.sharingEpoch(), 3, clears::add);
        assertThat(state.publish(next, "current", "en", "ta", update -> {
            updates.add(update); return true;
        })).isTrue();
        assertThat(updates).extracting(TextSharingState.DraftUpdate::translatedText)
                .containsExactly("obsolete", "current");
        assertThat(clears).hasSize(1);
    }

    @Test void invalidEpochAndSessionCannotInvalidateAnotherShare() {
        TextSharingState state = new TextSharingState(clock);
        var view = state.start(room, user, participant, "session-a", ignored -> {});
        var lease = state.begin(room, user, participant, "session-a", view.sharingEpoch(), 1, ignored -> {});
        var updates = new ArrayList<TextSharingState.DraftUpdate>();
        assertThatThrownBy(() -> state.invalidate(room, user, participant, "session-b",
                view.sharingEpoch(), 2, ignored -> {})).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> state.invalidate(room, user, participant, "session-a",
                UUID.randomUUID(), 2, ignored -> {})).isInstanceOf(ResponseStatusException.class);
        assertThat(state.publish(lease, "still current", "en", "ta", update -> {
            updates.add(update); return true;
        })).isTrue();
        assertThat(updates).singleElement().extracting(TextSharingState.DraftUpdate::translatedText)
                .isEqualTo("still current");
    }

    @Test void disconnectAndExpiryFailClosedAndClearPublishedDrafts() {
        TextSharingState state = new TextSharingState(clock);
        var view = state.start(room, user, participant, "session-a", ignored -> {});
        var lease = state.begin(room, user, participant, "session-a", view.sharingEpoch(), 1, ignored -> {});
        state.publish(lease, "draft", "en", "ta", ignored -> true);
        var clears = new ArrayList<TextSharingState.DraftClear>();
        state.disconnect(room, user, "session-a", clears::add);
        assertThat(clears).singleElement().extracting(TextSharingState.DraftClear::reason)
                .isEqualTo("DISCONNECTED");

        var next = state.start(room, user, participant, "session-a", clears::add);
        var nextLease = state.begin(room, user, participant, "session-a", next.sharingEpoch(), 2, clears::add);
        state.publish(nextLease, "again", "en", "ta", ignored -> true);
        now.set(now.get().plus(Duration.ofSeconds(121)));
        state.prune(clears::add);
        assertThat(clears.getLast().reason()).isEqualTo("EXPIRED");
        assertThat(state.size()).isZero();
    }

    @Test void activeSharingStorageIsBounded() {
        TextSharingState state = new TextSharingState(clock);
        for (int i = 0; i < TextSharingState.MAX_ACTIVE_SHARES; i++)
            state.start(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "session-" + i, ignored -> {});
        assertThatThrownBy(() -> state.start(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "overflow", ignored -> {}))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(state.size()).isEqualTo(TextSharingState.MAX_ACTIVE_SHARES);
    }
}
