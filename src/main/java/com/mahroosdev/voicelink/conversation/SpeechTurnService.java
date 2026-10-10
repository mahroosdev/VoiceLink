package com.mahroosdev.voicelink.conversation;

import java.time.Instant;
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
    private final SpeechToTextProvider stt;
    private final TranslationProvider translation;
    private final TextToSpeechProvider tts;
    private final AudioTurnValidator validator;
    private final RoomGlossaryService glossary;
    private long storedAudioBytes;

    public SpeechTurnService(RoomLiveAuthorizer authorizer, RoomLiveHub hub,
                             SpeechToTextProvider stt, TranslationProvider translation,
                             TextToSpeechProvider tts, AudioTurnValidator validator,
                             RoomGlossaryService glossary) {
        this.authorizer = authorizer;
        this.hub = hub;
        this.stt = stt;
        this.translation = translation;
        this.tts = tts;
        this.validator = validator;
        this.glossary = glossary;
    }

    public Accepted accept(UUID userId, UUID roomId, UUID clientRequestId, byte[] bytes,
                           String mediaType, long durationMillis) {
        RoomLiveAuthorizer.Member member = activeMember(userId, roomId);
        if (clientRequestId == null) throw badRequest();
        synchronized (this) {
            RoomQueue room = states.get(roomId);
            String key = member.participantId() + ":" + clientRequestId;
            if (room != null && room.byRequest.containsKey(key)) {
                Turn existing = room.byRequest.get(key);
                return new Accepted(existing.id, existing.index, true, StandardProfile.ID);
            }
            validator.validate(bytes, mediaType, durationMillis);
            if (room == null) {
                if (states.size() >= MAX_ROOMS) throw unavailable();
                room = new RoomQueue(roomId);
                states.put(roomId, room);
            }
            if (room.closed || room.pending >= MAX_PENDING_PER_ROOM) throw unavailable();
            Turn turn = new Turn(roomId, userId, member.participantId(), clientRequestId,
                    ++room.nextIndex, member.speaking(), member.listening(), bytes.clone());
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
                    hub.publishTurn(roomId, userId, member.participantId(), turn.id, "TURN_FAILED",
                            failurePayload(turn, "The speech service is busy. Please try again later."));
                }
            }
            return new Accepted(turn.id, turn.index, false, StandardProfile.ID);
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
            try {
                process(turn);
            } finally {
                synchronized (this) { room.pending--; turn.rawAudio = null; }
            }
        }
    }

    private void process(Turn turn) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        String stage = "STT";
        try {
            if (!stillActive(turn)) return;
            SpeechToTextProvider.Result transcript = stt.transcribe(
                    new SpeechToTextProvider.Input(turn.rawAudio, turn.source));
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
            List<TranslationProvider.ContextTurn> context = contextFor(turn);
            List<TranslationProvider.GlossaryTerm> terms = glossary.applicableForTranslation(
                    turn.roomId, turn.source, turn.target, turn.transcript);
            if (!stillActive(turn)) return;
            TranslationProvider.Result translated = translation.translate(new TranslationProvider.TranslationRequest(
                    turn.transcript, turn.source, turn.target, context, terms));
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
            hub.publishTurn(turn.roomId, turn.userId, turn.participantId, turn.id, "TRANSLATION_READY",
                    Map.of("turnIndex", turn.index, "translatedText", turn.translation,
                            "targetLanguage", turn.target));
            requireTime(deadline);
            stage = "TTS";
            TextToSpeechProvider.Result spoken = tts.synthesize(
                    new TextToSpeechProvider.Input(turn.translation, turn.target));
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
                turn.audioExpiresAt = Instant.now().plusSeconds(AUDIO_TTL_SECONDS);
                turn.status = "READY";
            }
            if (!stillActive(turn)) {
                synchronized (this) { discardAudio(turn); }
                return;
            }
            hub.publishTurn(turn.roomId, turn.userId, turn.participantId, turn.id, "AUDIO_READY",
                    Map.of("turnIndex", turn.index, "audioUrl", "/api/rooms/" + turn.roomId
                            + "/turns/" + turn.id + "/audio", "mediaType", turn.mediaType));
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

    private List<TranslationProvider.ContextTurn> contextFor(Turn current) {
        synchronized (this) {
            RoomQueue room = states.get(current.roomId);
            if (room == null || room.closed) return List.of();
            List<RecentContext.Candidate> prior = room.recent.stream().map(turn ->
                    new RecentContext.Candidate(turn.index, turn.acceptedAt, turn.source,
                            turn.target, turn.transcript, turn.translationSucceeded)).toList();
            return RecentContext.select(prior, current.index, Instant.now());
        }
    }

    private boolean stillActive(Turn turn) {
        try {
            return authorizer.authorize(turn.userId, turn.roomId).status() == RoomStatus.ACTIVE;
        } catch (RuntimeException ex) {
            return false;
        }
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
                        && Instant.now().isBefore(turn.audioExpiresAt)) {
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
            Instant now = Instant.now();
            Iterator<RoomQueue> rooms = states.values().iterator();
            while (rooms.hasNext()) {
                RoomQueue room = rooms.next();
                for (Turn turn : room.recent) {
                    if (turn.generatedAudio != null && !now.isBefore(turn.audioExpiresAt)) discardAudio(turn);
                }
                room.recent.removeIf(turn -> !"PENDING".equals(turn.status)
                        && turn.acceptedAt.plusSeconds(TURN_TTL_SECONDS).isBefore(now));
                room.byRequest.values().removeIf(turn -> !"PENDING".equals(turn.status)
                        && turn.acceptedAt.plusSeconds(TURN_TTL_SECONDS).isBefore(now));
                // Retain the room counter until close so turnIndex stays monotonic in this runtime.
            }
        }
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

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Audio unavailable");
    }

    private Snapshot snapshot(Turn turn) {
        return new Snapshot(turn.id, turn.index, turn.participantId, turn.source, turn.target,
                turn.status, turn.transcript, turn.translation,
                turn.generatedAudio != null && Instant.now().isBefore(turn.audioExpiresAt),
                turn.failedStage, turn.errorCode);
    }

    @PreDestroy
    public void shutdown() { workers.shutdownNow(); }

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
        final Instant acceptedAt = Instant.now();
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
             long index, String source, String target, byte[] rawAudio) {
            this.roomId = roomId;
            this.userId = userId;
            this.participantId = participantId;
            this.clientRequestId = clientRequestId;
            this.index = index;
            this.source = source;
            this.target = target;
            this.rawAudio = rawAudio;
        }
    }
}
