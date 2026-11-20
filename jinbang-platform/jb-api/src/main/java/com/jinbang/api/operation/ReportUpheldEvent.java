package com.jinbang.api.operation;

/**
 * 举报成立（PRD 11.7）：被举报对象所在模块收到后下架内容。
 *
 * @param targetType RESOURCE / POST / COMMENT / SHOP
 */
public record ReportUpheldEvent(Long reportId, String targetType, Long targetId, String reason) {
}
