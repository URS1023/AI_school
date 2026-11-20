package com.jinbang.api.operation;

import java.util.List;

/**
 * 内容安全（PRD 13 AI 合规：内容安全审核）。本地敏感词库为第一道防线，生产可叠加云内容安全接口。
 */
public interface ContentSafetyApi {

    SafetyResult check(String text);

    record SafetyResult(boolean blocked, List<String> hits) {
        public static SafetyResult pass() {
            return new SafetyResult(false, List.of());
        }
    }
}
