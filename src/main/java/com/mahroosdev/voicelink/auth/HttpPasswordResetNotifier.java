package com.mahroosdev.voicelink.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("prod & !test")
public class HttpPasswordResetNotifier implements PasswordResetNotifier {
    private static final URI ENDPOINT = URI.create("https://api.resend.com/emails");
    private final PasswordResetSettings settings;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public HttpPasswordResetNotifier(PasswordResetSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    @Override
    public void send(String recipient, String resetUrl) {
        try {
            String body = json.writeValueAsString(Map.of(
                    "from", settings.fromAddress(),
                    "to", List.of(recipient),
                    "subject", "Reset your VoiceLink password",
                    "text", "A password reset was requested for your VoiceLink account.\n\n"
                            + "Use this link within 30 minutes:\n" + resetUrl + "\n\n"
                            + "If you did not request this, you can ignore this message."));
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                    .timeout(Duration.ofSeconds(6))
                    .header("Authorization", "Bearer " + settings.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status < 200 || status >= 300) throw new PasswordResetMailException();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PasswordResetMailException();
        } catch (Exception ex) {
            throw new PasswordResetMailException();
        }
    }
}
