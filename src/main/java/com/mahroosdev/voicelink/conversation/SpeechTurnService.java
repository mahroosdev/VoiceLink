package com.mahroosdev.voicelink.conversation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
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
import com.mahroosdev.voicelink.ai.PremiumUsageGuard;
import com.mahroosdev.voicelink.ai.stt.SpeechToTextProvider;
import com.mahroosdev.voicelink.ai.translation.TranslationProvider;
import com.mahroosdev.voicelink.ai.tts.TextToSpeechProvider;
import com.mahroosdev.voicelink.auth.AccountDeletedEvent;
import com.mahroosdev.voicelink.auth.PasswordResetCompletedEvent;
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
    private static final long PIPELINE_SECONDS = 45;
    private static final Duration TRANSLATION_CEILING = Duration.ofSeconds(15);
    private static final Duration TTS_TIMEOUT_RESERVE = Duration.ofSeconds(15);
    private static final Duration STAGE_HANDOFF_RESERVE = Duration.ofMillis(250);
    private static final Duration MINIMUM_TRANSLATION_BUDGET = Duration.ofSeconds(1);
    private static final int MAX_ROOMS = 32;
    private static final int MAX_PENDING_PER_ROOM = 4;
    private static final int MAX_RECENT = 16;
    private static final int MAX_IDEMPOTENCY = 128;
    private static final long MAX_AUDIO_BYTES = 16L * 1024 * 1024;
    private static final long AUDIO_TTL_SECONDS = 300;
    private static final long TURN_TTL_SECONDS = 600;
    private static final long REVIEW_TTL_SECONDS = 120;

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
    private final PremiumUsageGuard premiumUsage;
    private final Clock clock;
    private long storedAudioBytes;

    @Autowired
    public SpeechTurnService(RoomLiveAuthorizer authorizer, RoomLiveHub hub,
                             AiProviderResolver providers, AudioTurnValidator validator,
                             RoomGlossaryService glossary, SpeechTurnRateLimiter rateLimiter,
                             SpeechTurnMetrics metrics, SharedConversationContext sharedContext,
                             PremiumUsageGuard premiumUsage) {
        this(authorizer, hub, providers, validator, glossary, rateLimiter, metrics,
                sharedContext, premiumUsage, Clock.systemUTC());
    }

    SpeechTurnService(RoomLiveAuthorizer authorizer, RoomLiveHub hub,
                      AiProviderResolver providers, AudioTurnValidator validator,
                      RoomGlossaryService glossary, SpeechTurnRateLimiter rateLimiter,
                      SpeechTurnMetrics metrics, Clock clock) {
        this(authorizer, hub, providers, validator, glossary, rateLimiter, metrics,
                new SharedConversationContext(clock), PremiumUsageGuard.unlimitedForTests(), clock);
    }

    SpeechTurnService(RoomLiveAuthorizer authorizer, RoomLiveHub hub,
                      AiProviderResolver providers, AudioTurnValidator validator,
                      RoomGlossaryService glossary, SpeechTurnRateLimiter rateLimiter,
                      SpeechTurnMetrics metrics, SharedConversationContext sharedContext, Clock clock) {
        this(authorizer, hub, providers, validator, glossary, rateLimiter, metrics,
                sharedContext, PremiumUsageGuard.unlimitedForTests(), clock);
    }

    SpeechTurnService(RoomLiveAuthorizer authorizer, RoomLiveHub hub,
                      AiProviderResolver providers, AudioTurnValidator validator,
                      RoomGlossaryService glossary, SpeechTurnRateLimiter rateLimiter,
                      SpeechTurnMetrics metrics, SharedConversationContext sharedContext,
                      PremiumUsageGuard premiumUsage, Clock clock) {
        this.authorizer = authorizer;
        this.hub = hub;
        this.providers = providers;
        this.validator = validator;
        this.glossary = glossary;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.sharedContext = sharedContext;
        this.premiumUsage = premiumUsage;
        this.clock = clock;
    }

    public Accepted accept(UUID userId, UUID roomId, UUID clientRequestId, byte[] bytes,
                           String mediaType, long durationMillis) {
        return accept(userId, null, roomId, clientRequestId, Workflow.LIVE_SEND,
                bytes, mediaType, durationMillis);
    }

    public Accepted accept(UUID userId, String sessionId, UUID roomId, UUID clientRequestId,
                           Workflow workflow, byte[] bytes, String mediaType, long durationMillis) {
        RoomLiveAuthorizer.Member member = activeMember(userId, roomId);
        if (clientRequestId == null || workflow == null
                || workflow == Workflow.FINISH_AND_SEND && (sessionId == null || sessionId.isBlank()))
            throw badRequest();
        metrics.count(member.profile(), "received");
        synchronized (this) {
            expireLocked(clock.instant());
            RoomQueue room = states.get(roomId);
            String key = member.participantId() + ":" + workflow + ":"
                    + (workflow == Workflow.LIVE_SEND ? "live" : sessionId) + ":" + clientRequestId;
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
            Turn turn = new Turn(roomId, userId, member.participantId(), sessionId, clientRequestId,
                    ++room.nextIndex, member.speaking(), member.listening(), member.profile(),
                    member.premiumCostSaverEnabled(), member.languageVersion(), workflow, bytes.clone(), clock.instant());
            metrics.count(turn.profile, "accepted");
            room.queue.add(new Work(turn, WorkKind.PROCESS));
            room.recent.add(turn);
            room.byRequest.put(key, turn);
            room.pending++;
            while (room.recent.size() > MAX_RECENT) {
                Turn evicted = room.recent.removeFirst();
                if (evicted.workflow == Workflow.FINISH_AND_SEND && !evicted.sent) cancelLocked(evicted);
                discardAudio(evicted);
            }
            while (room.byRequest.size() > MAX_IDEMPOTENCY) {
                Iterator<String> ids = room.byRequest.keySet().iterator();
                ids.next(); ids.remove();
            }
            if (workflow == Workflow.LIVE_SEND) publishTurn(turn, "TURN_ACCEPTED",
                    Map.of("turnIndex", turn.index, "sourceLanguage", turn.source,
                            "targetLanguage", turn.target), true);
            if (!room.processing) {
                room.processing = true;
                RoomQueue selected = room;
                try {
                    workers.execute(() -> runRoom(selected));
                } catch (RejectedExecutionException ex) {
                    room.processing = false;
                    room.queue.removeLastOccurrence(new Work(turn, WorkKind.PROCESS));
                    room.pending--;
                    turn.rawAudio = null;
                    turn.status = "FAILED";
                    turn.failedStage = "PIPELINE";
                    turn.errorCode = "CAPACITY";
                    metrics.count(turn.profile, "failed");
                    metrics.duration(turn.profile, "end_to_end", turn.receivedNanos);
                    if (workflow == Workflow.LIVE_SEND)
                        publishTurn(turn, "TURN_FAILED",
                                failurePayload(turn, "The speech service is busy. Please try again later."), false);
                }
            }
            return new Accepted(turn.id, turn.index, false, turn.profile.name());
        }
    }

    private void runRoom(RoomQueue room) {
        while (true) {
            Work work;
            synchronized (this) {
                work = room.queue.poll();
                if (work == null || room.closed) {
                    room.processing = false;
                    return;
                }
            }
            Turn turn = work.turn;
            metrics.duration(turn.profile, "queue_wait", turn.receivedNanos);
            try {
                if (work.kind == WorkKind.PROCESS) process(turn);
                else if (work.kind == WorkKind.RETRY_TRANSLATION) retryTranslationWork(turn);
                else synthesize(turn);
            } finally {
                synchronized (this) { room.pending--; turn.rawAudio = null; }
                metrics.count(turn.profile, "READY".equals(turn.status) || "READY_FOR_REVIEW".equals(turn.status) ? "ready" :
                        "FAILED".equals(turn.status) ? "failed" : "discarded");
                metrics.duration(turn.profile, "end_to_end", turn.receivedNanos);
            }
        }
    }

    private void process(Turn turn) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PIPELINE_SECONDS);
        String stage = "STT";
        try {
            if (!stillActive(turn)) return;
            AiProviderBundle bundle = providers.resolve(turn.profile);
            long stageStarted = System.nanoTime();
            SpeechToTextProvider.Result transcript;
            String terminologyHints = glossarySpeechHints(turn.roomId, turn.userId, turn.source);
            try {
                transcript = bundle.stt().transcribe(new SpeechToTextProvider.Input(
                        turn.rawAudio, turn.source, terminologyHints));
            } finally {
                metrics.duration(turn.profile, "stt", stageStarted);
            }
            synchronized (this) {
                if (turn.cancelled) return;
                turn.rawAudio = null;
                turn.transcript = transcript.transcript();
                turn.status = "TRANSCRIBED";
                if (turn.workflow == Workflow.FINISH_AND_SEND)
                    turn.reviewExpiresAt = clock.instant().plusSeconds(REVIEW_TTL_SECONDS);
            }
            if (turn.transcript == null || turn.transcript.isBlank()
                    || turn.transcript.codePointCount(0, turn.transcript.length()) > 4000) {
                throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
            }
            if (!stillActive(turn)) return;
            requireTime(deadline);
            stage = "TRANSLATION";
            List<TranslationProvider.ContextTurn> context = sharedContext.snapshot(turn.roomId);
            List<RoomGlossaryService.EntryView> glossaryVersion = turn.workflow == Workflow.FINISH_AND_SEND
                    ? glossary.list(turn.userId, turn.roomId) : List.of();
            List<TranslationProvider.GlossaryTerm> terms = glossary.applicableForTranslation(
                    turn.roomId, turn.source, turn.target, turn.transcript);
            if (!stillActive(turn)) return;
            requirePaidStage(turn);
            stageStarted = System.nanoTime();
            TranslationProvider.Result translated;
            try (PremiumUsageGuard.Permit permit = premiumUsage.acquire(turn.profile, turn.userId, turn.roomId,
                    PremiumUsageGuard.Operation.TRANSLATION, turn.premiumCostSaverEnabled, false)) {
                Duration translationBudget = translationBudget(deadline - System.nanoTime(),
                        turn.workflow == Workflow.LIVE_SEND);
                translated = bundle.translation().translate(new TranslationProvider.TranslationRequest(
                        turn.transcript, turn.source, turn.target, context, terms, translationBudget));
                permit.success();
            } finally {
                metrics.duration(turn.profile, "translation", stageStarted);
            }
            premiumUsage.recordTranslationUsage(turn.profile, turn.premiumCostSaverEnabled,
                    translated.usage().inputTokens(), translated.usage().outputTokens(),
                    translated.usage().thinkingTokens());
            if (translated.translatedText() == null || translated.translatedText().isBlank()
                    || translated.translatedText().codePointCount(0, translated.translatedText().length()) > 6000) {
                throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
            }
            synchronized (this) {
                if (turn.cancelled) return;
                turn.translation = translated.translatedText();
                turn.translationSucceeded = true;
                turn.glossaryVersion = glossaryVersion;
                turn.status = turn.workflow == Workflow.FINISH_AND_SEND ? "READY_FOR_REVIEW" : "TRANSLATED";
                if (turn.workflow == Workflow.FINISH_AND_SEND)
                    turn.reviewExpiresAt = clock.instant().plusSeconds(REVIEW_TTL_SECONDS);
            }
            if (!stillActive(turn)) return;
            if (turn.workflow == Workflow.FINISH_AND_SEND) return;
            if (!publishFinalResult(turn, true)) return;
            metrics.duration(turn.profile, "caption_event", turn.receivedNanos);
            stage = "TTS";
            synthesize(turn, bundle, deadline);
        } catch (ProviderFailure ex) {
            fail(turn, stage, ex.code().name(), ex.getMessage());
        } catch (RuntimeException ex) {
            fail(turn, stage, "UNAVAILABLE", "The speech service is temporarily unavailable.");
        }
    }

    private void fail(Turn turn, String stage, String code, String message) {
        synchronized (this) {
            if (turn.cancelled) return;
            turn.rawAudio = null;
            turn.status = "FAILED";
            turn.failedStage = stage;
            turn.errorCode = code;
        }
        if ((turn.workflow == Workflow.LIVE_SEND || turn.sent) && stillActive(turn)) {
            if ("TRANSLATION".equals(stage) && turn.workflow == Workflow.LIVE_SEND) {
                publishTurn(turn, "TURN_FAILED", failurePayload(turn, message), true);
            } else {
                publishTurn(turn, "TURN_FAILED", failurePayload(turn, message), false);
            }
        }
    }

    private String glossarySpeechHints(UUID roomId, UUID userId, String sourceLanguage) {
        String prefix = "Recognize these room terminology hints when clearly heard: ";
        StringBuilder terms = new StringBuilder();
        for (RoomGlossaryService.EntryView term : glossary.list(userId, roomId)) {
            if (!sourceLanguage.equals(term.sourceLanguage())) continue;
            String candidate = term.sourceTerm();
            int additionLength = candidate.length() + (terms.isEmpty() ? 0 : 2);
            if (prefix.length() + terms.length() + additionLength > 224) break;
            if (!terms.isEmpty()) terms.append(", ");
            terms.append(candidate);
        }
        return terms.isEmpty() ? "" : prefix + terms;
    }

    public RetryAccepted retryTranslation(UUID userId, String sessionId, UUID roomId, UUID turnId) {
        RoomLiveAuthorizer.Member member = activeMember(userId, roomId);
        synchronized (this) {
            expireLocked(clock.instant());
            RoomQueue room = states.get(roomId);
            Turn turn = room == null ? null : room.recent.stream()
                    .filter(candidate -> candidate.id.equals(turnId)).findFirst().orElse(null);
            if (turn == null || turn.workflow != Workflow.LIVE_SEND || !turn.userId.equals(userId)
                    || !turn.participantId.equals(member.participantId())
                    || !java.util.Objects.equals(turn.sessionId, sessionId)) throw reviewGone();
            if (!"FAILED".equals(turn.status) || !"TRANSLATION".equals(turn.failedStage)
                    || turn.transcript == null || turn.transcript.isBlank()
                    || !clock.instant().isBefore(turn.acceptedAt.plusSeconds(TURN_TTL_SECONDS)))
                throw conflict("This recognized turn is no longer available for translation retry.");
            if (member.profile() != turn.profile || !member.speaking().equals(turn.source)
                    || !member.listening().equals(turn.target) || member.languageVersion() != turn.languageVersion)
                throw conflict("The room language or service profile changed. Record a new turn.");
            if (room.closed || room.pending >= MAX_PENDING_PER_ROOM) throw unavailable();
            if (!rateLimiter.tryAcquire(userId)) throw rateLimited();
            turn.status = "TRANSLATING";
            turn.failedStage = null;
            turn.errorCode = null;
            room.queue.addLast(new Work(turn, WorkKind.RETRY_TRANSLATION));
            room.pending++;
            scheduleLocked(room, turn, false);
            return new RetryAccepted(turn.id, "TRANSLATING");
        }
    }

    private void retryTranslationWork(Turn turn) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PIPELINE_SECONDS);
        String stage = "TRANSLATION";
        try {
            if (!stillActive(turn)) return;
            requireTime(deadline);
            List<TranslationProvider.ContextTurn> context = sharedContext.snapshot(turn.roomId);
            List<TranslationProvider.GlossaryTerm> terms = glossary.applicableForTranslation(
                    turn.roomId, turn.source, turn.target, turn.transcript);
            AiProviderBundle bundle = providers.resolve(turn.profile);
            requirePaidStage(turn);
            long started = System.nanoTime();
            TranslationProvider.Result translated;
            try (PremiumUsageGuard.Permit permit = premiumUsage.acquire(turn.profile, turn.userId, turn.roomId,
                    PremiumUsageGuard.Operation.TRANSLATION, turn.premiumCostSaverEnabled, false)) {
                translated = bundle.translation().translate(new TranslationProvider.TranslationRequest(
                        turn.transcript, turn.source, turn.target, context, terms,
                        translationBudget(deadline - System.nanoTime(), true)));
                permit.success();
            } finally { metrics.duration(turn.profile, "translation", started); }
            premiumUsage.recordTranslationUsage(turn.profile, turn.premiumCostSaverEnabled,
                    translated.usage().inputTokens(), translated.usage().outputTokens(),
                    translated.usage().thinkingTokens());
            if (translated.translatedText() == null || translated.translatedText().isBlank()
                    || translated.translatedText().codePointCount(0, translated.translatedText().length()) > 6000)
                throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
            synchronized (this) {
                if (turn.cancelled || turn.status.equals("EXPIRED")) return;
                turn.translation = translated.translatedText();
                turn.translationSucceeded = true;
                turn.status = "TRANSLATED";
            }
            if (!stillActive(turn)) return;
            if (!publishFinalResult(turn, true)) return;
            metrics.duration(turn.profile, "caption_event", turn.receivedNanos);
            stage = "TTS";
            synthesize(turn, bundle, deadline);
        } catch (ProviderFailure ex) {
            fail(turn, stage, ex.code().name(), ex.getMessage());
        } catch (RuntimeException ex) {
            fail(turn, stage, "UNAVAILABLE", "The speech service is temporarily unavailable.");
        }
    }

    private void synthesize(Turn turn) {
        try {
            if (!stillActive(turn) || !turn.sent) return;
            synthesize(turn, providers.resolve(turn.profile),
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(45));
        } catch (ProviderFailure ex) {
            fail(turn, "TTS", ex.code().name(), ex.getMessage());
        } catch (RuntimeException ex) {
            fail(turn, "TTS", "UNAVAILABLE", "The speech service is temporarily unavailable.");
        }
    }

    private void synthesize(Turn turn, AiProviderBundle bundle, long deadline) {
        requireRemaining(deadline, TTS_TIMEOUT_RESERVE);
        requirePaidStage(turn);
        long stageStarted = System.nanoTime();
        TextToSpeechProvider.Result spoken;
        try (PremiumUsageGuard.Permit permit = premiumUsage.acquire(turn.profile, turn.userId, turn.roomId,
                PremiumUsageGuard.Operation.TTS, turn.premiumCostSaverEnabled, false)) {
            spoken = bundle.tts().synthesize(new TextToSpeechProvider.Input(turn.translation, turn.target));
            permit.success();
        } finally {
            metrics.duration(turn.profile, "tts", stageStarted);
        }
        if (!stillActive(turn)) return;
        requireTime(deadline);
        boolean current;
        ProviderFailure[] invalidAudio = new ProviderFailure[1];
        try {
            synchronized (this) {
                // Keep the service monitor before the room-row lock, matching send()
                // and audio(). The persisted lock fences this storage/publication block
                // against language changes and room closure across app instances.
                current = hub.runIfCurrentLanguageVersion(turn.roomId, turn.userId,
                        turn.participantId, turn.languageVersion, () -> {
                    if (spoken.audio() == null || spoken.audio().length == 0
                            || spoken.audio().length > 2 * 1024 * 1024
                            || !"audio/wav".equals(spoken.mediaType())
                            || storedAudioBytes + spoken.audio().length > MAX_AUDIO_BYTES) {
                        invalidAudio[0] = new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
                        return;
                    }
                    turn.generatedAudio = spoken.audio().clone();
                    storedAudioBytes += turn.generatedAudio.length;
                    turn.mediaType = spoken.mediaType();
                    turn.audioExpiresAt = clock.instant().plusSeconds(AUDIO_TTL_SECONDS);
                    turn.status = "READY";
                    hub.publishTurn(turn.roomId, turn.userId, turn.participantId, turn.id, "AUDIO_READY",
                            Map.of("turnIndex", turn.index, "audioUrl", "/api/rooms/" + turn.roomId
                                    + "/turns/" + turn.id + "/audio", "mediaType", turn.mediaType));
                });
            }
        } catch (RuntimeException ex) {
            // Audio is process memory, so explicitly undo it if transaction commit
            // or event publication fails after the callback has populated the turn.
            synchronized (this) { discardAudio(turn); }
            throw ex;
        }
        if (!current) {
            synchronized (this) { discardAudio(turn); }
            return;
        }
        if (invalidAudio[0] != null) throw invalidAudio[0];
        metrics.duration(turn.profile, "audio_event", turn.receivedNanos);
    }

    private static Map<String, ?> failurePayload(Turn turn, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("turnIndex", turn.index);
        payload.put("stage", turn.failedStage);
        payload.put("code", turn.errorCode);
        payload.put("message", message == null ? "Speech processing could not finish." : message);
        if ("TRANSLATION".equals(turn.failedStage) && turn.transcript != null)
            payload.put("transcript", turn.transcript);
        if ("TRANSLATION".equals(turn.failedStage)) payload.put("sourceLanguage", turn.source);
        return Map.copyOf(payload);
    }

    private boolean stillActive(Turn turn) {
        synchronized (this) {
            if (turn.cancelled) return false;
        }
        try {
            RoomLiveAuthorizer.Member member = authorizer.authorize(turn.userId, turn.roomId);
            return member.status() == RoomStatus.ACTIVE && member.profile() == turn.profile
                    && member.languageVersion() == turn.languageVersion;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void requirePaidStage(Turn turn) {
        RoomLiveAuthorizer.Member member = authorizer.authorize(turn.userId, turn.roomId);
        if (member.status() != RoomStatus.ACTIVE || member.profile() != turn.profile
                || member.languageVersion() != turn.languageVersion) {
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
        RoomLiveAuthorizer.Member member = activeMember(userId, roomId);
        synchronized (this) {
            RoomQueue room = states.get(roomId);
            if (room == null) return List.of();
            List<Snapshot> result = new ArrayList<>();
            for (Turn turn : room.recent) {
                if (turn.languageVersion != member.languageVersion() && !turn.resultPublished) continue;
                if ((turn.workflow == Workflow.LIVE_SEND || turn.sent)
                        && !(turn.workflow == Workflow.LIVE_SEND && !turn.translationSucceeded
                        && !turn.userId.equals(userId)
                        && ("TRANSLATION".equals(turn.failedStage) || "TRANSLATING".equals(turn.status))))
                    result.add(snapshot(turn));
            }
            return result;
        }
    }

    public Review review(UUID userId, String sessionId, UUID roomId, UUID turnId) {
        RoomLiveAuthorizer.Member member = activeMember(userId, roomId);
        synchronized (this) {
            expireLocked(clock.instant());
            Turn turn = privateTurn(userId, sessionId, roomId, turnId);
            if ("EXPIRED".equals(turn.status) || "CANCELLED".equals(turn.status)) throw reviewGone();
            if (turn.languageVersion != member.languageVersion())
                throw conflict("The room direction changed. Record again.");
            return new Review(turn.id, turn.index, turn.status, turn.transcript, turn.translation,
                    turn.source, turn.target, turn.profile.name(), turn.reviewExpiresAt,
                    turn.failedStage, turn.errorCode);
        }
    }

    public Sent send(UUID userId, String sessionId, UUID roomId, UUID turnId) {
        RoomLiveAuthorizer.Member member = activeMember(userId, roomId);
        synchronized (this) {
            expireLocked(clock.instant());
            Turn turn = privateTurn(userId, sessionId, roomId, turnId);
            if (turn.sent) return new Sent(turn.id, turn.index, true, turn.profile.name());
            Sent[] result = new Sent[1];
            boolean current = authorizer.runIfActiveLanguageVersion(userId, roomId, member.participantId(),
                    member.languageVersion(), () -> {
            if (!"READY_FOR_REVIEW".equals(turn.status) || turn.reviewExpiresAt == null
                    || !clock.instant().isBefore(turn.reviewExpiresAt)) throw reviewGone();
            if (!hub.hasCurrentConnection(roomId, userId, sessionId))
                throw conflict("Reconnect to the live room before sending.");
            if (member.profile() != turn.profile || !member.speaking().equals(turn.source)
                    || !member.listening().equals(turn.target)
                    || member.languageVersion() != turn.languageVersion)
                throw conflict("The room changed. Record again.");
            if (!glossary.list(userId, roomId).equals(turn.glossaryVersion))
                throw conflict("The glossary changed. Record again.");
            providers.requirePaidStage(member.profile());
            turn.sent = true;
            turn.status = "TRANSLATED";
            sharedContext.finalized(roomId, turn.source, turn.target, turn.transcript);
            publishAccepted(turn);
            publishTurn(turn, "TRANSCRIPT_READY", Map.of("turnIndex", turn.index,
                    "transcript", turn.transcript, "sourceLanguage", turn.source), false);
            publishTurn(turn, "TRANSLATION_READY", Map.of("turnIndex", turn.index,
                    "translatedText", turn.translation, "targetLanguage", turn.target), false);
            metrics.duration(turn.profile, "caption_event", turn.receivedNanos);
            RoomQueue room = states.get(roomId);
            room.queue.add(new Work(turn, WorkKind.TTS));
            room.pending++;
            scheduleLocked(room, turn, true);
            result[0] = new Sent(turn.id, turn.index, false, turn.profile.name());
            });
            if (!current) throw conflict("The room changed. Record again.");
            return result[0];
        }
    }

    public void cancel(UUID userId, String sessionId, UUID roomId, UUID turnId) {
        activeMember(userId, roomId);
        synchronized (this) {
            Turn turn = privateTurn(userId, sessionId, roomId, turnId);
            if (turn.sent) throw conflict("This speech turn has already been sent.");
            cancelLocked(turn);
        }
    }

    public void revokeHttpSession(String sessionId) {
        if (sessionId == null) return;
        synchronized (this) {
            for (RoomQueue room : states.values())
                for (Turn turn : room.recent)
                    if (sessionId.equals(turn.sessionId) && !turn.sent) cancelLocked(turn);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordReset(PasswordResetCompletedEvent event) { revokeUser(event.userId()); }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountDeleted(AccountDeletedEvent event) { revokeUser(event.userId()); }

    private void revokeUser(UUID userId) {
        synchronized (this) {
            for (RoomQueue room : states.values())
                for (Turn turn : room.recent)
                    if (userId.equals(turn.userId) && !turn.sent) cancelLocked(turn);
        }
    }

    public Audio audio(UUID userId, UUID roomId, UUID turnId) {
        RoomLiveAuthorizer.Member member = activeMember(userId, roomId);
        Audio[] result = new Audio[1];
        boolean current;
        synchronized (this) {
            current = authorizer.runIfActiveLanguageVersion(userId, roomId, member.participantId(),
                    member.languageVersion(), () -> {
                RoomQueue room = states.get(roomId);
                if (room == null) return;
                for (Turn turn : room.recent) {
                    if (!turn.id.equals(turnId)) continue;
                    // Both current room participants may listen, but a turn from
                    // an earlier language generation is never exposed.
                    if (!turn.roomId.equals(roomId)) return;
                    if (turn.languageVersion != member.languageVersion()) {
                        discardAudio(turn);
                        return;
                    }
                    if (turn.generatedAudio != null && clock.instant().isBefore(turn.audioExpiresAt)) {
                        result[0] = new Audio(turn.generatedAudio.clone(), turn.mediaType);
                        return;
                    }
                }
            });
        }
        if (!current) throw conflict("The room changed. Audio is no longer available.");
        if (result[0] == null) throw notFound();
        return result[0];
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onClosed(RoomClosedEvent event) {
        synchronized (this) {
            RoomQueue room = states.remove(event.roomId());
            if (room == null) return;
            room.closed = true;
            for (Work work : room.queue) work.turn.rawAudio = null;
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
                    if (turn.workflow == Workflow.FINISH_AND_SEND && !turn.sent
                            && turn.reviewExpiresAt != null && !now.isBefore(turn.reviewExpiresAt))
                        expireReviewLocked(turn);
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
        return "READY".equals(turn.status) || "FAILED".equals(turn.status)
                || "EXPIRED".equals(turn.status) || "CANCELLED".equals(turn.status);
    }

    private void publishAccepted(Turn turn) {
        publishTurn(turn, "TURN_ACCEPTED", Map.of("turnIndex", turn.index,
                "sourceLanguage", turn.source, "targetLanguage", turn.target), false);
    }

    private boolean publishFinalResult(Turn turn, boolean includeTranscript) {
        return hub.runIfCurrentLanguageVersion(turn.roomId, turn.userId, turn.participantId,
                turn.languageVersion, () -> {
                    sharedContext.finalized(turn.roomId, turn.source, turn.target, turn.transcript);
                    hub.publishTurn(turn.roomId, turn.userId, turn.participantId, turn.id, "TURN_ACCEPTED",
                            Map.of("turnIndex", turn.index, "sourceLanguage", turn.source,
                                    "targetLanguage", turn.target));
                    if (includeTranscript) hub.publishTurn(turn.roomId, turn.userId, turn.participantId,
                            turn.id, "TRANSCRIPT_READY", Map.of("turnIndex", turn.index,
                                    "transcript", turn.transcript, "sourceLanguage", turn.source));
                    hub.publishTurn(turn.roomId, turn.userId, turn.participantId, turn.id,
                            "TRANSLATION_READY", Map.of("turnIndex", turn.index,
                                    "translatedText", turn.translation, "targetLanguage", turn.target));
                    turn.resultPublished = true;
                });
    }

    private void publishTurn(Turn turn, String type, Map<String, ?> payload, boolean senderOnly) {
        hub.publishTurnForLanguageVersion(turn.roomId, turn.userId, turn.participantId,
                turn.languageVersion, turn.id, type, payload, senderOnly);
    }

    private Turn privateTurn(UUID userId, String sessionId, UUID roomId, UUID turnId) {
        if (sessionId == null || sessionId.isBlank() || turnId == null) throw badRequest();
        RoomQueue room = states.get(roomId);
        if (room == null) throw reviewGone();
        for (Turn turn : room.recent) {
            if (turn.id.equals(turnId) && turn.workflow == Workflow.FINISH_AND_SEND
                    && turn.userId.equals(userId) && sessionId.equals(turn.sessionId)) return turn;
        }
        throw reviewGone();
    }

    private void cancelLocked(Turn turn) {
        turn.cancelled = true;
        turn.rawAudio = null;
        turn.transcript = null;
        turn.translation = null;
        turn.glossaryVersion = null;
        turn.status = "CANCELLED";
    }

    private void expireReviewLocked(Turn turn) {
        turn.cancelled = true;
        turn.transcript = null;
        turn.translation = null;
        turn.glossaryVersion = null;
        turn.status = "EXPIRED";
    }

    private void scheduleLocked(RoomQueue room, Turn turn, boolean tts) {
        if (room.processing) return;
        room.processing = true;
        try {
            workers.execute(() -> runRoom(room));
        } catch (RejectedExecutionException ex) {
            room.processing = false;
            room.queue.removeLast();
            room.pending--;
            turn.status = "FAILED";
            turn.failedStage = tts ? "TTS" : "PIPELINE";
            turn.errorCode = "CAPACITY";
            if (turn.workflow == Workflow.LIVE_SEND || turn.sent)
                publishTurn(turn, "TURN_FAILED",
                        failurePayload(turn, "The speech service is busy. Please try again later."), true);
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

    static Duration translationBudget(long remainingNanos, boolean reserveTts) {
        long reserved = STAGE_HANDOFF_RESERVE.toNanos()
                + (reserveTts ? TTS_TIMEOUT_RESERVE.toNanos() : 0);
        long available = remainingNanos - reserved;
        if (available < MINIMUM_TRANSLATION_BUDGET.toNanos()) throw ProviderFailure.localTimeout();
        return Duration.ofNanos(Math.min(available, TRANSLATION_CEILING.toNanos()));
    }

    private static void requireRemaining(long deadline, Duration required) {
        if (deadline - System.nanoTime() < required.toNanos()) throw ProviderFailure.localTimeout();
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

    private static ResponseStatusException reviewGone() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Private speech review unavailable");
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
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
    public record Review(UUID turnId, long turnIndex, String status, String transcript,
                         String translatedText, String sourceLanguage, String targetLanguage,
                         String profile, Instant expiresAt, String failedStage, String errorCode) {}
    public record Sent(UUID turnId, long turnIndex, boolean duplicate, String profile) {}
    public record RetryAccepted(UUID turnId, String status) {}
    public record Audio(byte[] bytes, String mediaType) {}
    public record Snapshot(UUID turnId, long turnIndex, UUID senderParticipantId,
                           String sourceLanguage, String targetLanguage, String status,
                           String transcript, String translatedText, boolean audioAvailable,
                           String failedStage, String errorCode) {}

    private static final class RoomQueue {
        final UUID roomId;
        final ArrayDeque<Work> queue = new ArrayDeque<>();
        final ArrayDeque<Turn> recent = new ArrayDeque<>();
        final LinkedHashMap<String, Turn> byRequest = new LinkedHashMap<>();
        long nextIndex;
        int pending;
        boolean processing;
        boolean closed;

        RoomQueue(UUID roomId) { this.roomId = roomId; }
    }

    public enum Workflow { LIVE_SEND, FINISH_AND_SEND }
    private enum WorkKind { PROCESS, RETRY_TRANSLATION, TTS }
    private record Work(Turn turn, WorkKind kind) {}

    private static final class Turn {
        final UUID id = UUID.randomUUID();
        final UUID roomId;
        final UUID userId;
        final UUID participantId;
        final String sessionId;
        final UUID clientRequestId;
        final long index;
        final String source;
        final String target;
        final AiServiceProfile profile;
        final boolean premiumCostSaverEnabled;
        final long languageVersion;
        final Workflow workflow;
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
        Instant reviewExpiresAt;
        List<RoomGlossaryService.EntryView> glossaryVersion;
        boolean sent;
        volatile boolean resultPublished;
        boolean cancelled;

        Turn(UUID roomId, UUID userId, UUID participantId, String sessionId, UUID clientRequestId,
             long index, String source, String target, AiServiceProfile profile,
             boolean premiumCostSaverEnabled, long languageVersion, Workflow workflow,
             byte[] rawAudio, Instant acceptedAt) {
            this.roomId = roomId;
            this.userId = userId;
            this.participantId = participantId;
            this.sessionId = sessionId;
            this.clientRequestId = clientRequestId;
            this.index = index;
            this.source = source;
            this.target = target;
            this.profile = profile;
            this.premiumCostSaverEnabled = premiumCostSaverEnabled;
            this.languageVersion = languageVersion;
            this.workflow = workflow;
            this.rawAudio = rawAudio;
            this.acceptedAt = acceptedAt;
        }
    }
}
