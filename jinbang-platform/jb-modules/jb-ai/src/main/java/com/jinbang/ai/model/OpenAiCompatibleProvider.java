package com.jinbang.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** OpenAI 兼容协议（/chat/completions，stream=true），DeepSeek、通义千问、文心等都可接入。 */
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleProvider implements ModelProvider {
    private final ObjectMapper om;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public ChatResult chat(ModelSpec spec, List<ChatMessage> messages, Consumer<String> onDelta, BooleanSupplier cancelled) {
        if (spec.baseUrl() == null || spec.baseUrl().isBlank() || spec.apiKey() == null || spec.apiKey().isBlank()) {
            throw new IllegalStateException("model " + spec.modelCode() + " not configured");
        }
        ObjectNode body = om.createObjectNode();
        body.put("model", spec.modelName());
        body.put("stream", true);
        ArrayNode arr = body.putArray("messages");
        messages.forEach(m -> arr.addObject().put("role", m.role()).put("content", m.content()));
        HttpRequest req = HttpRequest.newBuilder(URI.create(spec.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofSeconds(spec.timeoutSec()))
                .header("Authorization", "Bearer " + spec.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        try {
            HttpResponse<java.io.InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("model http " + resp.statusCode());
            }
            StringBuilder out = new StringBuilder();
            long tokensIn = 0;
            long tokensOut = 0;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        return new ChatResult(out.toString(), tokensIn, tokensOut, true);
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode n = om.readTree(data);
                    String delta = n.path("choices").path(0).path("delta").path("content").asText("");
                    if (!delta.isEmpty()) {
                        out.append(delta);
                        if (onDelta != null) {
                            onDelta.accept(delta);
                        }
                    }
                    if (n.has("usage") && !n.get("usage").isNull()) {
                        tokensIn = n.path("usage").path("prompt_tokens").asLong(tokensIn);
                        tokensOut = n.path("usage").path("completion_tokens").asLong(tokensOut);
                    }
                }
            }
            return new ChatResult(out.toString(), tokensIn, tokensOut == 0 ? out.length() / 2 : tokensOut, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (Exception e) {
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e.getMessage(), e);
        }
    }
}
