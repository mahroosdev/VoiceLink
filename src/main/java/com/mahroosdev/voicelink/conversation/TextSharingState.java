package com.mahroosdev.voicelink.conversation;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Bounded, session-owned authorization for provisional translated-text sharing. */
@Component
public class TextSharingState {
    static final int MAX_ACTIVE_SHARES = 64;
    static final long SHARE_SECONDS = 120;

    private final Map<Key, Share> shares = new HashMap<>();
    private final Clock clock;

    public TextSharingState() { this(Clock.systemUTC()); }

    TextSharingState(Clock clock) { this.clock = clock; }

    public synchronized SharingView start(UUID roomId, UUID userId, UUID participantId,
                                          String sessionId, Consumer<DraftClear> clear) {
        pruneLocked(clear);
        Key key = new Key(roomId, userId);
        Share previous = shares.remove(key);
        clear(previous, "REPLACED", clear);
        if (shares.size() >= MAX_ACTIVE_SHARES)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Shared Live is busy. Stop sharing elsewhere or try again later.");
        Instant expiresAt = clock.instant().plusSeconds(SHARE_SECONDS);
        Share share = new Share(roomId, userId, participantId, sessionId,
                UUID.randomUUID(), UUID.randomUUID(), expiresAt);
        shares.put(key, share);
        return new SharingView(share.epoch, expiresAt);
    }

    public synchronized Lease begin(UUID roomId, UUID userId, UUID participantId,
                                    String sessionId, UUID epoch, long revision,
                                    Consumer<DraftClear> clear) {
        pruneLocked(clear);
        Share share = require(roomId, userId, participantId, sessionId, epoch);
        if (revision <= share.latestRevision)
            throw conflict("A newer Shared Live revision already exists.");
        clearPublished(share, "REVISION_UPDATED", clear);
        share.latestRevision = revision;
        share.expiresAt = clock.instant().plusSeconds(SHARE_SECONDS);
        return new Lease(roomId, userId, participantId, sessionId, epoch,
                share.draftId, revision, share.expiresAt);
    }

    public synchronized boolean publish(Lease lease, String translatedText,
                                        String sourceLanguage, String targetLanguage,
                                        Function<DraftUpdate, Boolean> publisher) {
        Share share = shares.get(new Key(lease.roomId, lease.userId));
        if (!matches(share, lease.participantId, lease.sessionId, lease.epoch)
                || !clock.instant().isBefore(share.expiresAt)
                || share.latestRevision != lease.revision) return false;
        DraftUpdate update = new DraftUpdate(share.roomId, share.userId, share.participantId,
                share.sessionId, share.draftId, share.latestRevision, translatedText, sourceLanguage,
                targetLanguage, share.expiresAt);
        if (!Boolean.TRUE.equals(publisher.apply(update))) return false;
        share.published = true;
        share.publishedRevision = share.latestRevision;
        return true;
    }

    public synchronized void clearDraft(UUID roomId, UUID userId, UUID participantId,
                                        String sessionId, UUID epoch, long revision,
                                        Consumer<DraftClear> clear) {
        pruneLocked(clear);
        Share share = require(roomId, userId, participantId, sessionId, epoch);
        if (revision < share.latestRevision)
            throw conflict("A newer Shared Live revision already exists.");
        share.latestRevision = revision;
        share.expiresAt = clock.instant().plusSeconds(SHARE_SECONDS);
        clearPublished(share, "INPUT_CLEARED", clear);
    }

    public synchronized boolean invalidate(UUID roomId, UUID userId, UUID participantId,
                                           String sessionId, UUID epoch, long revision,
                                           Consumer<DraftClear> clear) {
        pruneLocked(clear);
        Share share = require(roomId, userId, participantId, sessionId, epoch);
        if (revision <= share.latestRevision) return false;
        clearPublished(share, "REVISION_UPDATED", clear);
        share.latestRevision = revision;
        share.expiresAt = clock.instant().plusSeconds(SHARE_SECONDS);
        return true;
    }

    public synchronized void stop(UUID roomId, UUID userId, UUID participantId,
                                  String sessionId, UUID epoch, Consumer<DraftClear> clear) {
        Key key = new Key(roomId, userId);
        Share share = require(roomId, userId, participantId, sessionId, epoch);
        shares.remove(key, share);
        clear(share, "SHARING_STOPPED", clear);
    }

    public synchronized void stopForPrivate(UUID roomId, UUID userId,
                                            Consumer<DraftClear> clear) {
        Share share = shares.remove(new Key(roomId, userId));
        clear(share, "PRIVATE_MODE", clear);
    }

