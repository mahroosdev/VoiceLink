package com.mahroosdev.voicelink.room;

import jakarta.validation.constraints.NotBlank;

public class JoinCodeForm {
    @NotBlank(message = "Enter an invite code.")
    private String joinCode;

    public String getJoinCode() { return joinCode; }
    public void setJoinCode(String joinCode) { this.joinCode = joinCode; }
}
