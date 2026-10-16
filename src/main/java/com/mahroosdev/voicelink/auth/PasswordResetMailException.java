package com.mahroosdev.voicelink.auth;

public final class PasswordResetMailException extends RuntimeException {
    public PasswordResetMailException() {
        super("Password reset email delivery unavailable");
    }
}
