package com.mahroosdev.voicelink.conversation;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mahroosdev.voicelink.ai.AiProviderResolver;
import com.mahroosdev.voicelink.ai.AiServiceProfile;
import com.mahroosdev.voicelink.ai.ProviderFailure;
import com.mahroosdev.voicelink.ai.translation.TranslationProvider;
import com.mahroosdev.voicelink.glossary.RoomGlossaryService;
import com.mahroosdev.voicelink.room.RoomClosedEvent;
import com.mahroosdev.voicelink.room.RoomLiveAuthorizer;
import com.mahroosdev.voicelink.room.RoomLiveHub;
import com.mahroosdev.voicelink.room.RoomStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

/** Private, bounded reviews; only explicit finalization publishes a text turn. */
@Service
public class TextTranslationService {
    private static final int MAX_TEXT_CODE_POINTS = 1000;
    private static final int MAX_ROOMS = 32;
    private static final int MAX_IN_FLIGHT = 16;
    private static final int MAX_IN_FLIGHT_PER_USER = 2;
    private static final long REVIEW_SECONDS = 120;

    private final Map<UUID, RoomReviews> rooms = new HashMap<>();
    private final RoomLiveAuthorizer authorizer;
    private final RoomGlossaryService glossary;
    private final AiProviderResolver providers;
    private final RoomLiveHub hub;
    private final SharedConversationContext context;
    private final TextTranslationRateLimiter limiter;
    private final Clock clock;
    private int inFlight;

    @Autowired
    public TextTranslationService(RoomLiveAuthorizer authorizer, RoomGlossaryService glossary,
                                  AiProviderResolver providers, RoomLiveHub hub,
                                  SharedConversationContext context, TextTranslationRateLimiter limiter) {
        this(authorizer, glossary, providers, hub, context, limiter, Clock.systemUTC());
    }

    TextTranslationService(RoomLiveAuthorizer authorizer, RoomGlossaryService glossary,
                           AiProviderResolver providers, RoomLiveHub hub,
                           SharedConversationContext context, TextTranslationRateLimiter limiter, Clock clock) {
        this.authorizer = authorizer;
        this.glossary = glossary;
        this.providers = providers;
        this.hub = hub;
        this.context = context;
        this.limiter = limiter;
        this.clock = clock;
    }

