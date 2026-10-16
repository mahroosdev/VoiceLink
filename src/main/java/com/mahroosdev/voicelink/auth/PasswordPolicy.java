package com.mahroosdev.voicelink.auth;

public final class PasswordPolicy {
    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 128;
    public static final String MESSAGE = "Password must be 10 to 128 characters.";

    private PasswordPolicy() {}

    public static boolean valid(String password) {
        return password != null && password.length() >= MIN_LENGTH && password.length() <= MAX_LENGTH;
    }
}
