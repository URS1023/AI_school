package com.jinbang.system.service;

import java.util.List;

/**
 * 导入结果（PRD 6.8 ➕ 导入校验）：先整体校验，有任何错误就一条都不导入，并逐行列出原因。
 */
public record ImportResult(int total, int imported, List<RowError> errors) {
    public record RowError(int row, String field, String message) {
    }

    public boolean ok() {
        return errors.isEmpty();
    }
}
