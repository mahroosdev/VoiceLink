package com.mahroosdev.voicelink.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class RegistrationForm {
    @NotBlank
    @Size(min = 2, max = 60)
    @Pattern(regexp = "^[^\\p{Cc}]*$", message = "must not contain control characters")
    private String displayName;

    @NotBlank
    @Email
    @Size(max = 254)
    private String email;

    @NotNull
    @Size(min = 15, max = 128)
    private String password;

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
