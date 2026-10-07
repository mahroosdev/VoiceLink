package com.mahroosdev.voicelink.ai;

public final class StandardProfile {
    public static final String ID = "STANDARD";
    public static final String STT_PROVIDER = "GROQ";
    public static final String STT_MODEL = "whisper-large-v3";
    public static final String TRANSLATION_PROVIDER = "AZURE_TRANSLATOR";
    public static final String TRANSLATION_SERVICE = "F0";
    public static final String TTS_PROVIDER = "AZURE_SPEECH";
    public static final String TTS_SERVICE = "F0_NEURAL";

    private StandardProfile() {}
}
