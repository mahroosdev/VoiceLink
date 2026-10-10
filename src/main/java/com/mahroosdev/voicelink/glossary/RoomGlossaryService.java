package com.mahroosdev.voicelink.glossary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mahroosdev.voicelink.ai.translation.TranslationProvider;
import com.mahroosdev.voicelink.room.ConversationRoom;
import com.mahroosdev.voicelink.room.ConversationRoomRepository;
import com.mahroosdev.voicelink.room.RoomAccessService;
import com.mahroosdev.voicelink.room.RoomParticipantRepository;
import com.mahroosdev.voicelink.room.RoomStatus;
import com.mahroosdev.voicelink.room.SupportedRoomLanguages;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RoomGlossaryService {
    private final RoomGlossaryRepository entries;
    private final ConversationRoomRepository rooms;
    private final RoomParticipantRepository participants;
    private final RoomAccessService access;
    private final UserAccountRepository accounts;
    private final SupportedRoomLanguages languages;

    public RoomGlossaryService(RoomGlossaryRepository entries, ConversationRoomRepository rooms,
                               RoomParticipantRepository participants, RoomAccessService access,
                               UserAccountRepository accounts, SupportedRoomLanguages languages) {
        this.entries = entries;
        this.rooms = rooms;
        this.participants = participants;
        this.access = access;
        this.accounts = accounts;
        this.languages = languages;
    }

    @Transactional(readOnly = true)
    public List<EntryView> list(UUID userId, UUID roomId) {
        access.requireMember(userId, roomId);
        requireOpen(rooms.findById(roomId).orElseThrow(RoomGlossaryService::notFound));
        return entries.findByRoom_IdOrderByCreatedAtAsc(roomId).stream().map(RoomGlossaryService::view).toList();
    }

    @Transactional
    public EntryView create(UUID userId, UUID roomId, Change change) {
        ConversationRoom room = lockedWritable(userId, roomId);
        Terms terms = validate(roomId, change);
        if (entries.countByRoom_Id(roomId) >= 12) throw conflict("Room glossary is full");
        if (duplicate(roomId, terms, null)) throw conflict("Glossary term already exists");
        RoomGlossaryEntry entry = new RoomGlossaryEntry(room, accounts.getReferenceById(userId),
                terms.sourceLanguage, terms.targetLanguage, terms.source, terms.lookup,
                terms.preferred, Instant.now());
        return view(entries.saveAndFlush(entry));
    }

    @Transactional
    public EntryView update(UUID userId, UUID roomId, UUID entryId, Change change, long expectedVersion) {
        lockedWritable(userId, roomId);
        RoomGlossaryEntry entry = entries.findByRoom_IdAndId(roomId, entryId).orElseThrow(RoomGlossaryService::notFound);
        if (expectedVersion < 0 || entry.getRowVersion() != expectedVersion) throw conflict("Glossary entry changed; refresh and retry");
        Terms terms = validate(roomId, change);
        if (duplicate(roomId, terms, entryId)) throw conflict("Glossary term already exists");
        entry.update(terms.sourceLanguage, terms.targetLanguage, terms.source, terms.lookup,
                terms.preferred, Instant.now());
        return view(entries.saveAndFlush(entry));
    }

    @Transactional
    public void delete(UUID userId, UUID roomId, UUID entryId, long expectedVersion) {
        lockedWritable(userId, roomId);
        RoomGlossaryEntry entry = entries.findByRoom_IdAndId(roomId, entryId).orElseThrow(RoomGlossaryService::notFound);
        if (expectedVersion < 0 || entry.getRowVersion() != expectedVersion) throw conflict("Glossary entry changed; refresh and retry");
        entries.delete(entry);
        entries.flush();
    }

    @Transactional(readOnly = true)
    public List<TranslationProvider.GlossaryTerm> applicableForTranslation(
            UUID roomId, String source, String target, String utterance) {
        ConversationRoom room = rooms.findById(roomId).orElseThrow(RoomGlossaryService::notFound);
        if (room.getStatus() != RoomStatus.ACTIVE) return List.of();
        return GlossaryTerms.applicable(entries.findByRoom_IdOrderByCreatedAtAsc(roomId),
                source, target, utterance);
    }

    private ConversationRoom lockedWritable(UUID userId, UUID roomId) {
        ConversationRoom room = rooms.findLockedById(roomId).orElseThrow(RoomGlossaryService::notFound);
        access.requireMember(userId, roomId);
        requireOpen(room);
        if (room.getStatus() == RoomStatus.WAITING && (!room.getCreatedBy().getId().equals(userId)
                || !Instant.now().isBefore(room.getInviteExpiresAt()))) {
            throw conflict("Room invite has expired");
        }
        return room;
    }

    private static void requireOpen(ConversationRoom room) {
        if (room.getStatus() == RoomStatus.CLOSED) throw conflict("Room is closed");
    }

    private Terms validate(UUID roomId, Change change) {
        if (change == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid glossary entry");
        SupportedRoomLanguages.Direction direction;
        try {
            direction = languages.validate(change.sourceLanguage, change.targetLanguage);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid room language direction");
        }
        var creator = participants.findByRoom_IdOrderByParticipantSlotAsc(roomId).stream()
                .filter(person -> person.getParticipantSlot() == 1).findFirst().orElseThrow(RoomGlossaryService::notFound);
        String speaking = creator.getSpeakingLanguageTag(), listening = creator.getListeningLanguageTag();
        if (!(direction.speaking().equals(speaking) && direction.listening().equals(listening)
                || direction.speaking().equals(listening) && direction.listening().equals(speaking))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid room language direction");
        }
        String source = GlossaryTerms.clean(change.sourceTerm, 48, false);
        String preferred = GlossaryTerms.clean(change.preferredTerm == null ? "" : change.preferredTerm, 64, true);
        if (preferred.isEmpty()) preferred = source;
        return new Terms(direction.speaking(), direction.listening(), source,
                GlossaryTerms.lookup(source), preferred);
    }

    private boolean duplicate(UUID roomId, Terms terms, UUID currentId) {
        return entries.findByRoom_IdAndSourceLanguageAndTargetLanguageAndNormalizedSourceTerm(
                roomId, terms.sourceLanguage, terms.targetLanguage, terms.lookup)
                .filter(entry -> !entry.getId().equals(currentId)).isPresent();
    }

    private static EntryView view(RoomGlossaryEntry entry) {
        return new EntryView(entry.getId(), entry.getSourceLanguage(), entry.getTargetLanguage(),
                entry.getSourceTerm(), entry.getPreferredTerm(), entry.getRowVersion());
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Room or glossary entry not found");
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record Terms(String sourceLanguage, String targetLanguage, String source,
                         String lookup, String preferred) {}
    public record Change(String sourceLanguage, String targetLanguage, String sourceTerm, String preferredTerm) {}
    public record EntryView(UUID id, String sourceLanguage, String targetLanguage,
                            String sourceTerm, String preferredTerm, long rowVersion) {}
}
