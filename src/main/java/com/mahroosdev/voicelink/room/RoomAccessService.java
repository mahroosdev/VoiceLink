package com.mahroosdev.voicelink.room;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RoomAccessService {
    private final RoomParticipantRepository participants;

    public RoomAccessService(RoomParticipantRepository participants) {
        this.participants = participants;
    }

    public void requireMember(UUID userId, UUID roomId) {
        if (!participants.existsByRoom_IdAndUser_Id(roomId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found");
        }
    }
}
