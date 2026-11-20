package com.jinbang.ai.service;

import com.jinbang.ai.runtime.AgentRuntime;
import com.jinbang.api.ai.AiApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.LoginHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 主站功能（智能备课等）调用 AI：走同一个运行时，共享计费、日志和内容审核。 */
@Service
@RequiredArgsConstructor
public class AiApiImpl implements AiApi {
    private final AgentRuntime runtime;

    @Override
    public AiResult run(Long userId, String agentCode, String prompt) {
        CurrentIdentity ci = LoginHelper.require();
        if (!ci.userId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        AgentRuntime.TaskResult r = runtime.run(ci, new AgentRuntime.RunRequest(agentCode, null, prompt, null, null, 1,
                null, false, null, null));
        if (!"SUCCESS".equals(r.status())) {
            throw new BizException(ErrorCode.AI_FAILED, r.userMessage(), r.taskId());
        }
        return new AiResult(r.taskId(), r.items().getFirst().output(), r.charged());
    }
}
