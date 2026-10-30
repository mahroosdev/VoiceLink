package com.mahroosdev.voicelink.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private final TranslationProvider premium = mock(TranslationProvider.class);
    private final SharedConversationContext context = new SharedConversationContext(clock);
    private TextSharingState sharing;
    private TextTranslationService service;

    @BeforeEach void setup() {
        when(authorizer.authorize(userId, roomId)).thenReturn(
                new RoomLiveAuthorizer.Member(participantId, RoomStatus.ACTIVE, "en", "ta", AiServiceProfile.STANDARD));
        doAnswer(invocation -> {
            UUID user = invocation.getArgument(0);
            UUID room = invocation.getArgument(1);
            UUID participant = invocation.getArgument(2);
            long version = invocation.getArgument(3);
            RoomLiveAuthorizer.Member current = authorizer.authorize(user, room);
            if (current.status() != RoomStatus.ACTIVE || !current.participantId().equals(participant)
                    || current.languageVersion() != version) return false;
            invocation.<Runnable>getArgument(4).run();
            return true;
        }).when(authorizer).runIfActiveLanguageVersion(any(), any(), any(), anyLong(), any(Runnable.class));
        when(glossary.list(userId, roomId)).thenReturn(List.of());
        when(glossary.applicableForTranslation(eq(roomId), eq("en"), eq("ta"), any())).thenReturn(List.of());
        when(resolver.resolve(AiServiceProfile.STANDARD)).thenReturn(new AiProviderBundle(null, standard, null));
        when(resolver.resolve(AiServiceProfile.PREMIUM)).thenReturn(new AiProviderBundle(null, premium, null));
        when(hub.hasCurrentConnection(roomId, userId, "session-a")).thenReturn(true);
        when(hub.publishDraft(any())).thenReturn(true);
        when(standard.translate(any())).thenReturn(new TranslationProvider.Result(
                "வணக்கம்", "GEMINI", "gemini-3.1-flash-lite", 1));
        when(premium.translate(any())).thenReturn(new TranslationProvider.Result(
                "வணக்கம்", "GEMINI", "gemini-3.8-flash", 1));
        sharing = new TextSharingState(clock);
        service = new TextTranslationService(authorizer, glossary, resolver, hub, context,
                new TextTranslationRateLimiter(8, 40), sharing, clock);
    }

    @Test void premiumPrivateTotalReviewRoutesOnlyToPremiumAndDuplicateReviewIsReused() {
        when(authorizer.authorize(userId, roomId)).thenReturn(
                new RoomLiveAuthorizer.Member(participantId, RoomStatus.ACTIVE,
                        "en", "ta", AiServiceProfile.PREMIUM, true));
        var first = service.review(userId, "session-a", roomId, "Hello", 1);
        var duplicate = service.review(userId, "session-a", roomId, "Hello", 1);
        assertThat(duplicate.reviewId()).isEqualTo(first.reviewId());
        assertThat(service.send(userId, "session-a", roomId, first.reviewId(), "Hello", 1).profile())
                .isEqualTo("PREMIUM");
        verify(premium).translate(any());
        verify(standard, never()).translate(any());
    }

    @Test void completedReviewIsNotReusedAfterGlossaryChanges() {
        var changed = new RoomGlossaryService.EntryView(UUID.randomUUID(), "en", "ta",
                "API", "ஏபிஐ", 0);
        when(glossary.list(userId, roomId)).thenReturn(List.of(), List.of(), List.of(changed), List.of(changed));
        var first = service.review(userId, "session-a", roomId, "Hello", 1);
        var refreshed = service.review(userId, "session-a", roomId, "Hello", 1);
        assertThat(refreshed.reviewId()).isNotEqualTo(first.reviewId());
        verify(standard, times(2)).translate(any());
    }

    @Test void invalidLiveSharingEpochIsRejectedAndCannotReachAProvider() {
        assertThatThrownBy(() -> service.review(userId, "session-a", roomId, "Hello", 1, UUID.randomUUID()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(standard, never()).translate(any());
        verify(hub, never()).publishDraft(any());
    }
    @Test void concurrentDuplicatePremiumPreviewNeverStartsASecondPaidTranslation() throws Exception {
        when(authorizer.authorize(userId, roomId)).thenReturn(
                new RoomLiveAuthorizer.Member(participantId, RoomStatus.ACTIVE,
                        "en", "ta", AiServiceProfile.PREMIUM, true));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(premium.translate(any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return new TranslationProvider.Result("வணக்கம்", "GEMINI", "gemini-3.8-flash", 1);
        });
        try (var worker = Executors.newSingleThreadExecutor()) {
            var first = worker.submit(() -> service.review(userId, "session-a", roomId, "Hello", 1));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.review(userId, "session-a", roomId, "Hello", 1))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
            release.countDown();
            assertThat(first.get(3, TimeUnit.SECONDS).profile()).isEqualTo("PREMIUM");
        }
        verify(premium).translate(any());
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

    @Test void romanizedTamilUsesAuthoritativeTamilToEnglishDirectionAndPreservesModeThroughSend() {
        when(authorizer.authorize(userId, roomId)).thenReturn(new RoomLiveAuthorizer.Member(
                participantId, RoomStatus.ACTIVE, "ta", "en", AiServiceProfile.STANDARD));
        var review = service.review(userId, "session-a", roomId,
                "ungalukku enna project update vivarangal venum", 7, null, true);
        assertThat(review.romanizedTamil()).isTrue();
        assertThat(review.sourceLanguage()).isEqualTo("ta");
        assertThat(review.targetLanguage()).isEqualTo("en");
        var request = org.mockito.ArgumentCaptor.forClass(TranslationProvider.TranslationRequest.class);
        verify(standard).translate(request.capture());
        assertThat(request.getValue().sourceLanguage()).isEqualTo("ta");
        assertThat(request.getValue().targetLanguage()).isEqualTo("en");
        assertThat(request.getValue().romanizedTamil()).isTrue();
        var sent = service.send(userId, "session-a", roomId, review.reviewId(), review.originalText(), 7);
        assertThat(sent.romanizedTamil()).isTrue();
        assertThat(sent.originalText()).isEqualTo(review.originalText());
    }

    @Test void romanizedTamilIsRejectedForEnglishSpeakingRoom() {
        assertThatThrownBy(() -> service.review(userId, "session-a", roomId,
                "hello", 1, null, true))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(standard, never()).translate(any());
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

    @Test void sendRejectsReviewWhenLanguageChangesAfterInitialCheckButBeforeAcceptance() {
        var review = service.review(userId, "session-a", roomId, "Hello", 1);
        when(authorizer.authorize(userId, roomId)).thenReturn(
                new RoomLiveAuthorizer.Member(participantId, RoomStatus.ACTIVE, "en", "ta",
                        AiServiceProfile.STANDARD, true, 0),
                new RoomLiveAuthorizer.Member(participantId, RoomStatus.ACTIVE, "ta", "en",
                        AiServiceProfile.STANDARD, true, 1));

        assertThatThrownBy(() -> service.send(userId, "session-a", roomId,
                review.reviewId(), review.originalText(), 1))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(hub, never()).publishTurn(any(), any(), any(), any(), any(), any());
        assertThat(context.snapshot(roomId)).isEmpty();
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

    @Test void reviewNeverAcceptsSharedPreviewAuthorization() {
        assertThatThrownBy(() -> service.review(userId, "session-a", roomId, "Raw source", 4,
                UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
        verify(standard, never()).translate(any());
        verify(hub, never()).publishDraft(any());
        verify(hub, never()).publishTurn(any(), any(), any(), any(), any(), any());
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

    @Test void languageVersionRejectsStaleTranslationAfterDirectionReturnsToOriginal() throws Exception {
        var version = new AtomicReference<RoomLiveAuthorizer.Member>(
                new RoomLiveAuthorizer.Member(participantId, RoomStatus.ACTIVE, "en", "ta",
                        AiServiceProfile.STANDARD, true, 0));
        when(authorizer.authorize(userId, roomId)).thenAnswer(invocation -> version.get());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(standard.translate(any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return new TranslationProvider.Result("வணக்கம்", "GEMINI", "gemini-3.1-flash-lite", 1);
        });
        try (var worker = Executors.newSingleThreadExecutor()) {
            var old = worker.submit(() -> service.review(userId, "session-a", roomId, "Old", 1));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            version.set(new RoomLiveAuthorizer.Member(participantId, RoomStatus.ACTIVE, "ta", "en",
                    AiServiceProfile.STANDARD, true, 1));
            version.set(new RoomLiveAuthorizer.Member(participantId, RoomStatus.ACTIVE, "en", "ta",
                    AiServiceProfile.STANDARD, true, 2));
            release.countDown();
            assertThatThrownBy(() -> old.get(3, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ResponseStatusException.class);
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

    @Test void disabledStandardReportsConfigurationWithoutCallingProvider() {
        doThrow(new ProviderFailure(ProviderFailure.Code.CONFIGURATION)).when(resolver)
                .requirePaidStage(AiServiceProfile.STANDARD);
        assertThatThrownBy(() -> service.review(userId, "session-a", roomId, "Hello", 1))
                .isInstanceOfSatisfying(TextTranslationFailureException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.safeCode()).isEqualTo(TextTranslationFailureException.SafeCode.CONFIGURATION);
                    assertThat(ex.getReason()).contains("provider access is not ready")
                            .doesNotContain("key", "token");
                });
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
