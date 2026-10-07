package com.mahroosdev.voicelink.ai;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class ProviderHttp {
    private ProviderHttp() {}

    public static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public static <T> HttpResponse<T> send(HttpClient client, HttpRequest request,
                                           HttpResponse.BodyHandler<T> handler) {
        try {
            HttpResponse<T> response = client.send(request, handler);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderFailure(codeForStatus(response.statusCode()));
            }
            return response;
        } catch (java.net.http.HttpTimeoutException ex) {
            throw new ProviderFailure(ProviderFailure.Code.TIMEOUT);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProviderFailure(ProviderFailure.Code.UNAVAILABLE);
        } catch (IOException ex) {
            throw new ProviderFailure(ProviderFailure.Code.UNAVAILABLE);
        }
    }

    public static ProviderFailure.Code codeForStatus(int status) {
        if (status == 401 || status == 403) return ProviderFailure.Code.CONFIGURATION;
        if (status == 429) return ProviderFailure.Code.RATE_LIMIT;
        if (status == 402 || status == 456) return ProviderFailure.Code.QUOTA;
        if (status == 408 || status == 504) return ProviderFailure.Code.TIMEOUT;
        return status >= 500 ? ProviderFailure.Code.UNAVAILABLE : ProviderFailure.Code.INVALID_RESPONSE;
    }
}
