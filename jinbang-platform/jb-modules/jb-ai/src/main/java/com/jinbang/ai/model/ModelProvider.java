package com.jinbang.ai.model;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 大模型调用抽象。国产模型多数兼容 OpenAI 协议，用同一个适配器接入（技术路线 2）。 */
public interface ModelProvider {

    /** mock / openai */
    String provider();

    ChatResult chat(ModelSpec spec, List<ChatMessage> messages, Consumer<String> onDelta, BooleanSupplier cancelled);

    record ChatMessage(String role, String content) {
        public static ChatMessage system(String c) {
            return new ChatMessage("system", c);
        }

        public static ChatMessage user(String c) {
            return new ChatMessage("user", c);
        }

        public static ChatMessage assistant(String c) {
            return new ChatMessage("assistant", c);
        }
    }

    record ChatResult(String content, long tokensIn, long tokensOut, boolean stopped) {
    }

    record ModelSpec(String modelCode, String provider, String baseUrl, String modelName, String apiKey,
                     int timeoutSec) {
    }
}
