package com.jinbang.ai.model;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.ai.domain.AiModel;
import com.jinbang.ai.mapper.AiModelMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 模型路由（PRD 10.2.4）：按智能体配置的模型编码取同编码的全部模型，按优先级依次尝试，主模型故障自动切备用。
 * 没有配置时使用 jb.ai.* 的默认模型。API Key 只存「环境变量名」，不落库。
 */
@Slf4j
@Component
public class ModelRouter {
    private final AiModelMapper modelMapper;
    private final Map<String, ModelProvider> providers;
    private final Environment env;
    private final ModelProvider.ModelSpec defaultSpec;

    public ModelRouter(AiModelMapper modelMapper, List<ModelProvider> providers, Environment env,
                       @Value("${jb.ai.provider:mock}") String provider, @Value("${jb.ai.base-url:}") String baseUrl,
                       @Value("${jb.ai.api-key:}") String apiKey, @Value("${jb.ai.model:deepseek-chat}") String model,
                       @Value("${jb.ai.timeout-seconds:60}") int timeout) {
        this.modelMapper = modelMapper;
        this.providers = providers.stream().collect(Collectors.toMap(ModelProvider::provider, Function.identity()));
        this.env = env;
        this.defaultSpec = new ModelProvider.ModelSpec("default", provider, baseUrl, model, apiKey, timeout);
    }

    public record Routed(ModelProvider.ChatResult result, String modelCode) {
    }

    public Routed chat(String modelCode, List<ModelProvider.ChatMessage> messages, Consumer<String> onDelta,
                       BooleanSupplier cancelled) {
        List<ModelProvider.ModelSpec> candidates = candidates(modelCode);
        RuntimeException last = null;
        for (ModelProvider.ModelSpec spec : candidates) {
            ModelProvider p = providers.get(spec.provider());
            if (p == null) {
                continue;
            }
            try {
                return new Routed(p.chat(spec, messages, onDelta, cancelled), spec.modelCode());
            } catch (RuntimeException e) {
                log.warn("模型 {}({}) 调用失败，尝试下一个: {}", spec.modelCode(), spec.modelName(), e.getMessage());
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("no model available");
    }

    private List<ModelProvider.ModelSpec> candidates(String modelCode) {
        List<ModelProvider.ModelSpec> list = new ArrayList<>();
        if (modelCode != null) {
            for (AiModel m : modelMapper.selectList(Wrappers.<AiModel>lambdaQuery().eq(AiModel::getModelCode, modelCode)
                    .eq(AiModel::getEnabled, true).orderByAsc(AiModel::getPriority))) {
                String key = m.getApiKeyRef() == null ? null : env.getProperty(m.getApiKeyRef());
                list.add(new ModelProvider.ModelSpec(m.getModelCode(), m.getProvider(), m.getBaseUrl(), m.getModelName(),
                        key, m.getTimeoutSec() == null ? 60 : m.getTimeoutSec()));
            }
        }
        list.add(defaultSpec);
        return list;
    }
}
