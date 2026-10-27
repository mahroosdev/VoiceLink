package com.mahroosdev.voicelink.room;

import java.util.List;
import java.util.UUID;
import com.mahroosdev.voicelink.ai.AiServiceProfile;

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
        ConversationRoom room = rooms.findById(roomId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        RoomParticipant participant = participants.findByRoom_IdOrderByParticipantSlotAsc(roomId).stream()
                .filter(p -> p.getUser().getId().equals(userId))
                .findFirst().orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        return new Member(participant.getId(), room.getStatus(), participant.getSpeakingLanguageTag(),
                participant.getListeningLanguageTag(), room.getServiceProfile(),
                room.isPremiumCostSaverEnabled(), room.getLanguageVersion());
    }

    /**
     * Runs a final room operation while holding the same database row lock used by
     * language changes and closure. The callback must be short and must not call an
     * external provider. This fences operations across application instances.
     */
    @Transactional
    public boolean runIfActiveLanguageVersion(UUID userId, UUID roomId, UUID participantId,
                                              long expectedVersion, Runnable operation) {
        ConversationRoom room = rooms.findLockedById(roomId).orElse(null);
        if (room == null || room.getStatus() != RoomStatus.ACTIVE
                || room.getLanguageVersion() != expectedVersion) return false;
        RoomParticipant participant = participants.findByRoom_IdAndUser_IdAndRemovedAtIsNull(roomId, userId)
                .orElse(null);
        if (participant == null || !participant.getId().equals(participantId)) return false;
        operation.run();
        return true;
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(UUID userId, UUID roomId) {
        Member member = authorize(userId, roomId);
        ConversationRoom room = rooms.findById(roomId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        List<Participant> people = participants.findByRoom_IdOrderByParticipantSlotAsc(roomId).stream()
                .map(p -> new Participant(p.getId(), ConversationDisplayName.resolve(p),
                        p.getParticipantSlot(), p.getSpeakingLanguageTag(),
                        p.getListeningLanguageTag(), p.getUser().getId().equals(userId),
                        p.getUser().getId().equals(room.getCreatedBy().getId())))
                .toList();
        return new Snapshot(member.status(), people);
    }

    public record Member(UUID participantId, RoomStatus status, String speaking, String listening,
                         AiServiceProfile profile, boolean premiumCostSaverEnabled, long languageVersion) {
        public Member(UUID participantId, RoomStatus status, String speaking, String listening,
                      AiServiceProfile profile) {
            this(participantId, status, speaking, listening, profile, true, 0);
        }
        public Member(UUID participantId, RoomStatus status, String speaking, String listening,
                      AiServiceProfile profile, boolean premiumCostSaverEnabled) {
            this(participantId, status, speaking, listening, profile, premiumCostSaverEnabled, 0);
        }
    }
    public record Participant(UUID participantId, String displayName, short slot,
                              String speaking, String listening, boolean current, boolean owner) {}
    public record Snapshot(RoomStatus status, List<Participant> participants) {}
}
