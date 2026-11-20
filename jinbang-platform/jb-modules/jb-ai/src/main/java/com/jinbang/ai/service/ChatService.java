package com.jinbang.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.jinbang.ai.domain.AiAnswerFeedback;
import com.jinbang.ai.domain.AiMessage;
import com.jinbang.ai.domain.AiSession;
import com.jinbang.ai.mapper.AiAnswerFeedbackMapper;
import com.jinbang.ai.mapper.AiMessageMapper;
import com.jinbang.ai.mapper.AiSessionMapper;
import com.jinbang.ai.model.ModelProvider;
import com.jinbang.ai.runtime.AgentRuntime;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.security.CurrentIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 会话与消息（PRD 7.1 会话侧栏：新对话、搜索、置顶、重命名、删除）。 */
@Service
@RequiredArgsConstructor
public class ChatService {
    private static final int HISTORY = 10;

    private final AiSessionMapper sessionMapper;
    private final AiMessageMapper messageMapper;
    private final AiAnswerFeedbackMapper feedbackMapper;
    private final AgentRuntime runtime;

    public record ChatReply(Long sessionId, Long messageId, AgentRuntime.TaskResult task) {
    }

    public ChatReply ask(CurrentIdentity ci, String agentCode, Long sessionId, String question, JsonNode params,
                         Consumer<String> onStart, Consumer<String> onDelta) {
        AiSession s = sessionId == null ? newSession(ci, agentCode, question) : own(ci, sessionId);
        List<ModelProvider.ChatMessage> history = new ArrayList<>();
        List<AiMessage> recent = messageMapper.selectList(Wrappers.<AiMessage>lambdaQuery()
                .eq(AiMessage::getSessionId, s.getId()).eq(AiMessage::getMsgStatus, "SUCCESS")
                .orderByDesc(AiMessage::getCreatedAt).orderByDesc(AiMessage::getId).last("LIMIT " + HISTORY));
        for (int i = recent.size() - 1; i >= 0; i--) {
            AiMessage m = recent.get(i);
            history.add(new ModelProvider.ChatMessage(m.getMsgRole(), m.getContent()));
        }
        AiMessage userMsg = save(s.getId(), "user", question, null, "SUCCESS");
        AgentRuntime.TaskResult r;
        try {
            r = runtime.run(ci, new AgentRuntime.RunRequest(s.getAgentCode(), s.getId(), question, params, null,
                    s.getAgentCode().equals("ask") ? 4 : 1, history, false, onStart, onDelta));
        } catch (BizException e) {
            // 被拦截的问题不保留内容
            if (e.getCode() == ErrorCode.CONTENT_BLOCKED.code()) {
                messageMapper.deleteById(userMsg.getId());
            }
            throw e;
        }
        String answer = r.items().isEmpty() ? null : r.items().getFirst().output();
        String status = "SUCCESS".equals(r.status()) || "STOPPED".equals(r.status()) ? "SUCCESS" : r.status();
        AiMessage reply = save(s.getId(), "assistant", answer == null ? r.userMessage() : answer, r.taskId(), status);
        sessionMapper.updateById(s);
        return new ChatReply(s.getId(), reply.getId(), r);
    }

    private AiSession newSession(CurrentIdentity ci, String agentCode, String question) {
        AiSession s = new AiSession();
        s.setUserId(ci.userId());
        s.setIdentityId(ci.identityId());
        s.setAgentCode(agentCode);
        s.setTitle(question == null ? "新对话" : question.substring(0, Math.min(30, question.length())));
        s.setPinned(false);
        s.setDeleted(false);
        sessionMapper.insert(s);
        return s;
    }

    private AiMessage save(Long sessionId, String role, String content, String taskId, String status) {
        AiMessage m = new AiMessage();
        m.setSessionId(sessionId);
        m.setMsgRole(role);
        m.setContent(content);
        m.setTaskId(taskId);
        m.setMsgStatus(status);
        messageMapper.insert(m);
        return m;
    }

    public AiSession own(CurrentIdentity ci, Long sessionId) {
        AiSession s = sessionMapper.selectById(sessionId);
        if (s == null || !s.getUserId().equals(ci.userId()) || Boolean.TRUE.equals(s.getDeleted())) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return s;
    }

    public List<AiSession> sessions(CurrentIdentity ci, String agentCode, String keyword) {
        return sessionMapper.selectList(Wrappers.<AiSession>lambdaQuery().eq(AiSession::getUserId, ci.userId())
                .eq(AiSession::getDeleted, false).eq(agentCode != null, AiSession::getAgentCode, agentCode)
                .like(keyword != null && !keyword.isBlank(), AiSession::getTitle, keyword)
                .orderByDesc(AiSession::getPinned).orderByDesc(AiSession::getUpdatedAt));
    }

    public List<AiMessage> messages(CurrentIdentity ci, Long sessionId) {
        own(ci, sessionId);
        return messageMapper.selectList(Wrappers.<AiMessage>lambdaQuery().eq(AiMessage::getSessionId, sessionId)
                .orderByAsc(AiMessage::getCreatedAt).orderByAsc(AiMessage::getId));
    }

    public void update(CurrentIdentity ci, Long sessionId, String title, Boolean pinned, Boolean deleted) {
        AiSession s = own(ci, sessionId);
        if (title != null && !title.isBlank()) {
            s.setTitle(title);
        }
        if (pinned != null) {
            s.setPinned(pinned);
        }
        if (deleted != null) {
            s.setDeleted(deleted);
        }
        sessionMapper.updateById(s);
    }

    /** 一键反馈（点赞 / 点踩 + 意见），进入 AI 中台效果评测。 */
    public void feedback(CurrentIdentity ci, Long messageId, boolean liked, String comment) {
        AiMessage m = BizException.notNull(messageMapper.selectById(messageId), "消息");
        own(ci, m.getSessionId());
        AiAnswerFeedback f = new AiAnswerFeedback();
        f.setMessageId(messageId);
        f.setUserId(ci.userId());
        f.setLiked(liked);
        f.setComment(comment);
        try {
            feedbackMapper.insert(f);
        } catch (DuplicateKeyException e) {
            feedbackMapper.update(Wrappers.<AiAnswerFeedback>lambdaUpdate().set(AiAnswerFeedback::getLiked, liked)
                    .set(AiAnswerFeedback::getComment, comment).eq(AiAnswerFeedback::getMessageId, messageId)
                    .eq(AiAnswerFeedback::getUserId, ci.userId()));
        }
    }
}
