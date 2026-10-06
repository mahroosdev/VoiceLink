package com.mahroosdev.voicelink.room;

public class RoomInviteUnavailableException extends RuntimeException {
    public RoomInviteUnavailableException() {
        super("Invite unavailable. Check the code or ask for a new room.");
    }
}
