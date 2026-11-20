package com.jinbang.ai.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinbang.ai.domain.AiAgent;
import com.jinbang.ai.domain.AiPrompt;
import com.jinbang.ai.domain.AiSubtask;
import com.jinbang.ai.domain.AiTask;
import com.jinbang.ai.mapper.AiSubtaskMapper;
import com.jinbang.ai.mapper.AiTaskMapper;
import com.jinbang.ai.model.ModelProvider;
import com.jinbang.ai.model.ModelRouter;
import com.jinbang.api.account.AccountApi;
import com.jinbang.api.operation.ContentSafetyApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.Constants;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.security.CurrentIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 智能体运行时（PRD 11.1、技术路线 3.4）。
 *
 * <pre>
 * 提交 → 输入审核(拦截：不收费、不保留内容) → 生成任务ID → 冻结预估金额(余额不足：提示充值，不创建任务)
 *      → 逐个子项执行 → 输出审核 → 结算(只收成功子项；失败/空结果/拦截不收费；主动停止不足 10% 不收费)
 *      → 个人消费记录与运营流水用同一任务 ID 对账
 * </pre>
 * 模型调用很慢，所以这里不包大事务：冻结、建任务、结算各自提交，由流水唯一键保证幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRuntime {
    public static final String BIZ_TYPE = "AI_TASK";
    private static final Set<String> RETRYABLE = Set.of("FAILED", "EMPTY");

    private final AgentService agentService;
    private final AiTaskMapper taskMapper;
    private final AiSubtaskMapper subtaskMapper;
    private final AccountApi accountApi;
    private final ContentSafetyApi safety;
    private final ModelRouter router;
    private final ObjectMapper om;
    private final List<AgentSkill> skillBeans;
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private Map<String, AgentSkill> skills;

    public record Item(String label, String input) {
    }

    /**
     * @param estimateUnits 预估数量（题数 / 页数 / 千字数 / 篇数）
     * @param free          本次免费（例如 PPT 的大纲步骤，最终生成时才计费）
     */
    public record RunRequest(String agentCode, Long sessionId, String displayText, JsonNode params, List<Item> items,
                             int estimateUnits, List<ModelProvider.ChatMessage> history, boolean free,
                             Consumer<String> onStart, Consumer<String> onDelta) {
    }

    public record ItemView(int itemNo, String label, String status, String output, long charged, String message) {
    }

    public record TaskResult(String taskId, String status, long estimate, long charged, int totalItems,
                             int successItems, String userMessage, List<ItemView> items, String disclaimer) {
    }

    private AgentSkill skill(String code) {
        if (skills == null) {
            skills = new ConcurrentHashMap<>();
            for (AgentSkill s : skillBeans) {
                s.agentCodes().forEach(c -> skills.put(c, s));
            }
        }
        AgentSkill s = skills.get(code);
        if (s == null) {
            throw new BizException(ErrorCode.STATE_TRANSITION_DENIED, "该智能体暂未开放");
        }
        return s;
    }

    public void stop(String taskId, Long userId) {
        AiTask t = taskMapper.selectOne(Wrappers.<AiTask>lambdaQuery().eq(AiTask::getTaskId, taskId));
        if (t != null && t.getUserId().equals(userId)) {
            AtomicBoolean f = cancelFlags.get(taskId);
            if (f != null) {
                f.set(true);
            }
        }
    }

    public TaskResult run(CurrentIdentity ci, RunRequest req) {
        AiAgent agent = agentService.require(req.agentCode(), ci);
        AgentSkill skill = skill(agent.getAgentCode());
        JsonNode params = req.params() == null ? om.createObjectNode() : req.params();
        List<Item> items = req.items() == null || req.items().isEmpty()
                ? List.of(new Item(null, req.displayText())) : req.items();
        if (items.size() > agent.getMaxUnits()) {
            throw new BizException(ErrorCode.BAD_REQUEST, agent.getAgentName() + "一次最多处理 " + agent.getMaxUnits() + " 项");
        }

        // 1. 输入审核：拦截的内容不收费、不保留
        String allInput = (req.displayText() == null ? "" : req.displayText()) + " "
                + items.stream().map(i -> i.input() == null ? "" : i.input()).collect(Collectors.joining(" "));
        if (safety.check(allInput).blocked()) {
            AiTask t = newTask(ci, agent, req, UUID.randomUUID().toString(), 0, items.size());
            t.setInputText(null);
            t.setTaskStatus("BLOCKED");
            t.setUserMessage(ErrorCode.CONTENT_BLOCKED.msg());
            t.setFinishedAt(LocalDateTime.now());
            taskMapper.insert(t);
            throw new BizException(ErrorCode.CONTENT_BLOCKED);
        }

        // 2. 冻结预估金额（余额不足直接提示充值，不创建任务）
        String taskId = UUID.randomUUID().toString().replace("-", "");
        long estimate = req.free() ? 0 : agentService.estimate(agent, req.estimateUnits());
        if (estimate > 0) {
            accountApi.freeze(ci.userId(), taskId, estimate, BIZ_TYPE, agent.getAgentName());
        }
        AiPrompt prompt = agentService.promptFor(agent.getAgentCode(), ci.userId());
        AiTask task = newTask(ci, agent, req, taskId, estimate, items.size());
        task.setPromptVersion(prompt.getVersionNo());
        taskMapper.insert(task);
        AtomicBoolean cancel = new AtomicBoolean(false);
        cancelFlags.put(taskId, cancel);
        if (req.onStart() != null) {
            req.onStart().accept(taskId);
        }

        long started = System.currentTimeMillis();
        List<AiSubtask> subs = new ArrayList<>();
        long charged = 0;
        try {
            for (int i = 0; i < items.size(); i++) {
                Item item = items.get(i);
                SkillContext ctx = new SkillContext(ci, agent, prompt.getContent(), taskId, i + 1, req.history(),
                        req.onDelta(), cancel::get, router);
                AiSubtask sub = new AiSubtask();
                sub.setTaskId(taskId);
                sub.setItemNo(i + 1);
                sub.setItemLabel(item.label());
                sub.setInputText(item.input());
                sub.setAttempts(1);
                long remaining = req.free() ? 0 : Math.max(0, estimate - charged);
                charged += execute(skill, ctx, params, item.input(), agent, sub, remaining, req.free());
                subtaskMapper.insert(sub);
                subs.add(sub);
                task.setTokensIn(task.getTokensIn() + ctx.getTokensIn());
                task.setTokensOut(task.getTokensOut() + ctx.getTokensOut());
                if (ctx.getModelCode() != null) {
                    task.setModelCode(ctx.getModelCode());
                }
                if (cancel.get()) {
                    // 停止后剩下的子项不再执行，记为停止且不收费
                    for (int j = i + 1; j < items.size(); j++) {
                        AiSubtask rest = new AiSubtask();
                        rest.setTaskId(taskId);
                        rest.setItemNo(j + 1);
                        rest.setItemLabel(items.get(j).label());
                        rest.setInputText(items.get(j).input());
                        rest.setItemStatus("STOPPED");
                        rest.setAttempts(1);
                        rest.setUnits(0);
                        rest.setCharged(0L);
                        subtaskMapper.insert(rest);
                        subs.add(rest);
                    }
                    break;
                }
            }
        } finally {
            cancelFlags.remove(taskId);
            // 3. 结算：无论成败都结算，未用部分全额解冻
            long settled = estimate > 0 ? accountApi.settle(taskId, charged, agent.getAgentName()) : 0;
            task.setCharged(settled);
            task.setCostMs(System.currentTimeMillis() - started);
            task.setFinishedAt(LocalDateTime.now());
            summarize(task, subs);
            taskMapper.updateById(task);
        }
        return view(task, subs);
    }

    /** 执行一个子项并写回子任务状态，返回该子项应收金额（已按剩余冻结额封顶）。 */
    private long execute(AgentSkill skill, SkillContext ctx, JsonNode params, String input, AiAgent agent, AiSubtask sub,
                         long remaining, boolean free) {
        AgentSkill.ItemResult r;
        try {
            r = skill.handle(ctx, params, input);
        } catch (BizException e) {
            r = new AgentSkill.ItemResult(AgentSkill.Status.FAILED, null, 0, e.getMessage(), 0);
        } catch (Exception e) {
            log.warn("AI 子任务失败 task={} item={}: {}", ctx.getTaskId(), ctx.getItemNo(), e.toString());
            sub.setFailMessage(trim(e.toString(), 300));
            r = new AgentSkill.ItemResult(AgentSkill.Status.FAILED, null, 0, ErrorCode.AI_FAILED.msg(), 0);
        }
        if ((r.status() == AgentSkill.Status.SUCCESS || r.status() == AgentSkill.Status.STOPPED)
                && safety.check(r.output()).blocked()) {
            r = new AgentSkill.ItemResult(AgentSkill.Status.BLOCKED, null, 0, ErrorCode.CONTENT_BLOCKED.msg(), 0);
        }
        long charge = 0;
        if (!free) {
            if (r.status() == AgentSkill.Status.SUCCESS) {
                charge = agentService.actual(agent, r.units());
            } else if (r.status() == AgentSkill.Status.STOPPED && r.fraction() >= 0.1) {
                charge = agentService.actual(agent, Math.max(1, r.units()));
            }
        }
        charge = Math.min(charge, remaining);
        sub.setItemStatus(r.status().name());
        sub.setOutputText(r.status() == AgentSkill.Status.BLOCKED ? null : r.output());
        sub.setUnits(r.units());
        sub.setCharged(charge);
        if (r.userMessage() != null && sub.getFailMessage() == null) {
            sub.setFailMessage(r.userMessage());
        } else if (r.userMessage() != null) {
            sub.setFailMessage(trim(r.userMessage() + "｜" + sub.getFailMessage(), 300));
        }
        return charge;
    }

    /**
     * 重试失败 / 空结果的子项（PRD 11.1、AC-10、AC-11）：
     * 同一任务 ID 下按子项重试；用 CAS 抢占子项，连续点两次也只会执行、扣费一次；只有成功的那次收费。
     */
    public TaskResult retry(CurrentIdentity ci, String taskId) {
        AiTask task = BizException.notNull(taskMapper.selectOne(Wrappers.<AiTask>lambdaQuery()
                .eq(AiTask::getTaskId, taskId)), "任务");
        if (!task.getUserId().equals(ci.userId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        AiAgent agent = agentService.require(task.getAgentCode(), ci);
        AgentSkill skill = skill(agent.getAgentCode());
        JsonNode params = readParams(task.getInputText());
        AiPrompt prompt = agentService.promptFor(agent.getAgentCode(), ci.userId());
        List<AiSubtask> subs = subtaskMapper.selectList(Wrappers.<AiSubtask>lambdaQuery().eq(AiSubtask::getTaskId, taskId)
                .orderByAsc(AiSubtask::getItemNo));
        long perItem = Math.max(agentService.actual(agent, 1), task.getEstimate() / Math.max(1, task.getTotalItems()));
        for (AiSubtask sub : subs) {
            if (!RETRYABLE.contains(sub.getItemStatus())) {
                continue;
            }
            int claimed = subtaskMapper.update(Wrappers.<AiSubtask>lambdaUpdate().set(AiSubtask::getItemStatus, "RETRYING")
                    .set(AiSubtask::getAttempts, sub.getAttempts() + 1)
                    .eq(AiSubtask::getId, sub.getId()).eq(AiSubtask::getItemStatus, sub.getItemStatus()));
            if (claimed == 0) {
                continue;
            }
            sub.setAttempts(sub.getAttempts() + 1);
            String billKey = taskId + "#" + sub.getItemNo() + "#" + sub.getAttempts();
            boolean free = "FREE".equals(agent.getPriceMode()) || task.getEstimate() == 0;
            long est = free ? 0 : perItem;
            try {
                if (est > 0) {
                    accountApi.freeze(ci.userId(), billKey, est, BIZ_TYPE, agent.getAgentName() + "（重试）");
                }
            } catch (BizException e) {
                subtaskMapper.update(Wrappers.<AiSubtask>lambdaUpdate().set(AiSubtask::getItemStatus, sub.getItemStatus())
                        .eq(AiSubtask::getId, sub.getId()));
                throw e;
            }
            SkillContext ctx = new SkillContext(ci, agent, prompt.getContent(), taskId, sub.getItemNo(), List.of(),
                    null, () -> false, router);
            sub.setFailMessage(null);
            long charge = execute(skill, ctx, params, sub.getInputText(), agent, sub, est, free);
            long settled = est > 0 ? accountApi.settle(billKey, charge, agent.getAgentName() + "（重试）") : 0;
            sub.setCharged(sub.getCharged() + settled);
            subtaskMapper.updateById(sub);
            task.setCharged(task.getCharged() + settled);
            task.setTokensIn(task.getTokensIn() + ctx.getTokensIn());
            task.setTokensOut(task.getTokensOut() + ctx.getTokensOut());
        }
        List<AiSubtask> latest = subtaskMapper.selectList(Wrappers.<AiSubtask>lambdaQuery().eq(AiSubtask::getTaskId, taskId)
                .orderByAsc(AiSubtask::getItemNo));
        summarize(task, latest);
        taskMapper.updateById(task);
        return view(task, latest);
    }

    public TaskResult get(CurrentIdentity ci, String taskId) {
        AiTask task = BizException.notNull(taskMapper.selectOne(Wrappers.<AiTask>lambdaQuery()
                .eq(AiTask::getTaskId, taskId)), "任务");
        if (!task.getUserId().equals(ci.userId()) && !ci.isPlatform()) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        return view(task, subtaskMapper.selectList(Wrappers.<AiSubtask>lambdaQuery().eq(AiSubtask::getTaskId, taskId)
                .orderByAsc(AiSubtask::getItemNo)));
    }

    private void summarize(AiTask task, List<AiSubtask> subs) {
        Map<String, Long> by = subs.stream().collect(Collectors.groupingBy(AiSubtask::getItemStatus, Collectors.counting()));
        int ok = by.getOrDefault("SUCCESS", 0L).intValue();
        int failed = subs.size() - ok;
        task.setSuccessItems(ok);
        String status;
        String msg;
        if (ok == subs.size()) {
            status = "SUCCESS";
            msg = task.getCharged() > 0 ? "本次消耗 " + task.getCharged() + " 金榜币" : "完成";
        } else if (ok > 0) {
            status = "PARTIAL";
            msg = "成功 " + ok + "，失败 " + failed + "（未扣费），可重试失败项";
        } else if (by.containsKey("BLOCKED")) {
            status = "BLOCKED";
            msg = ErrorCode.CONTENT_BLOCKED.msg();
        } else if (by.containsKey("STOPPED")) {
            status = "STOPPED";
            msg = task.getCharged() > 0 ? "已停止，本次消耗 " + task.getCharged() + " 金榜币" : "已停止，本次未扣费";
        } else if (by.containsKey("EMPTY") && !by.containsKey("FAILED")) {
            status = "EMPTY";
            msg = subs.stream().map(AiSubtask::getFailMessage).filter(m -> m != null && !m.isBlank()).findFirst()
                    .map(m -> m + "，本次未扣费").orElse("没有得到有效结果，本次未扣费，建议补充更具体的要求后重试");
        } else {
            status = "FAILED";
            msg = ErrorCode.AI_FAILED.msg();
        }
        task.setTaskStatus(status);
        task.setUserMessage(msg);
        String reasons = subs.stream().map(AiSubtask::getFailMessage).filter(m -> m != null && !m.isBlank())
                .distinct().collect(Collectors.joining("；"));
        task.setFailReason(reasons.isEmpty() ? null : trim(reasons, 1000));
    }

    private TaskResult view(AiTask t, List<AiSubtask> subs) {
        Function<AiSubtask, String> safeMsg = s -> "FAILED".equals(s.getItemStatus()) ? ErrorCode.AI_FAILED.msg()
                : s.getFailMessage();
        List<ItemView> items = subs.stream().map(s -> new ItemView(s.getItemNo(), s.getItemLabel(), s.getItemStatus(),
                s.getOutputText(), s.getCharged(), safeMsg.apply(s))).toList();
        return new TaskResult(t.getTaskId(), t.getTaskStatus(), t.getEstimate(), t.getCharged(), t.getTotalItems(),
                t.getSuccessItems(), t.getUserMessage(), items, Constants.AI_DISCLAIMER);
    }

    private AiTask newTask(CurrentIdentity ci, AiAgent agent, RunRequest req, String taskId, long estimate, int total) {
        AiTask t = new AiTask();
        t.setTaskId(taskId);
        t.setUserId(ci.userId());
        t.setIdentityId(ci.identityId());
        t.setTenantId(ci.tenantId());
        t.setAgentCode(agent.getAgentCode());
        t.setSessionId(req.sessionId());
        t.setInputText(req.params() == null ? trim(req.displayText(), 4000) : req.params().toString());
        t.setEstimate(estimate);
        t.setCharged(0L);
        t.setTaskStatus("RUNNING");
        t.setTotalItems(total);
        t.setSuccessItems(0);
        t.setTokensIn(0L);
        t.setTokensOut(0L);
        t.setCostMs(0L);
        return t;
    }

    private JsonNode readParams(String s) {
        try {
            return s == null ? om.createObjectNode() : om.readTree(s);
        } catch (Exception e) {
            return om.createObjectNode().put("text", s);
        }
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
