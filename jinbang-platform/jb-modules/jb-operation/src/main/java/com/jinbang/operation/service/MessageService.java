package com.jinbang.operation.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.operation.NotifyApi;
import com.jinbang.common.core.PageQuery;
import com.jinbang.common.core.PageResult;
import com.jinbang.operation.domain.MsgMessage;
import com.jinbang.operation.mapper.MsgMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * 统一消息中心（PRD 2.2 ➕P1）：系统公告、审核结果、订单、AI 任务完成、成绩更正 / 撤回通知。
 * 生产环境可在这里扩展推送通道（WebSocket、微信小程序订阅消息、短信）。
 */
@Service
@RequiredArgsConstructor
public class MessageService implements NotifyApi {
    private final MsgMessageMapper messageMapper;

    @Override
    public void send(Collection<Long> userIds, String category, String title, String content, String bizRef) {
        if (userIds == null) {
            return;
        }
        for (Long uid : new LinkedHashSet<>(userIds)) {
            if (uid == null) {
                continue;
            }
            MsgMessage m = new MsgMessage();
            m.setUserId(uid);
            m.setCategory(category);
            m.setTitle(title);
            m.setContent(content);
            m.setBizRef(bizRef);
            m.setReadFlag(false);
            messageMapper.insert(m);
        }
    }

    public PageResult<MsgMessage> list(Long userId, String category, Boolean unread, PageQuery q) {
        return PageResult.of(messageMapper.selectPage(q.toPage(), Wrappers.<MsgMessage>lambdaQuery()
                .eq(MsgMessage::getUserId, userId).eq(category != null, MsgMessage::getCategory, category)
                .eq(Boolean.TRUE.equals(unread), MsgMessage::getReadFlag, false)
                .orderByDesc(MsgMessage::getCreatedAt).orderByDesc(MsgMessage::getId)));
    }

    public long unreadCount(Long userId) {
        return messageMapper.selectCount(Wrappers.<MsgMessage>lambdaQuery().eq(MsgMessage::getUserId, userId)
                .eq(MsgMessage::getReadFlag, false));
    }

    /** ids 为空表示全部已读。只能改自己的消息。 */
    public void markRead(Long userId, Collection<Long> ids) {
        messageMapper.update(Wrappers.<MsgMessage>lambdaUpdate().set(MsgMessage::getReadFlag, true)
                .set(MsgMessage::getReadAt, LocalDateTime.now()).eq(MsgMessage::getUserId, userId)
                .eq(MsgMessage::getReadFlag, false).in(ids != null && !ids.isEmpty(), MsgMessage::getId, ids));
    }
}
