package com.mahroosdev.voicelink.room;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mahroosdev.voicelink.user.UserAccount;
import com.mahroosdev.voicelink.user.UserAccountRepository;
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

    public RoomService(ConversationRoomRepository rooms, RoomParticipantRepository participants,
                       UserAccountRepository accounts, RoomAccessService access,
                       SupportedRoomLanguages languages, ApplicationEventPublisher events) {
        this.rooms = rooms;
        this.participants = participants;
        this.accounts = accounts;
        this.access = access;
        this.languages = languages;
        this.events = events;
    }

    @Transactional
    public UUID createRoom(UUID userId, String speaking, String listening) {
        var direction = languages.validate(speaking, listening);
        UserAccount creator = accounts.findById(userId).orElseThrow(this::notFound);
        Instant now = Instant.now();
        ConversationRoom room = rooms.saveAndFlush(new ConversationRoom(RoomCode.generate(), creator, now));
        participants.saveAndFlush(new RoomParticipant(room, creator, (short) 1,
                direction.speaking(), direction.listening(), now));
        return room.getId();
    }

    @Transactional
    public UUID joinRoom(UUID userId, String inputCode) {
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
        UserAccount joiner = accounts.findById(userId).orElseThrow(this::notFound);
        participants.saveAndFlush(new RoomParticipant(room, joiner, (short) 2,
                direction.speaking(), direction.listening(), now));
        room.activate(now);
        events.publishEvent(new RoomActivatedEvent(room.getId()));
        return room.getId();
    }

    @Transactional(readOnly = true)
    public List<RoomSummary> listRoomsForMember(UUID userId) {
        Instant now = Instant.now();
        return participants.findByUser_IdOrderByJoinedAtDesc(userId).stream()
                .map(p -> new RoomSummary(p.getRoom().getId(), p.getRoom().getStatus(),
                        p.getRoom().getCreatedAt(), p.getRoom().getStatus() == RoomStatus.WAITING
                                && !now.isBefore(p.getRoom().getInviteExpiresAt())))
                .toList();
    }

    @Transactional(readOnly = true)
    public RoomView getRoomForMember(UUID userId, UUID roomId) {
        access.requireMember(userId, roomId);
        ConversationRoom room = rooms.findById(roomId).orElseThrow(this::notFound);
        List<ParticipantView> members = participants.findByRoom_IdOrderByParticipantSlotAsc(roomId).stream()
                .map(p -> new ParticipantView(p.getUser().getDisplayName(), p.getParticipantSlot(),
                        p.getSpeakingLanguageTag(), p.getListeningLanguageTag()))
                .toList();
        boolean creator = room.getCreatedBy().getId().equals(userId);
        boolean expired = room.getStatus() == RoomStatus.WAITING
                && !Instant.now().isBefore(room.getInviteExpiresAt());
        String inviteCode = creator && room.getStatus() == RoomStatus.WAITING && !expired
                ? RoomCode.display(room.getJoinCode()) : null;
        return new RoomView(roomId, room.getStatus(), members, creator, expired,
                room.getInviteExpiresAt(), inviteCode);
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
        events.publishEvent(new RoomClosedEvent(roomId));
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found");
    }

    public record RoomSummary(UUID id, RoomStatus status, Instant createdAt, boolean inviteExpired) {}
    public record ParticipantView(String displayName, short slot, String speaking, String listening) {}
    public record RoomView(UUID id, RoomStatus status, List<ParticipantView> participants,
                           boolean creator, boolean inviteExpired, Instant inviteExpiresAt, String inviteCode) {}
}
