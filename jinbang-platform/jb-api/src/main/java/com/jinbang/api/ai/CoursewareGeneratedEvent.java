package com.jinbang.api.ai;

/**
 * AI 平台生成了课件 / 教案（PRD 7.4 ➕P0：同步到主站「独立备课」目录，结束两套生成器两份数据的局面）。
 *
 * @param fileType PPTX / DOCX
 */
public record CoursewareGeneratedEvent(Long userId, String taskId, String title, String fileType, String storageKey,
                                       long sizeBytes, Long targetCatalogId) {
}
