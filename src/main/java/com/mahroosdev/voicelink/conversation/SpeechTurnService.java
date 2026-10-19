package com.mahroosdev.voicelink.conversation;

import java.time.Instant;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.mahroosdev.voicelink.ai.ProviderFailure;
import com.mahroosdev.voicelink.ai.StandardProfile;
import com.mahroosdev.voicelink.ai.AiServiceProfile;
import com.mahroosdev.voicelink.ai.AiProviderBundle;
import com.mahroosdev.voicelink.ai.AiProviderResolver;
import com.mahroosdev.voicelink.ai.stt.SpeechToTextProvider;
import com.mahroosdev.voicelink.ai.translation.TranslationProvider;
import com.mahroosdev.voicelink.ai.tts.TextToSpeechProvider;
import com.mahroosdev.voicelink.glossary.RoomGlossaryService;
import com.mahroosdev.voicelink.room.RoomClosedEvent;
import com.mahroosdev.voicelink.room.RoomLiveAuthorizer;
import com.mahroosdev.voicelink.room.RoomLiveHub;
import com.mahroosdev.voicelink.room.RoomStatus;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SpeechTurnService {
    private static final int MAX_ROOMS = 32;
    private static final int MAX_PENDING_PER_ROOM = 4;
    private static final int MAX_RECENT = 16;
    private static final int MAX_IDEMPOTENCY = 128;
    private static final long MAX_AUDIO_BYTES = 16L * 1024 * 1024;
    private static final long AUDIO_TTL_SECONDS = 300;
    private static final long TURN_TTL_SECONDS = 600;

    private final Map<UUID, RoomQueue> states = new LinkedHashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());
    private final RoomLiveAuthorizer authorizer;
    private final RoomLiveHub hub;
    private final AiProviderResolver providers;
    private final AudioTurnValidator validator;
    private final RoomGlossaryService glossary;
    private final SpeechTurnRateLimiter rateLimiter;
    private final SpeechTurnMetrics metrics;
    private final SharedConversationContext sharedContext;
    private final Clock clock;
    private long storedAudioBytes;

    @Autowired
    public SpeechTurnService(RoomLiveAuthorizer authorizer, RoomLiveHub hub,
                             AiProviderResolver providers, AudioTurnValidator validator,
                             RoomGlossaryService glossary, SpeechTurnRateLimiter rateLimiter,
                             SpeechTurnMetrics metrics, SharedConversationContext sharedContext) {
        this(authorizer, hub, providers, validator, glossary, rateLimiter, metrics,
                sharedContext, Clock.systemUTC());
    }

    SpeechTurnService(RoomLiveAuthorizer authorizer, RoomLiveHub hub,
                      AiProviderResolver providers, AudioTurnValidator validator,
                      RoomGlossaryService glossary, SpeechTurnRateLimiter rateLimiter,
                      SpeechTurnMetrics metrics, Clock clock) {
        this(authorizer, hub, providers, validator, glossary, rateLimiter, metrics,
                new SharedConversationContext(clock), clock);
    }

    SpeechTurnService(RoomLiveAuthorizer authorizer, RoomLiveHub hub,
                      AiProviderResolver providers, AudioTurnValidator validator,
                      RoomGlossaryService glossary, SpeechTurnRateLimiter rateLimiter,
                      SpeechTurnMetrics metrics, SharedConversationContext sharedContext, Clock clock) {
        this.authorizer = authorizer;
        this.hub = hub;
        this.providers = providers;
        this.validator = validator;
        this.glossary = glossary;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.sharedContext = sharedContext;
        this.clock = clock;
    }

    public Accepted accept(UUID userId, UUID roomId, UUID clientRequestId, byte[] bytes,
                           String mediaType, long durationMillis) {
        RoomLiveAuthorizer.Member member = activeMember(userId, roomId);
        if (clientRequestId == null) throw badRequest();
        metrics.count(member.profile(), "received");
        synchronized (this) {
            expireLocked(clock.instant());
            RoomQueue room = states.get(roomId);
            String key = member.participantId() + ":" + clientRequestId;
            if (room != null && room.byRequest.containsKey(key)) {
                Turn existing = room.byRequest.get(key);
                return new Accepted(existing.id, existing.index, true, existing.profile.name());
            }
            validator.validate(bytes, mediaType, durationMillis);
            if (room == null && states.size() >= MAX_ROOMS) throw unavailable();
            if (room != null && (room.closed || room.pending >= MAX_PENDING_PER_ROOM)) throw unavailable();
            if (!rateLimiter.tryAcquire(userId)) {
                metrics.count(member.profile(), "rate_limited");
                throw rateLimited();
            }
            if (room == null) {
                room = new RoomQueue(roomId);
                states.put(roomId, room);
            }
            Turn turn = new Turn(roomId, userId, member.participantId(), clientRequestId,
                    ++room.nextIndex, member.speaking(), member.listening(), member.profile(),
                    bytes.clone(), clock.instant());
            metrics.count(turn.profile, "accepted");
            room.queue.add(turn);
            room.recent.add(turn);
            room.byRequest.put(key, turn);
            room.pending++;
            while (room.recent.size() > MAX_RECENT) discardAudio(room.recent.removeFirst());
            while (room.byRequest.size() > MAX_IDEMPOTENCY) {
                Iterator<String> ids = room.byRequest.keySet().iterator();
                ids.next(); ids.remove();
            }
            hub.publishTurn(roomId, userId, member.participantId(), turn.id, "TURN_ACCEPTED",
                    Map.of("turnIndex", turn.index, "sourceLanguage", turn.source,
                            "targetLanguage", turn.target));
            if (!room.processing) {
                room.processing = true;
                RoomQueue selected = room;
                try {
                    workers.execute(() -> runRoom(selected));
                } catch (RejectedExecutionException ex) {
                    room.processing = false;
                    room.queue.remove(turn);
                    room.pending--;
                    turn.rawAudio = null;
                    turn.status = "FAILED";
                    turn.failedStage = "PIPELINE";
                    turn.errorCode = "CAPACITY";
                    metrics.count(turn.profile, "failed");
                    metrics.duration(turn.profile, "end_to_end", turn.receivedNanos);
                    hub.publishTurn(roomId, userId, member.participantId(), turn.id, "TURN_FAILED",
                            failurePayload(turn, "The speech service is busy. Please try again later."));
                }
            }
            return new Accepted(turn.id, turn.index, false, turn.profile.name());
        }
    }

    private void runRoom(RoomQueue room) {
        while (true) {
            Turn turn;
            synchronized (this) {
                turn = room.queue.poll();
                if (turn == null || room.closed) {
                    room.processing = false;
                    return;
                }
            }
            metrics.duration(turn.profile, "queue_wait", turn.receivedNanos);
            try {
                process(turn);
            } finally {
                synchronized (this) { room.pending--; turn.rawAudio = null; }
                metrics.count(turn.profile, "READY".equals(turn.status) ? "ready" :
                        "FAILED".equals(turn.status) ? "failed" : "discarded");
                metrics.duration(turn.profile, "end_to_end", turn.receivedNanos);
            }
        }
    }

    private void process(Turn turn) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        String stage = "STT";
        try {
            if (!stillActive(turn)) return;
            AiProviderBundle bundle = providers.resolve(turn.profile);
            long stageStarted = System.nanoTime();
            SpeechToTextProvider.Result transcript;
            try {
                transcript = bundle.stt().transcribe(new SpeechToTextProvider.Input(turn.rawAudio, turn.source));
            } finally {
                metrics.duration(turn.profile, "stt", stageStarted);
            }
            synchronized (this) { turn.rawAudio = null; turn.transcript = transcript.transcript(); turn.status = "TRANSCRIBED"; }
            if (turn.transcript == null || turn.transcript.isBlank()
                    || turn.transcript.codePointCount(0, turn.transcript.length()) > 4000) {
                throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
            }
            if (!stillActive(turn)) return;
            hub.publishTurn(turn.roomId, turn.userId, turn.participantId, turn.id, "TRANSCRIPT_READY",
                    Map.of("turnIndex", turn.index, "transcript", turn.transcript,
                            "sourceLanguage", turn.source));
            requireTime(deadline);
            stage = "TRANSLATION";
            List<TranslationProvider.ContextTurn> context = sharedContext.snapshot(turn.roomId);
            List<TranslationProvider.GlossaryTerm> terms = glossary.applicableForTranslation(
                    turn.roomId, turn.source, turn.target, turn.transcript);
            if (!stillActive(turn)) return;
            requirePaidStage(turn);
            stageStarted = System.nanoTime();
            TranslationProvider.Result translated;
            try {
                translated = bundle.translation().translate(new TranslationProvider.TranslationRequest(
                        turn.transcript, turn.source, turn.target, context, terms));
            } finally {
                metrics.duration(turn.profile, "translation", stageStarted);
            }
            if (translated.translatedText() == null || translated.translatedText().isBlank()
                    || translated.translatedText().codePointCount(0, translated.translatedText().length()) > 6000) {
                throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
            }
            synchronized (this) {
                turn.translation = translated.translatedText();
                turn.translationSucceeded = true;
                turn.status = "TRANSLATED";
            }
            if (!stillActive(turn)) return;
            sharedContext.finalized(turn.roomId, turn.source, turn.target, turn.transcript);
            hub.publishTurn(turn.roomId, turn.userId, turn.participantId, turn.id, "TRANSLATION_READY",
                    Map.of("turnIndex", turn.index, "translatedText", turn.translation,
                            "targetLanguage", turn.target));
            metrics.duration(turn.profile, "caption_event", turn.receivedNanos);
            requireTime(deadline);
            stage = "TTS";
            requirePaidStage(turn);
            stageStarted = System.nanoTime();
            TextToSpeechProvider.Result spoken;
            try {
                spoken = bundle.tts().synthesize(new TextToSpeechProvider.Input(turn.translation, turn.target));
            } finally {
                metrics.duration(turn.profile, "tts", stageStarted);
            }
            if (!stillActive(turn)) return;
            requireTime(deadline);
            synchronized (this) {
                if (spoken.audio() == null || spoken.audio().length == 0
                        || spoken.audio().length > 2 * 1024 * 1024
                        || !"audio/wav".equals(spoken.mediaType())
                        || storedAudioBytes + spoken.audio().length > MAX_AUDIO_BYTES) {
                    throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
                }
                turn.generatedAudio = spoken.audio().clone();
                storedAudioBytes += turn.generatedAudio.length;
                turn.mediaType = spoken.mediaType();
                turn.audioExpiresAt = clock.instant().plusSeconds(AUDIO_TTL_SECONDS);
                turn.status = "READY";
            }
            if (!stillActive(turn)) {
                synchronized (this) { discardAudio(turn); }
                return;
            }
            hub.publishTurn(turn.roomId, turn.userId, turn.participantId, turn.id, "AUDIO_READY",
                    Map.of("turnIndex", turn.index, "audioUrl", "/api/rooms/" + turn.roomId
                            + "/turns/" + turn.id + "/audio", "mediaType", turn.mediaType));
            metrics.duration(turn.profile, "audio_event", turn.receivedNanos);
        } catch (ProviderFailure ex) {
            fail(turn, stage, ex.code().name(), ex.getMessage());
        } catch (RuntimeException ex) {
            fail(turn, stage, "UNAVAILABLE", "The speech service is temporarily unavailable.");
        }
    }

    private void fail(Turn turn, String stage, String code, String message) {
        synchronized (this) {
            turn.rawAudio = null;
            turn.status = "FAILED";
            turn.failedStage = stage;
            turn.errorCode = code;
        }
        if (stillActive(turn)) hub.publishTurn(turn.roomId, turn.userId, turn.participantId,
                turn.id, "TURN_FAILED", failurePayload(turn, message));
    }

    private static Map<String, ?> failurePayload(Turn turn, String message) {
        return Map.of("turnIndex", turn.index, "stage", turn.failedStage,
                "code", turn.errorCode, "message", message);
    }

    private boolean stillActive(Turn turn) {
        try {
            RoomLiveAuthorizer.Member member = authorizer.authorize(turn.userId, turn.roomId);
            return member.status() == RoomStatus.ACTIVE && member.profile() == turn.profile;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void requirePaidStage(Turn turn) {
        RoomLiveAuthorizer.Member member = authorizer.authorize(turn.userId, turn.roomId);
        if (member.status() != RoomStatus.ACTIVE || member.profile() != turn.profile) {
            throw new ProviderFailure(ProviderFailure.Code.CONFIGURATION);
        }
        providers.requirePaidStage(member.profile());
    }

    private RoomLiveAuthorizer.Member activeMember(UUID userId, UUID roomId) {
        RoomLiveAuthorizer.Member member = authorizer.authorize(userId, roomId);
        if (member.status() != RoomStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Room is not active");
        }
        return member;
    }

    public List<Snapshot> recent(UUID userId, UUID roomId) {
        activeMember(userId, roomId);
        synchronized (this) {
            RoomQueue room = states.get(roomId);
            if (room == null) return List.of();
            List<Snapshot> result = new ArrayList<>();
            for (Turn turn : room.recent) result.add(snapshot(turn));
            return result;
        }
    }

    public Audio audio(UUID userId, UUID roomId, UUID turnId) {
        activeMember(userId, roomId);
        synchronized (this) {
            RoomQueue room = states.get(roomId);
            if (room == null) throw notFound();
            for (Turn turn : room.recent) {
                if (turn.id.equals(turnId) && turn.generatedAudio != null
                        && clock.instant().isBefore(turn.audioExpiresAt)) {
                    return new Audio(turn.generatedAudio.clone(), turn.mediaType);
                }
            }
            throw notFound();
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onClosed(RoomClosedEvent event) {
        synchronized (this) {
            RoomQueue room = states.remove(event.roomId());
            if (room == null) return;
            room.closed = true;
            for (Turn turn : room.queue) turn.rawAudio = null;
            room.queue.clear();
            for (Turn turn : room.recent) discardAudio(turn);
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void expire() {
        synchronized (this) {
            expireLocked(clock.instant());
        }
    }

    private void expireLocked(Instant now) {
            Iterator<RoomQueue> rooms = states.values().iterator();
            while (rooms.hasNext()) {
                RoomQueue room = rooms.next();
                for (Turn turn : room.recent) {
                    if (turn.generatedAudio != null && !now.isBefore(turn.audioExpiresAt)) discardAudio(turn);
                }
                room.recent.removeIf(turn -> terminal(turn) && turn.generatedAudio == null
                        && !now.isBefore(turn.acceptedAt.plusSeconds(TURN_TTL_SECONDS)));
                room.byRequest.values().removeIf(turn -> terminal(turn) && turn.generatedAudio == null
                        && !now.isBefore(turn.acceptedAt.plusSeconds(TURN_TTL_SECONDS)));
                if (!room.processing && room.pending == 0 && room.queue.isEmpty()
                        && room.recent.isEmpty() && room.byRequest.isEmpty()) rooms.remove();
            }
    }

    private static boolean terminal(Turn turn) {
        return "READY".equals(turn.status) || "FAILED".equals(turn.status);
    }

    private void discardAudio(Turn turn) {
        if (turn.generatedAudio != null) {
            storedAudioBytes -= turn.generatedAudio.length;
            turn.generatedAudio = null;
        }
    }

    private static void requireTime(long deadline) {
        if (System.nanoTime() > deadline) throw new ProviderFailure(ProviderFailure.Code.TIMEOUT);
    }

    private static ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid turn request");
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Speech processing is busy");
    }

    private static ResponseStatusException rateLimited() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Too many speech turns. Please wait and try again.");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Audio unavailable");
    }

    private Snapshot snapshot(Turn turn) {
        return new Snapshot(turn.id, turn.index, turn.participantId, turn.source, turn.target,
                turn.status, turn.transcript, turn.translation,
                turn.generatedAudio != null && clock.instant().isBefore(turn.audioExpiresAt),
                turn.failedStage, turn.errorCode);
    }

    @PreDestroy
    public void shutdown() { workers.shutdownNow(); }

    synchronized int roomStateCount() { return states.size(); }
    synchronized long cachedAudioBytes() { return storedAudioBytes; }

    public record Accepted(UUID turnId, long turnIndex, boolean duplicate, String profile) {}
    public record Audio(byte[] bytes, String mediaType) {}
    public record Snapshot(UUID turnId, long turnIndex, UUID senderParticipantId,
                           String sourceLanguage, String targetLanguage, String status,
                           String transcript, String translatedText, boolean audioAvailable,
                           String failedStage, String errorCode) {}

    private static final class RoomQueue {
        final UUID roomId;
        final ArrayDeque<Turn> queue = new ArrayDeque<>();
        final ArrayDeque<Turn> recent = new ArrayDeque<>();
        final LinkedHashMap<String, Turn> byRequest = new LinkedHashMap<>();
        long nextIndex;
        int pending;
        boolean processing;
        boolean closed;

        RoomQueue(UUID roomId) { this.roomId = roomId; }
    }

    private static final class Turn {
        final UUID id = UUID.randomUUID();
        final UUID roomId;
        final UUID userId;
        final UUID participantId;
        final UUID clientRequestId;
        final long index;
        final String source;
        final String target;
        final AiServiceProfile profile;
        final Instant acceptedAt;
        final long receivedNanos = System.nanoTime();
        byte[] rawAudio;
        byte[] generatedAudio;
        Instant audioExpiresAt;
        String mediaType;
        String transcript;
        String translation;
        boolean translationSucceeded;
        String status = "PENDING";
        String failedStage;
        String errorCode;

        Turn(UUID roomId, UUID userId, UUID participantId, UUID clientRequestId,
             long index, String source, String target, AiServiceProfile profile, byte[] rawAudio,
             Instant acceptedAt) {
            this.roomId = roomId;
            this.userId = userId;
            this.participantId = participantId;
            this.clientRequestId = clientRequestId;
            this.index = index;
            this.source = source;
            this.target = target;
            this.profile = profile;
            this.rawAudio = rawAudio;
            this.acceptedAt = acceptedAt;
        }
    }
}
