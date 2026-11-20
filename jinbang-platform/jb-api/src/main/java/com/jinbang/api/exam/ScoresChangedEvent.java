package com.jinbang.api.exam;

/**
 * 成绩变化（扫描入库、阅卷提交、改分、异常卷分配、补交）。学情模块据此重算报表和错题。
 *
 * @param sourceType HOMEWORK / EXAM
 */
public record ScoresChangedEvent(String sourceType, Long sourceId, Long tenantId) {
}
