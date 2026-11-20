package com.jinbang.api.ai;

/**
 * AI 平台对内契约。主站的智能备课等功能也走这里，从而共用同一套计费、日志和内容审核（PRD 15 打通）。
 */
public interface AiApi {

    /**
     * 同步执行一次智能体调用（含冻结 → 执行 → 结算）。
     *
     * @return 结果文本；失败时抛 BizException(AI_FAILED)，且不扣费
     */
    AiResult run(Long userId, String agentCode, String prompt);

    /** 带结构化参数（例如作文批改的 fullScore、subject）。 */
    AiResult run(Long userId, String agentCode, String prompt, java.util.Map<String, Object> params);

    /**
     * 免费调用（主站智能备课本身不计费，完成后还发奖励，PRD 4.1.2）。照样记录任务、走内容审核，只是不冻结不扣费。
     */
    AiResult runFree(Long userId, String agentCode, String prompt, java.util.Map<String, Object> params);

    record AiResult(String taskId, String content, long charged) {
    }
}
