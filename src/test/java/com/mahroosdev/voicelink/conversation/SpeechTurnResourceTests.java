package com.mahroosdev.voicelink.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.mahroosdev.voicelink.ai.AiProviderBundle;
import com.mahroosdev.voicelink.ai.AiProviderResolver;
import com.mahroosdev.voicelink.ai.AiServiceProfile;
import com.mahroosdev.voicelink.ai.stt.SpeechToTextProvider;
import com.mahroosdev.voicelink.ai.translation.TranslationProvider;
import com.mahroosdev.voicelink.ai.tts.TextToSpeechProvider;
import com.mahroosdev.voicelink.glossary.RoomGlossaryService;
import com.mahroosdev.voicelink.room.RoomClosedEvent;
import com.mahroosdev.voicelink.room.RoomLiveAuthorizer;
import com.mahroosdev.voicelink.room.RoomLiveHub;
import com.mahroosdev.voicelink.room.RoomStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SpeechTurnResourceTests {
    private final UUID user = UUID.randomUUID();
    private final MutableClock clock = new MutableClock();
    private final AtomicLong ticker = new AtomicLong();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SpeechToTextProvider stt = mock(SpeechToTextProvider.class);
    private final TranslationProvider translation = mock(TranslationProvider.class);
    private final TextToSpeechProvider tts = mock(TextToSpeechProvider.class);
    private final SpeechTurnService turns = service(100, 200);

    private SpeechTurnService service(int perUser, int global) {
        RoomLiveAuthorizer authorizer = mock(RoomLiveAuthorizer.class);
        RoomLiveHub hub = mock(RoomLiveHub.class);
        AiProviderResolver resolver = mock(AiProviderResolver.class);
        RoomGlossaryService glossary = mock(RoomGlossaryService.class);
        when(authorizer.authorize(any(), any())).thenAnswer(call -> new RoomLiveAuthorizer.Member(
                call.getArgument(0), RoomStatus.ACTIVE, "en", "ta", AiServiceProfile.STANDARD));
        when(resolver.resolve(AiServiceProfile.STANDARD))
                .thenReturn(new AiProviderBundle(stt, translation, tts));
        when(glossary.applicableForTranslation(any(), any(), any(), any())).thenReturn(List.of());
        when(stt.transcribe(any())).thenReturn(new SpeechToTextProvider.Result("Hello", "GROQ", "whisper-large-v3", 1));
        when(translation.translate(any())).thenReturn(new TranslationProvider.Result("வணக்கம்", "GEMINI",
                "gemini-3.1-flash-lite", 1));
        when(tts.synthesize(any())).thenReturn(new TextToSpeechProvider.Result(
                new byte[]{1, 2, 3}, "audio/wav", "GEMINI", "Kore", 1));
        return new SpeechTurnService(authorizer, hub, resolver, new AudioTurnValidator(), glossary,
                new SpeechTurnRateLimiter(perUser, global, ticker::get), new SpeechTurnMetrics(registry), clock);
    }

    @AfterEach void stopWorkers() { turns.shutdown(); registry.close(); }

    private static byte[] webm() {
        byte[] bytes = new byte[64];
        bytes[0] = 0x1a; bytes[1] = 0x45; bytes[2] = (byte) 0xdf; bytes[3] = (byte) 0xa3;
        byte[] marker = "webmA_OPUSOpusHead".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(marker, 0, bytes, 8, marker.length);
        return bytes;
    }

    private SpeechTurnService.Accepted accept(UUID room, UUID request) {
        return turns.accept(user, room, request, webm(), "audio/webm;codecs=opus", 1000);
    }

    private void await(UUID room, int count) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            var recent = turns.recent(user, room);
            if (recent.size() == count && recent.stream().allMatch(turn -> "READY".equals(turn.status()))) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Speech turns did not finish");
    }

    private void expireUntilEmpty() throws InterruptedException {
        for (int retry = 0; retry < 100 && turns.roomStateCount() != 0; retry++) {
            turns.expire();
            if (turns.roomStateCount() != 0) Thread.sleep(10);
        }
        assertThat(turns.roomStateCount()).isZero();
    }

    @Test void moreThanThirtyTwoHistoricRoomsAreReclaimedAfterTemporaryDataExpires() throws Exception {
        for (int i = 0; i < 40; i++) {
            UUID room = UUID.randomUUID();
            assertThat(accept(room, UUID.randomUUID()).turnIndex()).isEqualTo(1);
            await(room, 1);
            assertThat(turns.roomStateCount()).isEqualTo(1);
            clock.advanceSeconds(601);
            ticker.addAndGet(TimeUnit.SECONDS.toNanos(601));
            expireUntilEmpty();
            assertThat(turns.cachedAudioBytes()).isZero();
        }
    }

    @Test void fullCapacityKeepsRoomsWithUnexpiredCaptionsAndAudio() throws Exception {
        for (int i = 0; i < 32; i++) {
            UUID room = UUID.randomUUID();
            accept(room, UUID.randomUUID());
            await(room, 1);
        }
        assertThat(turns.roomStateCount()).isEqualTo(32);
        assertThatThrownBy(() -> accept(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(turns.cachedAudioBytes()).isEqualTo(96);
        clock.advanceSeconds(601);
        ticker.addAndGet(TimeUnit.SECONDS.toNanos(601));
        UUID next = UUID.randomUUID();
        accept(next, UUID.randomUUID());
        await(next, 1);
        assertThat(turns.roomStateCount()).isEqualTo(1);
    }

    @Test void liveAudioAndContextRemainUntilExpiryThenRoomCanReenter() throws Exception {
        UUID room = UUID.randomUUID();
        var first = accept(room, UUID.randomUUID());
        await(room, 1);
        assertThat(turns.audio(user, room, first.turnId()).bytes()).containsExactly(1, 2, 3);
        clock.advanceSeconds(299);
        turns.expire();
        assertThat(turns.roomStateCount()).isEqualTo(1);
        assertThat(turns.cachedAudioBytes()).isEqualTo(3);
        clock.advanceSeconds(302);
        ticker.addAndGet(TimeUnit.SECONDS.toNanos(601));
        expireUntilEmpty();
        assertThat(turns.cachedAudioBytes()).isZero();
        assertThat(accept(room, UUID.randomUUID()).turnIndex()).isEqualTo(1);
        await(room, 1);
        for (String stage : List.of("queue_wait", "stt", "translation", "caption_event", "tts",
                "audio_event", "end_to_end")) {
            assertThat(registry.get("voicelink.speech.duration").tag("profile", "STANDARD")
                    .tag("stage", stage).timer().count()).isPositive();
        }
        assertThat(registry.get("voicelink.speech.turns").tag("event", "received").counter().count())
                .isPositive();
    }

    @Test void duplicateBypassesRateLimitButNewTurnIsRejectedBeforeProvider() throws Exception {
        SpeechTurnService limited = service(1, 1);
        try {
            UUID room = UUID.randomUUID(), request = UUID.randomUUID();
            var first = limited.accept(user, room, request, webm(), "audio/webm;codecs=opus", 1000);
            assertThat(limited.accept(user, room, request, webm(), "audio/webm;codecs=opus", 1000))
                    .satisfies(copy -> { assertThat(copy.duplicate()).isTrue(); assertThat(copy.turnId()).isEqualTo(first.turnId()); });
            assertThatThrownBy(() -> limited.accept(user, UUID.randomUUID(), UUID.randomUUID(),
                    webm(), "audio/webm;codecs=opus", 1000))
                    .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                        assertThat(ex.getReason()).isEqualTo("Too many speech turns. Please wait and try again.");
                    });
            assertThat(limited.roomStateCount()).isEqualTo(1);
            for (int i = 0; i < 200 && limited.recent(user, room).getFirst().status().equals("PENDING"); i++)
                Thread.sleep(10);
            verify(stt).transcribe(any());
            assertThat(registry.get("voicelink.speech.turns").tag("event", "rate_limited").counter().count())
                    .isEqualTo(1);
        } finally {
            limited.shutdown();
        }
    }

    @Test void concurrentAcceptanceKeepsQueuedTurnsAndClosureClearsInFlightState() throws Exception {
        UUID room = UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(stt.transcribe(any())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                release.await(3, TimeUnit.SECONDS);
            }
            return new SpeechToTextProvider.Result("Hello", "GROQ", "whisper-large-v3", 1);
        });
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<SpeechTurnService.Accepted>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> accept(room, UUID.randomUUID())));
            List<Long> indices = new ArrayList<>();
            for (var future : futures) indices.add(future.get(3, TimeUnit.SECONDS).turnIndex());
            assertThat(indices).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            clock.advanceSeconds(601);
            turns.expire();
            assertThat(turns.roomStateCount()).isEqualTo(1);
            assertThat(turns.recent(user, room)).hasSize(4);
            release.countDown();
            await(room, 4);
            turns.onClosed(new RoomClosedEvent(room));
            assertThat(turns.roomStateCount()).isZero();
            assertThat(turns.cachedAudioBytes()).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-10-19T00:00:00Z");
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
