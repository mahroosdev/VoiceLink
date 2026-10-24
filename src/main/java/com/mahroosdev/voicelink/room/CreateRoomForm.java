package com.mahroosdev.voicelink.room;

import jakarta.validation.constraints.NotBlank;

public class CreateRoomForm {
    @NotBlank(message = "Enter your display name for this conversation.")
    private String displayName;

    @NotBlank(message = "Choose a speaking language.")
    private String speaking;

    private String profile = "STANDARD";

    private String premiumCostSaver = "ON";

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getSpeaking() { return speaking; }
    public void setSpeaking(String speaking) { this.speaking = speaking; }
    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }
    public String getPremiumCostSaver() { return premiumCostSaver; }
    public void setPremiumCostSaver(String premiumCostSaver) { this.premiumCostSaver = premiumCostSaver; }
}
