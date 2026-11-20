package com.jinbang.ai.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinbang.common.storage.FileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * 图片识别（作文原图、试卷图片）。识别由 Python 识别服务完成（技术路线 3.6），Java 只做调用：
 * POST {jb.recognition.base-url}/ocr  {"image": base64} → {"text": "..."}。
 * 输入不是图片而是文字时直接返回；没有配置识别服务时图片返回空串，由调用方按「识别失败」处理（不扣费）。
 */
@Slf4j
@Component
public class OcrService {
    public static final String STORAGE_PREFIX = "storage:";

    private final FileStorage storage;
    private final ObjectMapper om;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public OcrService(FileStorage storage, ObjectMapper om, @Value("${jb.recognition.base-url:}") String baseUrl) {
        this.storage = storage;
        this.om = om;
        this.baseUrl = baseUrl;
    }

    public String textOf(String input) {
        if (input == null) {
            return "";
        }
        if (!input.startsWith(STORAGE_PREFIX)) {
            return input;
        }
        String key = input.substring(STORAGE_PREFIX.length());
        byte[] bytes = storage.get(key);
        if (key.endsWith(".txt") || key.endsWith(".md")) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("未配置识别服务 jb.recognition.base-url，图片 {} 无法识别", key);
            return "";
        }
        try {
            String body = om.writeValueAsString(Map.of("image", Base64.getEncoder().encodeToString(bytes)));
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/ocr"))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return om.readTree(resp.body()).path("text").asText("");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ocr interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("ocr failed: " + e.getMessage(), e);
        }
    }
}
