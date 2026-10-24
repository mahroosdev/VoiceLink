package com.mahroosdev.voicelink.room;

import jakarta.validation.constraints.NotBlank;

public class JoinRoomForm {
    @NotBlank(message = "Invite unavailable. Check the code or ask for a new room.")
    private String joinCode;

    @NotBlank(message = "Enter your display name for this conversation.")
    private String displayName;

    @NotBlank(message = "Choose your speaking language.")
    private String speaking;

    public String getJoinCode() { return joinCode; }
    public void setJoinCode(String joinCode) { this.joinCode = joinCode; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getSpeaking() { return speaking; }
    public void setSpeaking(String speaking) { this.speaking = speaking; }
}