    public synchronized boolean current(UUID roomId, UUID userId, UUID participantId,
                                        String sessionId, UUID epoch, long revision) {
        Share share = shares.get(new Key(roomId, userId));
        return matches(share, participantId, sessionId, epoch)
                && clock.instant().isBefore(share.expiresAt)
                && share.latestRevision == revision;
    }

    public synchronized boolean finalized(UUID roomId, UUID userId, UUID participantId,
                                          String sessionId, UUID epoch, long revision,
                                          Consumer<DraftClear> clear) {
        Share share = shares.get(new Key(roomId, userId));
        if (!matches(share, participantId, sessionId, epoch)
                || !clock.instant().isBefore(share.expiresAt)
                || share.latestRevision != revision) return false;
        clearPublished(share, "FINALIZED", clear);
        share.expiresAt = clock.instant().plusSeconds(SHARE_SECONDS);
        return true;
    }

    public synchronized void disconnect(UUID roomId, UUID userId, String sessionId,
                                        Consumer<DraftClear> clear) {
        Key key = new Key(roomId, userId);
        Share share = shares.get(key);
        if (share != null && share.sessionId.equals(sessionId)) {
            shares.remove(key);
            clear(share, "DISCONNECTED", clear);
        }
    }

    public synchronized void close(UUID roomId) {
        shares.keySet().removeIf(key -> key.roomId.equals(roomId));
    }

    public synchronized void clearRoom(UUID roomId, Consumer<DraftClear> clear) {
        Iterator<Map.Entry<Key, Share>> iterator = shares.entrySet().iterator();
        while (iterator.hasNext()) {
            Share share = iterator.next().getValue();
            if (share.roomId.equals(roomId)) {
                iterator.remove();
                clear(share, "LANGUAGES_CHANGED", clear);
            }
        }
    }

    public synchronized void prune(Consumer<DraftClear> clear) { pruneLocked(clear); }

    synchronized int size() { return shares.size(); }

    private void pruneLocked(Consumer<DraftClear> clear) {
        Instant now = clock.instant();
        Iterator<Share> iterator = shares.values().iterator();
        while (iterator.hasNext()) {
            Share share = iterator.next();
            if (!now.isBefore(share.expiresAt)) {
                iterator.remove();
                clear(share, "EXPIRED", clear);
            }
        }
    }

    private Share require(UUID roomId, UUID userId, UUID participantId,
                          String sessionId, UUID epoch) {
        Share share = shares.get(new Key(roomId, userId));
        if (!matches(share, participantId, sessionId, epoch))
            throw conflict("Shared Live authorization is unavailable. Enable sharing again.");
        return share;
    }

    private static boolean matches(Share share, UUID participantId, String sessionId, UUID epoch) {
        return share != null && sessionId != null && epoch != null
                && share.participantId.equals(participantId)
                && share.sessionId.equals(sessionId) && share.epoch.equals(epoch);
    }

    private static void clear(Share share, String reason, Consumer<DraftClear> consumer) {
        if (share != null && share.published)
            consumer.accept(new DraftClear(share.roomId, share.userId, share.participantId,
                    share.draftId, share.publishedRevision, reason));
    }

    private static void clearPublished(Share share, String reason, Consumer<DraftClear> consumer) {
        clear(share, reason, consumer);
        share.published = false;
        share.publishedRevision = -1;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record SharingView(UUID sharingEpoch, Instant expiresAt) {}
    public record Lease(UUID roomId, UUID userId, UUID participantId, String sessionId,
                        UUID epoch, UUID draftId, long revision, Instant expiresAt) {}
    public record DraftUpdate(UUID roomId, UUID userId, UUID participantId, String sessionId,
                              UUID draftId, long revision, String translatedText, String sourceLanguage,
                              String targetLanguage, Instant expiresAt) {}
    public record DraftClear(UUID roomId, UUID userId, UUID participantId, UUID draftId,
                             long revision, String reason) {}

    private record Key(UUID roomId, UUID userId) {}
    private static final class Share {
        final UUID roomId, userId, participantId, epoch, draftId;
        final String sessionId;
        Instant expiresAt;
        long latestRevision = -1;
        long publishedRevision = -1;
        boolean published;

        Share(UUID roomId, UUID userId, UUID participantId, String sessionId,
              UUID epoch, UUID draftId, Instant expiresAt) {
            this.roomId = roomId;
            this.userId = userId;
            this.participantId = participantId;
            this.sessionId = sessionId;
            this.epoch = epoch;
            this.draftId = draftId;
            this.expiresAt = expiresAt;
        }
    }
}
