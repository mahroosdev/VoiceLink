package com.mahroosdev.voicelink.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ForgotPasswordForm {
    @NotBlank
    @Email
    @Size(max = 254)
    private String email;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
