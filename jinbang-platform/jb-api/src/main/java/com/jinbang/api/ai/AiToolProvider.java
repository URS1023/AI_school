package com.jinbang.api.ai;

import com.jinbang.common.security.CurrentIdentity;

/**
 * 智能问答的「工具」（PRD 7.2 意图路由：题库检索、资源检索、学情分析、错题分析）。
 * 由资源、学情等模块实现；工具执行时沿用调用者身份，因此只能拿到其权限范围内的数据。
 * 工具抛异常 → 本次 AI 任务按「系统失败」处理，不扣费，不向用户暴露内部信息（AC-09）。
 */
public interface AiToolProvider {

    String name();

    /** 是否处理这个问题（关键词或意图判断）。 */
    boolean matches(String agentCode, String question);

    /** @return 提供给大模型的参考资料（纯文本）；没有结果返回空字符串 */
    String run(CurrentIdentity identity, String question);
}
