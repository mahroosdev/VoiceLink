package com.mahroosdev.voicelink.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.mahroosdev.voicelink.ai.AiProviderBundle;
import com.mahroosdev.voicelink.ai.AiProviderResolver;
import com.mahroosdev.voicelink.ai.AiServiceProfile;
import com.mahroosdev.voicelink.ai.ProviderFailure;
import com.mahroosdev.voicelink.ai.translation.TranslationProvider;
import com.mahroosdev.voicelink.glossary.RoomGlossaryService;
import com.mahroosdev.voicelink.room.RoomClosedEvent;
import com.mahroosdev.voicelink.room.RoomLiveAuthorizer;
import com.mahroosdev.voicelink.room.RoomLiveHub;
import com.mahroosdev.voicelink.room.RoomStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class TextTranslationServiceTests {
    private final UUID roomId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID participantId = UUID.randomUUID();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-19T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final RoomLiveAuthorizer authorizer = mock(RoomLiveAuthorizer.class);
    private final RoomGlossaryService glossary = mock(RoomGlossaryService.class);
    private final AiProviderResolver resolver = mock(AiProviderResolver.class);
    private final RoomLiveHub hub = mock(RoomLiveHub.class);
    private final TranslationProvider standard = mock(TranslationProvider.class);
    private final SharedConversationContext context = new SharedConversationContext(clock);
    private TextSharingState sharing;
    private TextTranslationService service;

    @BeforeEach void setup() {
        when(authorizer.authorize(userId, roomId)).thenReturn(
                new RoomLiveAuthorizer.Member(participantId, RoomStatus.ACTIVE, "en", "ta", AiServiceProfile.STANDARD));
        when(glossary.list(userId, roomId)).thenReturn(List.of());
        when(glossary.applicableForTranslation(eq(roomId), eq("en"), eq("ta"), any())).thenReturn(List.of());
        when(resolver.resolve(AiServiceProfile.STANDARD)).thenReturn(new AiProviderBundle(null, standard, null));
        when(hub.hasCurrentConnection(roomId, userId, "session-a")).thenReturn(true);
        when(hub.publishDraft(any())).thenReturn(true);
        when(standard.translate(any())).thenReturn(new TranslationProvider.Result(
                "வணக்கம்", "GEMINI", "gemini-3.1-flash-lite", 1));
        sharing = new TextSharingState(clock);
        service = new TextTranslationService(authorizer, glossary, resolver, hub, context,
                new TextTranslationRateLimiter(8, 40), sharing, clock);
    }

    @Test void privateReviewUsesDirectionGlossaryAndSharedContextWithoutPublishing() {
        var term = new TranslationProvider.GlossaryTerm("en", "ta", "Java", "ஜாவா");
        when(glossary.applicableForTranslation(roomId, "en", "ta", "Hello Java")).thenReturn(List.of(term));
        context.finalized(roomId, "ta", "en", "வணக்கம்");
        var review = service.review(userId, "session-a", roomId, "Hello Java", 3);
        assertThat(review.originalText()).isEqualTo("Hello Java");
        assertThat(review.translatedText()).isEqualTo("வணக்கம்");
        assertThat(review.sourceLanguage()).isEqualTo("en");
        assertThat(review.targetLanguage()).isEqualTo("ta");
        var input = org.mockito.ArgumentCaptor.forClass(TranslationProvider.TranslationRequest.class);
        verify(standard).translate(input.capture());
        assertThat(input.getValue().recentContext()).hasSize(1);
        assertThat(input.getValue().glossary()).containsExactly(term);
        verify(hub, never()).publishTurn(any(), any(), any(), any(), any(), any());
        assertThat(context.snapshot(roomId)).hasSize(1);
    }

    @Test void sendIsOneTimeAndOnlyThenAddsContext() {
        var review = service.review(userId, "session-a", roomId, "Hello", 1);
        assertThat(context.snapshot(roomId)).isEmpty();
        var sent = service.send(userId, "session-a", roomId, review.reviewId(), "Hello", 1);
        assertThat(sent.duplicate()).isFalse();
        assertThat(service.send(userId, "session-a", roomId, review.reviewId(), "Hello", 1).duplicate()).isTrue();
        assertThat(context.snapshot(roomId)).hasSize(1);
        verify(hub).publishTurn(eq(roomId), eq(userId), eq(participantId), eq(sent.finalId()),
                eq("TEXT_TRANSLATION_SENT"), any());
        service.review(userId, "session-a", roomId, "Next message", 2);
        var requests = org.mockito.ArgumentCaptor.forClass(TranslationProvider.TranslationRequest.class);
        verify(standard, org.mockito.Mockito.times(2)).translate(requests.capture());
        assertThat(requests.getAllValues().get(1).recentContext())
                .extracting(turn -> turn.sourceTranscript()).containsExactly("Hello");
    }

    @Test void editedSourceWrongSessionOrRoomCannotSend() {
        var review = service.review(userId, "session-a", roomId, "Hello", 1);
        assertThatThrownBy(() -> service.send(userId, "session-a", roomId, review.reviewId(), "Edited", 2))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.send(userId, "session-b", roomId, review.reviewId(), "Hello", 1))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.send(userId, "session-a", UUID.randomUUID(), review.reviewId(), "Hello", 1))
                .isInstanceOf(ResponseStatusException.class);
        verify(hub, never()).publishTurn(any(), any(), any(), any(), any(), any());
    }

    @Test void anotherRoomMemberCannotFinalizeOwnersPrivatePreview() {
        UUID otherUser = UUID.randomUUID();
        when(authorizer.authorize(otherUser, roomId)).thenReturn(
                new RoomLiveAuthorizer.Member(UUID.randomUUID(), RoomStatus.ACTIVE,
                        "ta", "en", AiServiceProfile.STANDARD));
        var review = service.review(userId, "session-a", roomId, "Hello", 1);
        assertThatThrownBy(() -> service.send(otherUser, "session-b", roomId,
                review.reviewId(), "Hello", 1))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(hub, never()).publishTurn(any(), any(), any(), any(), any(), any());
        assertThat(context.snapshot(roomId)).isEmpty();
    }

    @Test void sharedReviewRequiresExplicitSessionBoundOptInAndPublishesOnlyProviderTranslation() {
        var authorization = service.startSharing(userId, "session-a", roomId);
        var review = service.review(userId, "session-a", roomId, "Raw source", 4,
                authorization.sharingEpoch());
        assertThat(review.translatedText()).isEqualTo("வணக்கம்");
        var update = org.mockito.ArgumentCaptor.forClass(TextSharingState.DraftUpdate.class);
        verify(hub).publishDraft(update.capture());
        assertThat(update.getValue().translatedText()).isEqualTo("வணக்கம்");
        assertThat(update.getValue().sourceLanguage()).isEqualTo("en");
        assertThat(update.getValue().targetLanguage()).isEqualTo("ta");
        assertThat(update.getValue().revision()).isEqualTo(4);
        assertThat(context.snapshot(roomId)).isEmpty();
        verify(hub, never()).publishTurn(any(), any(), any(), any(), any(), any());
    }

    @Test void forgedOrOtherSessionSharingEpochCannotInvokeProvider() {
        var authorization = service.startSharing(userId, "session-a", roomId);
        assertThatThrownBy(() -> service.review(userId, "session-b", roomId,
                "Hello", 1, authorization.sharingEpoch()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.review(userId, "session-a", roomId,
                "Hello", 1, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
        verify(standard, never()).translate(any());
    }

    @Test void privateReviewAfterOptInClearsSharingWithoutPublishingDraft() {
        service.startSharing(userId, "session-a", roomId);
        var review = service.review(userId, "session-a", roomId, "Private again", 2);
        assertThat(review.originalText()).isEqualTo("Private again");
        verify(hub, never()).publishDraft(any());
        assertThat(context.snapshot(roomId)).isEmpty();
    }

    @Test void optOutDuringProviderWorkPreventsLateDraftPublication() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(standard.translate(any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return new TranslationProvider.Result("வணக்கம்", "GEMINI", "gemini-3.1-flash-lite", 1);
        });
        var authorization = service.startSharing(userId, "session-a", roomId);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var pending = workers.submit(() -> service.review(userId, "session-a", roomId,
                    "Still typing", 3, authorization.sharingEpoch()));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            service.stopSharing(userId, "session-a", roomId, authorization.sharingEpoch());
            release.countDown();
            assertThatThrownBy(() -> pending.get(3, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ResponseStatusException.class);
        }
        verify(hub, never()).publishDraft(any());
        verify(hub, never()).publishTurn(any(), any(), any(), any(), any(), any());
    }

    @Test void privateProviderWorkDoesNotBecomeShareableAfterLaterOptIn() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(standard.translate(any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return new TranslationProvider.Result("வணக்கம்", "GEMINI", "gemini-3.1-flash-lite", 1);
        });
        try (var workers = Executors.newSingleThreadExecutor()) {
            var privateWork = workers.submit(() -> service.review(
                    userId, "session-a", roomId, "Private first", 1));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var authorization = service.startSharing(userId, "session-a", roomId);
            release.countDown();
            assertThatThrownBy(() -> privateWork.get(3, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ResponseStatusException.class);
            assertThat(authorization.sharingEpoch()).isNotNull();
        }
        verify(hub, never()).publishDraft(any());
        assertThat(context.snapshot(roomId)).isEmpty();
    }

    @Test void roomClosureDuringSharedProviderWorkPreventsLateDraftPublication() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(standard.translate(any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return new TranslationProvider.Result("வணக்கம்", "GEMINI", "gemini-3.1-flash-lite", 1);
        });
        var authorization = service.startSharing(userId, "session-a", roomId);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var pending = workers.submit(() -> service.review(userId, "session-a", roomId,
                    "Closing room", 1, authorization.sharingEpoch()));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            service.onClosed(new RoomClosedEvent(roomId));
            release.countDown();
            assertThatThrownBy(() -> pending.get(3, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ResponseStatusException.class);
        }
        verify(hub, never()).publishDraft(any());
        verify(hub, never()).publishTurn(any(), any(), any(), any(), any(), any());
    }

    @Test void sharedFinalizationClearsDraftBeforePublishingExactlyOneFinalEvent() {
        var authorization = service.startSharing(userId, "session-a", roomId);
        var review = service.review(userId, "session-a", roomId, "Hello", 1,
                authorization.sharingEpoch());
        var first = service.send(userId, "session-a", roomId, review.reviewId(), "Hello", 1);
        assertThat(first.duplicate()).isFalse();
        assertThat(service.send(userId, "session-a", roomId,
                review.reviewId(), "Hello", 1).duplicate()).isTrue();
        var ordered = inOrder(hub);
        ordered.verify(hub).publishDraft(any());
        ordered.verify(hub).publishDraftClear(any());
        ordered.verify(hub).publishTurn(eq(roomId), eq(userId), eq(participantId),
                eq(first.finalId()), eq("TEXT_TRANSLATION_SENT"), any());
        verify(hub, org.mockito.Mockito.times(1)).publishTurn(any(), any(), any(), any(), any(), any());
        assertThat(context.snapshot(roomId)).hasSize(1);
    }

    @Test void newerReviewInvalidatesOlderReview() {
        var first = service.review(userId, "session-a", roomId, "First", 1);
        var second = service.review(userId, "session-a", roomId, "Second", 2);
        assertThatThrownBy(() -> service.send(userId, "session-a", roomId, first.reviewId(), "First", 1))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(service.send(userId, "session-a", roomId, second.reviewId(), "Second", 2).duplicate()).isFalse();
    }

    @Test void concurrentSendPublishesOnlyOneFinalEvent() throws Exception {
        var review = service.review(userId, "session-a", roomId, "Hello", 1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> service.send(userId, "session-a", roomId,
                    review.reviewId(), "Hello", 1));
            var second = workers.submit(() -> service.send(userId, "session-a", roomId,
                    review.reviewId(), "Hello", 1));
            assertThat(List.of(first.get(3, TimeUnit.SECONDS).duplicate(),
                    second.get(3, TimeUnit.SECONDS).duplicate())).containsExactlyInAnyOrder(false, true);
        }
        verify(hub).publishTurn(eq(roomId), eq(userId), eq(participantId), any(),
                eq("TEXT_TRANSLATION_SENT"), any());
    }

    @Test void lateOlderTranslationCannotReplaceNewerReview() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(standard.translate(any())).thenAnswer(invocation -> {
            String utterance = invocation.getArgument(0, TranslationProvider.TranslationRequest.class)
                    .currentUtterance();
            if ("Old".equals(utterance)) {
                entered.countDown();
                release.await(3, TimeUnit.SECONDS);
            }
            return new TranslationProvider.Result("வணக்கம்", "GEMINI", "gemini-3.1-flash-lite", 1);
        });
        try (var workers = Executors.newSingleThreadExecutor()) {
            var old = workers.submit(() -> service.review(userId, "session-a", roomId, "Old", 1));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var newer = service.review(userId, "session-a", roomId, "New", 2);
            release.countDown();
            assertThat(newer.originalText()).isEqualTo("New");
            assertThatThrownBy(() -> old.get(3, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ResponseStatusException.class);
            assertThat(service.send(userId, "session-a", roomId, newer.reviewId(), "New", 2)
                    .duplicate()).isFalse();
        }
    }

    @Test void reviewExpiresAndRoomCloseClearsState() {
        var review = service.review(userId, "session-a", roomId, "Hello", 1);
        now.set(now.get().plus(Duration.ofSeconds(121)));
        assertThatThrownBy(() -> service.send(userId, "session-a", roomId, review.reviewId(), "Hello", 1))
                .isInstanceOf(ResponseStatusException.class);
        var newer = service.review(userId, "session-a", roomId, "Again", 2);
        service.onClosed(new RoomClosedEvent(roomId));
        assertThatThrownBy(() -> service.send(userId, "session-a", roomId, newer.reviewId(), "Again", 2))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test void invalidInputAndClosedRoomNeverCallProvider() {
        assertThatThrownBy(() -> service.review(userId, "s", roomId, " ", 1))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.review(userId, "s", roomId, "😀".repeat(1001), 1))
                .isInstanceOf(ResponseStatusException.class);
        when(authorizer.authorize(userId, roomId)).thenReturn(
                new RoomLiveAuthorizer.Member(participantId, RoomStatus.CLOSED, "en", "ta", AiServiceProfile.STANDARD));
        assertThatThrownBy(() -> service.review(userId, "s", roomId, "Hello", 1))
                .isInstanceOf(ResponseStatusException.class);
        verify(standard, never()).translate(any());
    }

    @Test void disabledPremiumCannotInvokeProvider() {
        when(authorizer.authorize(userId, roomId)).thenReturn(
                new RoomLiveAuthorizer.Member(participantId, RoomStatus.ACTIVE, "en", "ta", AiServiceProfile.PREMIUM));
        doThrow(new ProviderFailure(ProviderFailure.Code.CONFIGURATION)).when(resolver)
                .requirePaidStage(AiServiceProfile.PREMIUM);
        assertThatThrownBy(() -> service.review(userId, "s", roomId, "Hello", 1))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(standard, never()).translate(any());
    }

    @Test void glossaryChangeInvalidatesPendingReview() {
        var review = service.review(userId, "session-a", roomId, "Hello", 1);
        when(glossary.list(userId, roomId)).thenReturn(List.of(new RoomGlossaryService.EntryView(
                UUID.randomUUID(), "en", "ta", "Java", "ஜாவா", 1)));
        assertThatThrownBy(() -> service.send(userId, "session-a", roomId, review.reviewId(), "Hello", 1))
                .isInstanceOf(ResponseStatusException.class);
        verify(hub, never()).publishTurn(any(), any(), any(), any(), any(), any());
    }

    @Test void providerFailureDoesNotCreateReviewOrContext() {
        when(standard.translate(any())).thenThrow(new ProviderFailure(ProviderFailure.Code.TIMEOUT));
        assertThatThrownBy(() -> service.review(userId, "session-a", roomId, "Hello", 1))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT));
        assertThat(context.snapshot(roomId)).isEmpty();
        verify(hub, never()).publishTurn(any(), any(), any(), any(), any(), any());
    }

    @Test void rateLimitStopsCallsBeforeProvider() {
        TextTranslationService limited = new TextTranslationService(authorizer, glossary, resolver, hub,
                context, new TextTranslationRateLimiter(1, 2), new TextSharingState(clock), clock);
        limited.review(userId, "s", roomId, "First", 1);
        assertThatThrownBy(() -> limited.review(userId, "s", roomId, "Second", 2))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(standard).translate(any());
    }

    @Test void roomReviewStateCapRejectsFurtherRoomsBeforeProviderWork() {
        var member = new RoomLiveAuthorizer.Member(participantId, RoomStatus.ACTIVE,
                "en", "ta", AiServiceProfile.STANDARD);
        when(authorizer.authorize(eq(userId), any(UUID.class))).thenReturn(member);
        when(glossary.list(eq(userId), any(UUID.class))).thenReturn(List.of());
        when(glossary.applicableForTranslation(any(UUID.class), eq("en"), eq("ta"), any()))
                .thenReturn(List.of());
        TextTranslationService bounded = new TextTranslationService(authorizer, glossary, resolver, hub,
                context, new TextTranslationRateLimiter(40, 40), new TextSharingState(clock), clock);
        for (int i = 0; i < 32; i++)
            bounded.review(userId, "session-a", UUID.randomUUID(), "Message", i);
        assertThatThrownBy(() -> bounded.review(userId, "session-a", UUID.randomUUID(), "Message", 33))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(standard, org.mockito.Mockito.times(32)).translate(any());
    }
}
