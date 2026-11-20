package com.jinbang.api.system;

import java.util.List;

/**
 * 学年升级前的校验扩展点（PRD 11.8：未完成阅卷 / 未发布成绩要提示）。由考试模块实现。
 */
public interface YearUpgradeCheck {

    /** @return 需要提示管理员的问题，空列表表示可以直接升级 */
    List<String> check(Long tenantId, Long yearId);
}
