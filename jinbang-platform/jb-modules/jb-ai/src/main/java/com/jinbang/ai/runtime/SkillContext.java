package com.jinbang.ai.runtime;

import com.jinbang.ai.domain.AiAgent;
import com.jinbang.ai.model.ModelProvider;
import com.jinbang.ai.model.ModelRouter;
import com.jinbang.common.security.CurrentIdentity;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 一次子项执行的上下文：身份、智能体、当前 Prompt、流式回调、取消标记，以及记录 token 用量。 */
@Getter
public class SkillContext {
    private final CurrentIdentity identity;
    private final AiAgent agent;
    private final String systemPrompt;
    private final String taskId;
    private final int itemNo;
    private final List<ModelProvider.ChatMessage> history;
    private final Consumer<String> onDelta;
    private final BooleanSupplier cancelled;
    private final ModelRouter router;
    private long tokensIn;
    private long tokensOut;
    private String modelCode;

    public SkillContext(CurrentIdentity identity, AiAgent agent, String systemPrompt, String taskId, int itemNo,
                        List<ModelProvider.ChatMessage> history, Consumer<String> onDelta, BooleanSupplier cancelled,
                        ModelRouter router) {
        this.identity = identity;
        this.agent = agent;
        this.systemPrompt = systemPrompt;
        this.taskId = taskId;
        this.itemNo = itemNo;
        this.history = history == null ? List.of() : history;
        this.onDelta = onDelta;
        this.cancelled = cancelled == null ? () -> false : cancelled;
        this.router = router;
    }

    /** 以当前智能体的系统提示词调用模型（带会话历史）。 */
    public ModelProvider.ChatResult chat(String userContent, boolean stream) {
        List<ModelProvider.ChatMessage> msgs = new ArrayList<>();
        msgs.add(ModelProvider.ChatMessage.system(systemPrompt));
        msgs.addAll(history);
        msgs.add(ModelProvider.ChatMessage.user(userContent));
        ModelRouter.Routed r = router.chat(agent.getModelCode(), msgs, stream ? onDelta : null, cancelled);
        tokensIn += r.result().tokensIn();
        tokensOut += r.result().tokensOut();
        modelCode = r.modelCode();
        return r.result();
    }
}