    public ReviewView review(UUID userId, String sessionId, UUID roomId, String sourceText, long revision) {
        validText(sourceText);
        if (revision < 0 || sessionId == null || sessionId.isBlank()) throw badRequest();
        RoomLiveAuthorizer.Member member = active(userId, roomId);
        requireProviderAvailable(member.profile());
        RoomReviews room;
        UserReviews user;
        long generation;
        synchronized (this) {
            pruneLocked();
            room = rooms.get(roomId);
            if (room == null) {
                if (rooms.size() >= MAX_ROOMS) throw busy();
                room = new RoomReviews();
                rooms.put(roomId, room);
            }
            user = room.users.computeIfAbsent(userId, ignored -> new UserReviews());
            if (user.inFlight >= MAX_IN_FLIGHT_PER_USER || inFlight >= MAX_IN_FLIGHT) throw busy();
            if (!limiter.tryAcquire(userId)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many text translations. Please wait and try again.");
            generation = ++user.generation;
            user.review = null;
            user.inFlight++;
            inFlight++;
        }
        try {
            List<RoomGlossaryService.EntryView> glossaryVersion = glossary.list(userId, roomId);
            List<TranslationProvider.GlossaryTerm> terms = glossary.applicableForTranslation(
                    roomId, member.speaking(), member.listening(), sourceText);
            List<TranslationProvider.ContextTurn> prior = context.snapshot(roomId);
            TranslationProvider.Result result = providers.resolve(member.profile()).translation().translate(
                    new TranslationProvider.TranslationRequest(sourceText, member.speaking(),
                            member.listening(), prior, terms));
            String translated = result.translatedText();
            if (translated == null || translated.isBlank()
                    || translated.codePointCount(0, translated.length()) > 6000)
                throw new ProviderFailure(ProviderFailure.Code.INVALID_RESPONSE);
            RoomLiveAuthorizer.Member current = active(userId, roomId);
            if (!current.equals(member) || !glossary.list(userId, roomId).equals(glossaryVersion))
                throw conflict("The room or glossary changed. Translate again.");
            synchronized (this) {
                if (rooms.get(roomId) != room || room.users.get(userId) != user
                        || user.generation != generation) throw conflict("A newer translation is available. Review that result.");
                Instant now = clock.instant();
                Review review = new Review(UUID.randomUUID(), UUID.randomUUID(), sessionId, sourceText,
                        translated, member.speaking(), member.listening(), member.profile(),
                        revision, glossaryVersion, now, now.plusSeconds(REVIEW_SECONDS));
                user.review = review;
                return view(review);
            }
        } catch (ProviderFailure failure) {
            HttpStatus status = failure.code() == ProviderFailure.Code.RATE_LIMIT
                    || failure.code() == ProviderFailure.Code.QUOTA ? HttpStatus.TOO_MANY_REQUESTS
                    : failure.code() == ProviderFailure.Code.TIMEOUT ? HttpStatus.GATEWAY_TIMEOUT
                    : HttpStatus.SERVICE_UNAVAILABLE;
            throw new ResponseStatusException(status, switch (failure.code()) {
                case QUOTA -> "Translation quota is unavailable. Try again later.";
                case RATE_LIMIT -> "Translation is busy. Please try again later.";
                case TIMEOUT -> "Translation timed out. Please try again.";
                case CONFIGURATION -> "Translation is not configured for this room.";
                default -> "Translation is temporarily unavailable. Please try again.";
            });
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Translation is temporarily unavailable. Please try again.");
        } finally {
            synchronized (this) {
                user.inFlight--;
                inFlight--;
            }
        }
    }

    public SentView send(UUID userId, String sessionId, UUID roomId, UUID reviewId,
                         String sourceText, long revision) {
        if (reviewId == null || sessionId == null || revision < 0) throw badRequest();
        RoomLiveAuthorizer.Member member = active(userId, roomId);
        synchronized (this) {
            RoomReviews room = rooms.get(roomId);
            UserReviews user = room == null ? null : room.users.get(userId);
            Review review = user == null ? null : user.review;
            if (review == null || !review.id.equals(reviewId) || !review.sessionId.equals(sessionId))
                throw conflict("The private translation is unavailable. Translate again.");
            if (!clock.instant().isBefore(review.expiresAt)) {
                user.review = null;
                throw conflict("The private translation expired. Translate again.");
            }
            if (!review.source.equals(sourceText) || review.revision != revision)
                throw conflict("The source text changed. Translate again.");
            if (member.profile() != review.profile || !member.speaking().equals(review.sourceLanguage)
                    || !member.listening().equals(review.targetLanguage))
                throw conflict("The room direction changed. Translate again.");
            if (review.sent) return sent(review, true);
            if (!glossary.list(userId, roomId).equals(review.glossaryVersion)) {
                user.review = null;
                throw conflict("The glossary changed. Translate again.");
            }
            requireProviderAvailable(member.profile());
            review.sent = true;
            context.finalized(roomId, review.sourceLanguage, review.targetLanguage, review.source);
            hub.publishTurn(roomId, userId, member.participantId(), review.finalId,
                    "TEXT_TRANSLATION_SENT", Map.of("originalText", review.source,
                            "translatedText", review.translation, "sourceLanguage", review.sourceLanguage,
                            "targetLanguage", review.targetLanguage, "profile", review.profile.name()));
            return sent(review, false);
        }
    }

    private static SentView sent(Review review, boolean duplicate) {
        return new SentView(review.finalId, review.source, review.translation, review.sourceLanguage,
                review.targetLanguage, review.profile.name(), duplicate);
    }

    private static ReviewView view(Review review) {
        return new ReviewView(review.id, review.source, review.translation, review.sourceLanguage,
                review.targetLanguage, review.profile.name(), review.revision, review.expiresAt);
    }

    private RoomLiveAuthorizer.Member active(UUID userId, UUID roomId) {
        RoomLiveAuthorizer.Member member = authorizer.authorize(userId, roomId);
        if (member == null || member.status() != RoomStatus.ACTIVE) throw conflict("Room is not active.");
        return member;
    }

    private void requireProviderAvailable(AiServiceProfile profile) {
        try {
            providers.requirePaidStage(profile);
        } catch (ProviderFailure failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Translation is not configured for this room.");
        }
    }

    private static void validText(String text) {
        if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > MAX_TEXT_CODE_POINTS)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Text must contain 1 to 1,000 characters.");
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (Character.isHighSurrogate(character)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) throw badRequest();
                i++;
            } else if (Character.isLowSurrogate(character) || Character.isISOControl(character)
                    && character != '\n' && character != '\t' && character != '\r') throw badRequest();
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public synchronized void onClosed(RoomClosedEvent event) { rooms.remove(event.roomId()); }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void prune() { pruneLocked(); }

    private void pruneLocked() {
        Instant now = clock.instant();
        rooms.values().forEach(room -> room.users.values().removeIf(user -> {
            if (user.review != null && !now.isBefore(user.review.expiresAt)) user.review = null;
            return user.inFlight == 0 && user.review == null;
        }));
        rooms.values().removeIf(room -> room.users.isEmpty());
    }

    private static ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid text translation request.");
    }
    private static ResponseStatusException busy() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Text translation is busy. Please wait and try again.");
    }
    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record ReviewView(UUID reviewId, String originalText, String translatedText,
                             String sourceLanguage, String targetLanguage, String profile,
                             long revision, Instant expiresAt) {}
    public record SentView(UUID finalId, String originalText, String translatedText,
                           String sourceLanguage, String targetLanguage, String profile, boolean duplicate) {}
    private static final class RoomReviews { final Map<UUID, UserReviews> users = new HashMap<>(); }
    private static final class UserReviews { long generation; int inFlight; Review review; }
    private static final class Review {
        final UUID id, finalId;
        final String sessionId, source, translation, sourceLanguage, targetLanguage;
        final AiServiceProfile profile;
        final long revision;
        final List<RoomGlossaryService.EntryView> glossaryVersion;
        final Instant createdAt, expiresAt;
        boolean sent;
        Review(UUID id, UUID finalId, String sessionId, String source, String translation,
               String sourceLanguage, String targetLanguage, AiServiceProfile profile, long revision,
               List<RoomGlossaryService.EntryView> glossaryVersion, Instant createdAt, Instant expiresAt) {
            this.id = id; this.finalId = finalId; this.sessionId = sessionId; this.source = source;
            this.translation = translation; this.sourceLanguage = sourceLanguage;
            this.targetLanguage = targetLanguage; this.profile = profile; this.revision = revision;
            this.glossaryVersion = List.copyOf(glossaryVersion);
            this.createdAt = createdAt; this.expiresAt = expiresAt;
        }
    }
}
