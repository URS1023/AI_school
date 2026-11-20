package com.jinbang.api.operation;

import java.util.Collection;

/** 统一消息中心（PRD 2.2 ➕：系统公告、审核结果、订单、AI 任务完成、成绩更正）。 */
public interface NotifyApi {

    /**
     * @param category SYSTEM / AUDIT / ORDER / AI_TASK / SCORE / HOMEWORK
     * @param bizRef   关联业务，例如 exam:123
     */
    void send(Collection<Long> userIds, String category, String title, String content, String bizRef);
}
