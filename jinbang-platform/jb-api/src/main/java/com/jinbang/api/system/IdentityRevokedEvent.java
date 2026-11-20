package com.jinbang.api.system;

/**
 * 身份或权限被撤销（离职、换班、家长解绑……，PRD 8.3）。
 * 各模块收到后要让该身份的导出链接、缓存报告、下载凭证立即失效。
 *
 * @param reason RESIGN / TRANSFER_CLASS / UNBIND_PARENT / HEAD_TEACHER_CHANGED
 */
public record IdentityRevokedEvent(Long userId, Long identityId, Long tenantId, String reason) {
}
