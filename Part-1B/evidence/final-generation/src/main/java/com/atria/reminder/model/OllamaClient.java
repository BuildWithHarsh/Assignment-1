package com.atria.reminder.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The only network client in the application: a local Ollama endpoint, nothing else (constitution III).
 *
 * <p>Every transport-level failure becomes ModelUnavailableException, so a service problem exits 6 and is never
 * mistaken for invalid model output (exit 7) or an application bug (exit 1).
 */
public final class OllamaClient {

    public static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient http;

    public OllamaClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /**
     * @param timeout the read timeout for this call — the remaining process budget, capped at {@link #CALL_TIMEOUT}
     * @return the model's {@code message.content}, unparsed
     */
    public String chat(String body, Duration timeout) throws ModelUnavailableException {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new ModelUnavailableException("time budget exhausted before the model could answer");
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(new URI(baseUrl + "/api/chat"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new ModelUnavailableException("ollama endpoint " + baseUrl + " is not a usable URL");
        }

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ModelUnavailableException(
                    "ollama endpoint " + baseUrl + " could not be reached: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException("the model call was interrupted", e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new ModelUnavailableException(
                    "ollama endpoint " + baseUrl + " returned HTTP " + response.statusCode());
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new ModelUnavailableException("ollama endpoint " + baseUrl + " returned a non-JSON response");
        }
        JsonNode content = root.path("message").path("content");
        if (!content.isTextual()) {
            throw new ModelUnavailableException(
                    "ollama response did not contain message.content");
        }
        return content.asText();
    }
}
