package com.jinbang.exam.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinbang.common.storage.FileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把扫描图片交给 Python 识别服务（异步）。识别服务处理完后按契约回调 /api/exam/recognition/callback，
 * 回传的 images 用这里的存储键，教师端据此查看原卷。没有配置识别服务时只存图，不转发。
 */
@Slf4j
@Component
public class RecognitionClient {
    private final FileStorage storage;
    private final ObjectMapper om;
    private final String baseUrl;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public RecognitionClient(FileStorage storage, ObjectMapper om, @Value("${jb.recognition.base-url:}") String baseUrl,
                             @Value("${jb.recognition.callback-token:}") String token) {
        this.storage = storage;
        this.om = om;
        this.baseUrl = baseUrl;
        this.token = token;
    }

    public boolean enabled() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    public void submitAsync(Long batchId, List<String> keys) {
        if (!enabled()) {
            return;
        }
        pool.submit(() -> {
            try {
                ObjectNode body = om.createObjectNode();
                ArrayNode arr = body.putArray("images");
                for (String k : keys) {
                    arr.addObject().put("key", k).put("data", Base64.getEncoder().encodeToString(storage.get(k)));
                }
                HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/batches/" + batchId + "/recognize"))
                        .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                        .header("X-Recognition-Token", token).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    log.warn("识别服务受理失败 batch={} http={}", batchId, resp.statusCode());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("提交识别失败 batch={}: {}", batchId, e.toString());
            }
        });
    }
}
