package com.jinbang.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.ai.domain.AiAgent;
import com.jinbang.ai.domain.AiModel;
import com.jinbang.ai.domain.AiPrompt;
import com.jinbang.ai.domain.AiTask;
import com.jinbang.ai.mapper.AiModelMapper;
import com.jinbang.ai.mapper.AiTaskMapper;
import com.jinbang.ai.runtime.AgentService;
import com.jinbang.common.core.PageQuery;
import com.jinbang.common.core.PageResult;
import com.jinbang.common.core.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** AI 中台（PRD 10.2.4）：智能体上下架与计费、Prompt 版本灰度回滚、模型路由、调用日志、使用统计。 */
@RestController
@RequiredArgsConstructor
@SaCheckPermission("op:ai")
public class AiAdminController {
    private final AgentService agentService;
    private final AiModelMapper modelMapper;
    private final AiTaskMapper taskMapper;

    public record PromptReq(String content, String remark) {
    }

    public record ReleaseReq(int versionNo, int percent) {
    }

    public record AgentStat(String agentCode, long calls, long success, double successRate, long income,
                            long tokensIn, long tokensOut, double avgCostMs) {
    }

    @PostMapping("/api/admin/ai/agents")
    public R<AiAgent> updateAgent(@RequestBody AiAgent patch) {
        return R.ok(agentService.update(patch));
    }

    @GetMapping("/api/admin/ai/agents/{code}/prompts")
    public R<List<AiPrompt>> prompts(@PathVariable String code) {
        return R.ok(agentService.prompts(code));
    }

    @PostMapping("/api/admin/ai/agents/{code}/prompts")
    public R<AiPrompt> newPrompt(@PathVariable String code, @RequestBody PromptReq req) {
        return R.ok(agentService.newPrompt(code, req.content(), req.remark()));
    }

    /** percent=100 发布；1-99 灰度；回滚 = 对旧版本再发布一次。 */
    @PostMapping("/api/admin/ai/agents/{code}/prompts/release")
    public R<Void> release(@PathVariable String code, @RequestBody ReleaseReq req) {
        agentService.release(code, req.versionNo(), req.percent());
        return R.ok();
    }

    @GetMapping("/api/admin/ai/models")
    public R<List<AiModel>> models() {
        return R.ok(modelMapper.selectList(Wrappers.<AiModel>lambdaQuery().orderByAsc(AiModel::getModelCode)
                .orderByAsc(AiModel::getPriority)));
    }

    /** apiKeyRef 填环境变量名，密钥本身不入库。 */
    @PostMapping("/api/admin/ai/models")
    public R<AiModel> saveModel(@RequestBody AiModel m) {
        if (m.getId() == null) {
            modelMapper.insert(m);
        } else {
            modelMapper.updateById(m);
        }
        return R.ok(m);
    }

    /** 调用日志：用户、智能体、状态、耗时、token、扣费；可按任务 ID 定位一次请求。 */
    @GetMapping("/api/admin/ai/tasks")
    public R<PageResult<AiTask>> tasks(@RequestParam(required = false) String agentCode,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String taskId,
                                       @RequestParam(required = false) Long userId, PageQuery q) {
        return R.ok(PageResult.of(taskMapper.selectPage(q.toPage(), Wrappers.<AiTask>lambdaQuery()
                .eq(agentCode != null, AiTask::getAgentCode, agentCode).eq(status != null, AiTask::getTaskStatus, status)
                .eq(taskId != null, AiTask::getTaskId, taskId).eq(userId != null, AiTask::getUserId, userId)
                .orderByDesc(AiTask::getCreatedAt))));
    }

    /** 使用数据统计：按智能体统计调用量、成功率、收入、token、平均耗时。 */
    @GetMapping("/api/admin/ai/stats")
    public R<List<AgentStat>> stats(@RequestParam(defaultValue = "30") int days) {
        List<AiTask> tasks = taskMapper.selectList(Wrappers.<AiTask>lambdaQuery()
                .ge(AiTask::getCreatedAt, LocalDateTime.now().minusDays(days)));
        Map<String, List<AiTask>> by = tasks.stream().collect(Collectors.groupingBy(AiTask::getAgentCode));
        return R.ok(by.entrySet().stream().map(e -> {
            List<AiTask> l = e.getValue();
            long ok = l.stream().filter(t -> "SUCCESS".equals(t.getTaskStatus())).count();
            return new AgentStat(e.getKey(), l.size(), ok, l.isEmpty() ? 0 : ok * 100.0 / l.size(),
                    l.stream().mapToLong(AiTask::getCharged).sum(), l.stream().mapToLong(AiTask::getTokensIn).sum(),
                    l.stream().mapToLong(AiTask::getTokensOut).sum(),
                    l.stream().mapToLong(AiTask::getCostMs).average().orElse(0));
        }).toList());
    }
}
