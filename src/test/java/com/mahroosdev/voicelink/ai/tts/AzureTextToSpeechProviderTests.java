package com.mahroosdev.voicelink.ai.tts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.mahroosdev.voicelink.ai.ProviderFailure;
import com.mahroosdev.voicelink.ai.StandardProviderSettings;

import org.junit.jupiter.api.Test;

class AzureTextToSpeechProviderTests {
    @Test void escapesUntrustedTextInSsmlAndUsesSelectedVoice() {
        String xml = AzureTextToSpeechProvider.ssml("ta-LK", "ta-LK-SaranyaNeural", "A&B <tag>\"'");
        assertThat(xml).contains("ta-LK-SaranyaNeural", "A&amp;B &lt;tag&gt;&quot;&apos;");
        assertThat(xml).doesNotContain("<tag>");
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsSyntheticMp3AndRejectsProviderErrorOrUnexpectedBody() throws Exception {
        var settings = new StandardProviderSettings(true, "unused", "unused", "eastus",
                "https://api.cognitive.microsofttranslator.com", "test-key", "eastus");
        HttpClient http = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new byte[]{(byte) 0xff, (byte) 0xfb, 0x10});
        var provider = new AzureTextToSpeechProvider(settings, http);
        var result = provider.synthesize(new TextToSpeechProvider.Input("Hello", "en"));
        assertThat(result.audio()).hasSize(3);
        assertThat(result.mediaType()).isEqualTo("audio/mpeg");
        assertThat(result.voiceId()).isEqualTo("en-US-JennyNeural");

        when(response.statusCode()).thenReturn(429);
        assertThatThrownBy(() -> provider.synthesize(new TextToSpeechProvider.Input("Hello", "en")))
                .isInstanceOfSatisfying(ProviderFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo(ProviderFailure.Code.RATE_LIMIT));

        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("not mp3".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> provider.synthesize(new TextToSpeechProvider.Input("Hello", "en")))
                .isInstanceOfSatisfying(ProviderFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo(ProviderFailure.Code.INVALID_RESPONSE));
    }
}
