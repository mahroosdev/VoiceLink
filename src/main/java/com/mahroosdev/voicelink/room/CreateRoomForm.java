package com.mahroosdev.voicelink.room;

import jakarta.validation.constraints.NotBlank;

public class CreateRoomForm {
    @NotBlank(message = "Choose a speaking language.")
    private String speaking;

    @NotBlank(message = "Choose a listening language.")
    private String listening;

    public String getSpeaking() { return speaking; }
    public void setSpeaking(String speaking) { this.speaking = speaking; }
    public String getListening() { return listening; }
    public void setListening(String listening) { this.listening = listening; }
}
