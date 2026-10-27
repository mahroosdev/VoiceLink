package com.mahroosdev.voicelink.room;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mahroosdev.voicelink.user.UserAccount;
import com.mahroosdev.voicelink.ai.AiServiceProfile;
import com.mahroosdev.voicelink.ai.PremiumProviderSettings;
import com.mahroosdev.voicelink.user.UserAccountRepository;
import com.mahroosdev.voicelink.glossary.RoomGlossaryRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RoomService {
    private final ConversationRoomRepository rooms;
    private final RoomParticipantRepository participants;
    private final UserAccountRepository accounts;
    private final RoomAccessService access;
    private final SupportedRoomLanguages languages;
    private final ApplicationEventPublisher events;
    private final RoomGlossaryRepository glossary;
    private final PremiumProviderSettings premium;

    public RoomService(ConversationRoomRepository rooms, RoomParticipantRepository participants,
                       UserAccountRepository accounts, RoomAccessService access,
                       SupportedRoomLanguages languages, ApplicationEventPublisher events,
                       RoomGlossaryRepository glossary, PremiumProviderSettings premium) {
        this.rooms = rooms;
        this.participants = participants;
        this.accounts = accounts;
        this.access = access;
        this.languages = languages;
        this.events = events;
        this.glossary = glossary;
        this.premium = premium;
    }

    @Transactional
    public UUID createRoom(UUID userId, String speaking, String listening) {
        return createRoom(userId, speaking, listening, AiServiceProfile.STANDARD);
    }

    @Transactional
    public UUID createRoom(UUID userId, String speaking, String listening, AiServiceProfile profile) {
        return createRoom(userId, speaking, listening, profile, true);
    }

    @Transactional
    public UUID createRoom(UUID userId, String speaking, String listening, AiServiceProfile profile,
                           boolean premiumCostSaverEnabled) {
        return createRoom(userId, null, languages.validate(speaking, listening), profile,
                premiumCostSaverEnabled);
    }

    @Transactional
    public UUID createRoomForSpeaker(UUID userId, String displayName, String speaking,
                                     AiServiceProfile profile, boolean premiumCostSaverEnabled) {
        return createRoom(userId, ConversationDisplayName.normalize(displayName),
                languages.forSpeaker(speaking), profile, premiumCostSaverEnabled);
    }

    private UUID createRoom(UUID userId, String displayName, SupportedRoomLanguages.Direction direction,
                            AiServiceProfile profile, boolean premiumCostSaverEnabled) {
        if (profile == null) throw new IllegalArgumentException("Invalid profile");
        if (profile == AiServiceProfile.PREMIUM && !premium.available()) {
            throw new PremiumUnavailableException();
        }
        UserAccount creator = accounts.findById(userId).orElseThrow(this::notFound);
        Instant now = Instant.now();
        ConversationRoom room = rooms.saveAndFlush(new ConversationRoom(RoomCode.generate(), creator, now, profile,
                premiumCostSaverEnabled));
        participants.saveAndFlush(new RoomParticipant(room, creator, (short) 1,
                direction.speaking(), direction.listening(), displayName, now));
        return room.getId();
    }

    @Transactional
    public UUID joinRoom(UUID userId, String inputCode) {
        return joinRoom(userId, inputCode, null);
    }

    @Transactional
    public UUID joinRoomWithSetup(UUID userId, String inputCode, String displayName) {
        return joinRoom(userId, inputCode, ConversationDisplayName.normalize(displayName));
    }

    @Transactional
    public UUID joinRoomWithSetup(UUID userId, String inputCode, String displayName, String selectedSpeaking) {
        if (selectedSpeaking == null || selectedSpeaking.isBlank()) throw new JoinLanguageMismatchException();
        return joinRoom(userId, inputCode, ConversationDisplayName.normalize(displayName), selectedSpeaking);
    }

    private UUID joinRoom(UUID userId, String inputCode, String displayName) {
        return joinRoom(userId, inputCode, displayName, null);
    }

    private UUID joinRoom(UUID userId, String inputCode, String displayName, String selectedSpeaking) {
        String code = RoomCode.canonicalize(inputCode);
        if (code == null) throw new RoomInviteUnavailableException();
        ConversationRoom room = rooms.findByJoinCode(code).orElseThrow(RoomInviteUnavailableException::new);
        Instant now = Instant.now();
        if (participants.existsByRoom_IdAndUser_Id(room.getId(), userId)) {
            if (room.getStatus() == RoomStatus.ACTIVE || room.getStatus() == RoomStatus.WAITING
                    && now.isBefore(room.getInviteExpiresAt())) return room.getId();
            throw new RoomInviteUnavailableException();
        }
        if (room.getStatus() != RoomStatus.WAITING || !now.isBefore(room.getInviteExpiresAt())
                || participants.existsByRoom_IdAndParticipantSlot(room.getId(), (short) 2)) {
            throw new RoomInviteUnavailableException();
        }
        RoomParticipant creator = participants.findByRoom_IdOrderByParticipantSlotAsc(room.getId())
                .stream().filter(p -> p.getParticipantSlot() == 1).findFirst()
                .orElseThrow(RoomInviteUnavailableException::new);
        var direction = languages.validate(creator.getSpeakingLanguageTag(),
                creator.getListeningLanguageTag()).reverse();
        if (selectedSpeaking != null) {
            try {
                if (!direction.speaking().equals(languages.forSpeaker(selectedSpeaking).speaking()))
                    throw new JoinLanguageMismatchException();
            } catch (IllegalArgumentException ex) {
                throw new JoinLanguageMismatchException();
            }
        }
        UserAccount joiner = accounts.findById(userId).orElseThrow(this::notFound);
        participants.saveAndFlush(new RoomParticipant(room, joiner, (short) 2,
                direction.speaking(), direction.listening(), displayName, now));
        room.activate(now);
        events.publishEvent(new RoomActivatedEvent(room.getId()));
        return room.getId();
    }

    @Transactional
    public JoinPreview previewJoin(UUID userId, String inputCode) {
        String code = RoomCode.canonicalize(inputCode);
        if (code == null) throw new RoomInviteUnavailableException();
        ConversationRoom room = rooms.findByJoinCode(code).orElseThrow(RoomInviteUnavailableException::new);
        Instant now = Instant.now();
        if (participants.existsByRoom_IdAndUser_Id(room.getId(), userId)) {
            if (room.getStatus() == RoomStatus.ACTIVE || room.getStatus() == RoomStatus.WAITING
                    && now.isBefore(room.getInviteExpiresAt())) {
                return new JoinPreview(room.getId(), RoomCode.display(code), null, null, null, true);
            }
            throw new RoomInviteUnavailableException();
        }
        if (room.getStatus() != RoomStatus.WAITING || !now.isBefore(room.getInviteExpiresAt())
                || participants.existsByRoom_IdAndParticipantSlot(room.getId(), (short) 2)) {
            throw new RoomInviteUnavailableException();
        }
        RoomParticipant creator = participants.findByRoom_IdOrderByParticipantSlotAsc(room.getId())
                .stream().filter(p -> p.getUser().getId().equals(room.getCreatedBy().getId()))
                .findFirst().orElseThrow(RoomInviteUnavailableException::new);
        var ownerDirection = languages.validate(creator.getSpeakingLanguageTag(), creator.getListeningLanguageTag());
        return new JoinPreview(room.getId(), RoomCode.display(code), ConversationDisplayName.resolve(creator),
                ownerDirection.speaking(), ownerDirection.listening(), false);
    }

    @Transactional(readOnly = true)
    public List<RoomSummary> listRoomsForMember(UUID userId) {
        Instant now = Instant.now();
        return participants.findByUser_IdAndRemovedAtIsNullOrderByJoinedAtDesc(userId).stream()
                .map(p -> new RoomSummary(p.getRoom().getId(), p.getRoom().getStatus(),
                        p.getRoom().getServiceProfile(), p.getRoom().isPremiumCostSaverEnabled(),
                        p.getSpeakingLanguageTag(),
                        p.getListeningLanguageTag(),
                        p.getRoom().getCreatedAt(), p.getRoom().getStatus() == RoomStatus.WAITING
                                && !now.isBefore(p.getRoom().getInviteExpiresAt())))
                .toList();
    }

    @Transactional(readOnly = true)
    public RoomView getRoomForMember(UUID userId, UUID roomId) {
        access.requireMember(userId, roomId);
        ConversationRoom room = rooms.findById(roomId).orElseThrow(this::notFound);
        List<ParticipantView> members = participants.findByRoom_IdOrderByParticipantSlotAsc(roomId).stream()
                .map(p -> new ParticipantView(p.getId(), ConversationDisplayName.resolve(p), p.getParticipantSlot(),
                        p.getSpeakingLanguageTag(), p.getListeningLanguageTag(),
                        p.getUser().getId().equals(userId),
                        p.getUser().getId().equals(room.getCreatedBy().getId())))
                .toList();
        ParticipantView currentParticipant = members.stream().filter(ParticipantView::current)
                .findFirst().orElseThrow(this::notFound);
        boolean creator = room.getCreatedBy().getId().equals(userId);
        boolean expired = room.getStatus() == RoomStatus.WAITING
                && !Instant.now().isBefore(room.getInviteExpiresAt());
        String inviteCode = creator && room.getStatus() == RoomStatus.WAITING && !expired
                ? RoomCode.display(room.getJoinCode()) : null;
        String ownerSpeaking = members.stream().filter(ParticipantView::owner)
                .map(ParticipantView::speaking).findFirst().orElse("en");
        return new RoomView(roomId, room.getStatus(), room.getServiceProfile(),
                room.isPremiumCostSaverEnabled(), members, currentParticipant, creator, expired,
                room.getInviteExpiresAt(), inviteCode, ownerSpeaking);
    }

    @Transactional
    public void updateLanguages(UUID userId, UUID roomId, String ownerSpeaking) {
        ConversationRoom room = rooms.findLockedById(roomId).orElseThrow(this::notFound);
        if (!room.getCreatedBy().getId().equals(userId)) throw notFound();
        if (room.getStatus() == RoomStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Closed rooms cannot change languages.");
        }
        var ownerDirection = languages.forSpeaker(ownerSpeaking);
        var people = participants.findByRoom_IdOrderByParticipantSlotAsc(roomId);
        RoomParticipant owner = people.stream().filter(p -> p.getParticipantSlot() == 1).findFirst()
                .orElseThrow(this::notFound);
        RoomParticipant partner = people.stream().filter(p -> p.getParticipantSlot() == 2).findFirst().orElse(null);
        owner.changeDirection(ownerDirection);
        if (partner != null) partner.changeDirection(ownerDirection.reverse());
        room.changeLanguages(Instant.now());
        participants.saveAll(people);
        events.publishEvent(new RoomLanguagesChangedEvent(roomId));
    }

    @Transactional
    public void closeRoom(UUID userId, UUID roomId) {
        ConversationRoom room = rooms.findLockedById(roomId).orElseThrow(this::notFound);
        access.requireMember(userId, roomId);
        if (room.getStatus() == RoomStatus.CLOSED) return;
        if (room.getStatus() == RoomStatus.WAITING && !room.getCreatedBy().getId().equals(userId)) {
            throw notFound();
        }
        room.close(Instant.now());
        glossary.deleteByRoom_Id(roomId);
        events.publishEvent(new RoomClosedEvent(roomId));
    }

    @Transactional
    public void removeClosedRoom(UUID userId, UUID roomId) {
        ConversationRoom room = rooms.findLockedById(roomId).orElseThrow(this::notFound);
        access.requireMember(userId, roomId);
        if (room.getStatus() != RoomStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only closed rooms can be removed");
        }
        RoomParticipant membership = participants.findByRoom_IdAndUser_IdAndRemovedAtIsNull(roomId, userId)
                .orElseThrow(this::notFound);
        membership.removeFromList(Instant.now());
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found");
    }

    public record RoomSummary(UUID id, RoomStatus status, AiServiceProfile profile,
                              boolean premiumCostSaverEnabled,
                              String speaking, String listening,
                              Instant createdAt, boolean inviteExpired) {}
    public record ParticipantView(UUID participantId, String displayName, short slot,
                                  String speaking, String listening, boolean current, boolean owner) {}
    public record JoinPreview(UUID roomId, String joinCode, String ownerDisplayName,
                              String ownerSpeaking, String participantSpeaking,
                              boolean alreadyMember) {}
    public record RoomView(UUID id, RoomStatus status, AiServiceProfile profile,
                           boolean premiumCostSaverEnabled,
                           List<ParticipantView> participants,
                           ParticipantView currentParticipant,
                           boolean creator, boolean inviteExpired, Instant inviteExpiresAt, String inviteCode,
                           String ownerSpeaking) {}
}
