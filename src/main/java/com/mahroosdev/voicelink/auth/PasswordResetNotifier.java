package com.mahroosdev.voicelink.auth;

public interface PasswordResetNotifier {
    void send(String recipient, String resetUrl);
}
