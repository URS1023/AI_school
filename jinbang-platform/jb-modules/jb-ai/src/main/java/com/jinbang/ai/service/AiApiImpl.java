package com.jinbang.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinbang.ai.runtime.AgentRuntime;
import com.jinbang.api.ai.AiApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.LoginHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/** 主站功能（智能备课、阅卷作文 AI 批阅等）调用 AI：走同一个运行时，共享计费、日志和内容审核。 */
@Service
@RequiredArgsConstructor
public class AiApiImpl implements AiApi {
    private final AgentRuntime runtime;
    private final ObjectMapper om;

    @Override
    public AiResult run(Long userId, String agentCode, String prompt) {
        return run(userId, agentCode, prompt, null);
    }

    @Override
    public AiResult run(Long userId, String agentCode, String prompt, Map<String, Object> params) {
        CurrentIdentity ci = LoginHelper.require();
        if (!ci.userId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        JsonNode p = params == null ? null : om.valueToTree(params);
        // 预估数量：出题按题量，其余按 1 次（结算时按实际用量，不超过预估）
        int units = p == null ? 1 : Math.max(1, p.path("count").asInt(1));
        AgentRuntime.TaskResult r = runtime.run(ci, new AgentRuntime.RunRequest(agentCode, null, prompt, p,
                java.util.List.of(new AgentRuntime.Item(null, prompt)), units, null, false, null, null));
        if (!"SUCCESS".equals(r.status())) {
            throw new BizException(ErrorCode.AI_FAILED, r.userMessage(), r.taskId());
        }
        return new AiResult(r.taskId(), r.items().getFirst().output(), r.charged());
    }
}
