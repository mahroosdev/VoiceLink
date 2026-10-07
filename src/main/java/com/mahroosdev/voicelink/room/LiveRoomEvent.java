package com.mahroosdev.voicelink.room;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record LiveRoomEvent(int protocolVersion, UUID eventId, UUID streamId, UUID roomId,
                            long sequence, String type, UUID senderParticipantId, UUID turnId,
                            Instant createdAt, Map<String, ?> payload) {}
