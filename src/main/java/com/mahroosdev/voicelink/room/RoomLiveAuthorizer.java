package com.mahroosdev.voicelink.room;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RoomLiveAuthorizer {
    private final RoomAccessService access;
    private final ConversationRoomRepository rooms;
    private final RoomParticipantRepository participants;

    public RoomLiveAuthorizer(RoomAccessService access, ConversationRoomRepository rooms,
                              RoomParticipantRepository participants) {
        this.access = access;
        this.rooms = rooms;
        this.participants = participants;
    }

    @Transactional(readOnly = true)
    public Member authorize(UUID userId, UUID roomId) {
        access.requireMember(userId, roomId);
        RoomStatus status = rooms.findById(roomId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found")).getStatus();
        RoomParticipant participant = participants.findByRoom_IdOrderByParticipantSlotAsc(roomId).stream()
                .filter(p -> p.getUser().getId().equals(userId))
                .findFirst().orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        return new Member(participant.getId(), status, participant.getSpeakingLanguageTag(),
                participant.getListeningLanguageTag());
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(UUID userId, UUID roomId) {
        Member member = authorize(userId, roomId);
        List<Participant> people = participants.findByRoom_IdOrderByParticipantSlotAsc(roomId).stream()
                .map(p -> new Participant(p.getId(), p.getUser().getDisplayName(),
                        p.getParticipantSlot(), p.getSpeakingLanguageTag(),
                        p.getListeningLanguageTag()))
                .toList();
        return new Snapshot(member.status(), people);
    }

    public record Member(UUID participantId, RoomStatus status, String speaking, String listening) {}
    public record Participant(UUID participantId, String displayName, short slot,
                              String speaking, String listening) {}
    public record Snapshot(RoomStatus status, List<Participant> participants) {}
}
