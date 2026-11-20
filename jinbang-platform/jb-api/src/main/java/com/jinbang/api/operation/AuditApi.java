package com.jinbang.api.operation;

/**
 * 统一审核流（PRD 10.2.3）：征集投稿、资源上架、店铺、论坛帖子、评论、AI 内容都提交到这里。
 * 审核结论通过 {@link AuditDecidedEvent} 回传给业务模块。
 */
public interface AuditApi {

    /**
     * @param bizType RESOURCE / COLLECTION / POST / COMMENT / SCHOOL_RESOURCE / JOIN_SCHOOL
     * @return 审核单 ID
     */
    Long submit(String bizType, Long bizId, Long tenantId, String title, String content, Long submitterUserId);

    record AuditDecidedEvent(Long auditId, String bizType, Long bizId, boolean approved, String reason,
                             Long submitterUserId) {
    }
}
