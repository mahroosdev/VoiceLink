package com.mahroosdev.voicelink.ai;

public final class ProviderFailure extends RuntimeException {
    public enum Code { CONFIGURATION, QUOTA, RATE_LIMIT, TIMEOUT, UNAVAILABLE, INVALID_RESPONSE, UNSUPPORTED_LANGUAGE }

    private final Code code;

    public ProviderFailure(Code code) {
        super(message(code));
        this.code = code;
    }

    public Code code() { return code; }

    private static String message(Code code) {
        return switch (code) {
            case CONFIGURATION -> "Standard speech service is not configured.";
            case QUOTA -> "The Standard free-tier quota is unavailable or exhausted.";
            case RATE_LIMIT -> "The speech service is busy. Please try again later.";
            case TIMEOUT -> "The speech service timed out.";
            case UNAVAILABLE -> "The speech service is temporarily unavailable.";
            case INVALID_RESPONSE -> "The speech service returned an unusable result.";
            case UNSUPPORTED_LANGUAGE -> "This language is not supported for speech processing.";
        };
    }
}
