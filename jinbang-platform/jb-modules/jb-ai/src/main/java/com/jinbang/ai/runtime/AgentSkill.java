package com.jinbang.ai.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * 智能体技能：处理一个子项（一次问答 / 一篇作文 / 一页试卷）。
 * 计费、内容审核、状态记录都由 {@link AgentRuntime} 统一负责，技能只管业务。
 */
public interface AgentSkill {

    Set<String> agentCodes();

    ItemResult handle(SkillContext ctx, JsonNode params, String itemInput);

    /**
     * @param units    计费数量（题目数、页数、千字数）
     * @param fraction 用户主动停止时已生成的比例（0-1），用于「生成不足 10% 不收费」
     */
    record ItemResult(Status status, String output, int units, String userMessage, double fraction) {
        public static ItemResult success(String output, int units) {
            return new ItemResult(Status.SUCCESS, output, units, null, 1);
        }

        public static ItemResult empty(String why) {
            return new ItemResult(Status.EMPTY, null, 0, why, 0);
        }

        public static ItemResult stopped(String partial, int units, double fraction) {
            return new ItemResult(Status.STOPPED, partial, units, null, fraction);
        }
    }

    enum Status { SUCCESS, EMPTY, FAILED, BLOCKED, STOPPED }
}
