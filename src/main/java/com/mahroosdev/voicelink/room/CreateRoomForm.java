package com.mahroosdev.voicelink.room;

import jakarta.validation.constraints.NotBlank;

public class CreateRoomForm {
    @NotBlank(message = "Choose a speaking language.")
    private String speaking;

    @NotBlank(message = "Choose a listening language.")
    private String listening;

    private String profile = "STANDARD";

    public String getSpeaking() { return speaking; }
    public void setSpeaking(String speaking) { this.speaking = speaking; }
    public String getListening() { return listening; }
    public void setListening(String listening) { this.listening = listening; }
    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }
}
