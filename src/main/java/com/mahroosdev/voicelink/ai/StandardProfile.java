package com.mahroosdev.voicelink.ai;

public final class StandardProfile {
    public static final String ID = "STANDARD";
    public static final String STT_PROVIDER = "GROQ";
    public static final String STT_MODEL = "whisper-large-v3";
    public static final String TRANSLATION_PROVIDER = "GEMINI";
    public static final String TRANSLATION_SERVICE = "gemini-3.1-flash-lite";
    public static final String TTS_PROVIDER = "GEMINI";
    public static final String TTS_SERVICE = "gemini-3.8-flash-lite-tts";

    private StandardProfile() {}
}
