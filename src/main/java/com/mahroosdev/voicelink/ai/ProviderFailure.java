package com.mahroosdev.voicelink.ai;

public final class ProviderFailure extends RuntimeException {
    public enum Code { CONFIGURATION, QUOTA, RATE_LIMIT, TIMEOUT, UNAVAILABLE, INVALID_RESPONSE, UNSUPPORTED_LANGUAGE }
    public enum Outcome {
        PROVIDER_HTTP_408, PROVIDER_HTTP_429, PROVIDER_HTTP_5XX, PROVIDER_QUOTA, LOCAL_TIMEOUT,
        NETWORK_IO, INVALID_RESPONSE_STRUCTURE, SAFETY_OR_NONSTANDARD_FINISH,
        CONFIGURATION, LOCAL_RATE_LIMIT, REQUEST_CANCELLED, UNEXPECTED_RUNTIME_FAILURE
    }
    public enum Origin { APP, PROVIDER, NETWORK, UNKNOWN }

    private final Code code;
    private final Outcome outcome;
    private final Origin origin;
    private final boolean retryable;

    public ProviderFailure(Code code) {
        this(code, defaultOutcome(code), defaultOrigin(code), false);
    }

    public ProviderFailure(Code code, Outcome outcome, Origin origin, boolean retryable) {
        super(message(code));
        this.code = code;
        this.outcome = outcome;
        this.origin = origin;
        this.retryable = retryable;
    }

    public Code code() { return code; }
    public Outcome outcome() { return outcome; }
    public Origin origin() { return origin; }
    public boolean retryable() { return retryable; }

    public static ProviderFailure forHttpStatus(int status) {
        if (status == 408) {
            return new ProviderFailure(Code.TIMEOUT, Outcome.PROVIDER_HTTP_408, Origin.PROVIDER, false);
        }
        if (status == 429) {
            return new ProviderFailure(Code.RATE_LIMIT, Outcome.PROVIDER_HTTP_429, Origin.PROVIDER, false);
        }
        if (status >= 500) {
            return new ProviderFailure(Code.UNAVAILABLE, Outcome.PROVIDER_HTTP_5XX,
                    Origin.PROVIDER, status == 502 || status == 503);
        }
        return new ProviderFailure(codeForOtherStatus(status));
    }

    public static ProviderFailure localTimeout() {
        return new ProviderFailure(Code.TIMEOUT, Outcome.LOCAL_TIMEOUT, Origin.NETWORK, false);
    }

    public static ProviderFailure networkIo() {
        return new ProviderFailure(Code.UNAVAILABLE, Outcome.NETWORK_IO, Origin.NETWORK, true);
    }

    public static ProviderFailure requestCancelled() {
        return new ProviderFailure(Code.UNAVAILABLE, Outcome.REQUEST_CANCELLED, Origin.APP, false);
    }

    public static ProviderFailure invalidResponse() {
        return new ProviderFailure(Code.INVALID_RESPONSE, Outcome.INVALID_RESPONSE_STRUCTURE,
                Origin.PROVIDER, false);
    }

    public static ProviderFailure nonstandardFinish() {
        return new ProviderFailure(Code.INVALID_RESPONSE, Outcome.SAFETY_OR_NONSTANDARD_FINISH,
                Origin.PROVIDER, false);
    }

    public static ProviderFailure unexpectedRuntime() {
        return new ProviderFailure(Code.UNAVAILABLE, Outcome.UNEXPECTED_RUNTIME_FAILURE,
                Origin.UNKNOWN, false);
    }

    public static ProviderFailure localRateLimit() {
        return new ProviderFailure(Code.RATE_LIMIT, Outcome.LOCAL_RATE_LIMIT, Origin.APP, false);
    }

    private static Code codeForOtherStatus(int status) {
        if (status == 401 || status == 403) return Code.CONFIGURATION;
        if (status == 402 || status == 456) return Code.QUOTA;
        return Code.INVALID_RESPONSE;
    }

    private static Outcome defaultOutcome(Code code) {
        return switch (code) {
            case CONFIGURATION -> Outcome.CONFIGURATION;
            case RATE_LIMIT -> Outcome.LOCAL_RATE_LIMIT;
            case TIMEOUT -> Outcome.LOCAL_TIMEOUT;
            case INVALID_RESPONSE, UNSUPPORTED_LANGUAGE -> Outcome.INVALID_RESPONSE_STRUCTURE;
            case QUOTA -> Outcome.PROVIDER_QUOTA;
            case UNAVAILABLE -> Outcome.UNEXPECTED_RUNTIME_FAILURE;
        };
    }

    private static Origin defaultOrigin(Code code) {
        return switch (code) {
            case CONFIGURATION, INVALID_RESPONSE, QUOTA -> Origin.PROVIDER;
            case RATE_LIMIT, UNSUPPORTED_LANGUAGE -> Origin.APP;
            case TIMEOUT -> Origin.NETWORK;
            case UNAVAILABLE -> Origin.UNKNOWN;
        };
    }

    private static String message(Code code) {
        return switch (code) {
            case CONFIGURATION -> "The speech service is not configured.";
            case QUOTA -> "The speech service quota is unavailable or exhausted.";
            case RATE_LIMIT -> "The speech service is busy. Please try again later.";
            case TIMEOUT -> "The speech service timed out.";
            case UNAVAILABLE -> "The speech service is temporarily unavailable.";
            case INVALID_RESPONSE -> "The speech service returned an unusable result.";
            case UNSUPPORTED_LANGUAGE -> "This language is not supported for speech processing.";
        };
    }
}
