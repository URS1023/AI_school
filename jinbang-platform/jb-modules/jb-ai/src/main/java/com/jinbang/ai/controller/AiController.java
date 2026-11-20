package com.jinbang.ai.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinbang.ai.domain.AiAgent;
import com.jinbang.ai.domain.AiEssayRecord;
import com.jinbang.ai.domain.AiKb;
import com.jinbang.ai.domain.AiKbDoc;
import com.jinbang.ai.domain.AiMessage;
import com.jinbang.ai.domain.AiRecommendQuestion;
import com.jinbang.ai.domain.AiSession;
import com.jinbang.ai.domain.AiTask;
import com.jinbang.ai.kb.KbService;
import com.jinbang.ai.mapper.AiEssayRecordMapper;
import com.jinbang.ai.mapper.AiRecommendQuestionMapper;
import com.jinbang.ai.mapper.AiTaskMapper;
import com.jinbang.ai.runtime.AgentRuntime;
import com.jinbang.ai.runtime.AgentService;
import com.jinbang.ai.service.ChatService;
import com.jinbang.ai.skill.OcrService;
import com.jinbang.ai.skill.PptBuilder;
import com.jinbang.ai.skill.PptSkill;
import com.jinbang.api.resource.QuestionApi;
import com.jinbang.api.system.ExportLinkApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.core.PageQuery;
import com.jinbang.common.core.PageResult;
import com.jinbang.common.core.R;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.LoginHelper;
import com.jinbang.common.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RestController
@RequiredArgsConstructor
public class AiController {
    private static final ExecutorService STREAM_POOL = Executors.newVirtualThreadPerTaskExecutor();
    private static final Map<String, String> STATUS_LABELS = Map.of("SUCCESS", "成功", "PARTIAL", "部分成功",
            "FAILED", "失败-未扣费", "EMPTY", "无结果-未扣费", "BLOCKED", "内容拦截-未扣费", "STOPPED", "已停止",
            "RUNNING", "进行中");

    private final AgentService agentService;
    private final AgentRuntime runtime;
    private final ChatService chatService;
    private final KbService kbService;
    private final AiTaskMapper taskMapper;
    private final AiRecommendQuestionMapper recommendMapper;
    private final AiEssayRecordMapper essayMapper;
    private final FileStorage storage;
    private final ExportLinkApi exportLinkApi;
    private final QuestionApi questionApi;
    private final ObjectMapper om;

    public record ChatReq(String agentCode, Long sessionId, String question, List<Long> kbIds) {
    }

    public record SessionPatch(String title, Boolean pinned, Boolean deleted) {
    }

    public record FeedbackReq(boolean liked, String comment) {
    }

    public record EssayReq(String subject, String period, String grade, String genre, Integer fullScore, String title,
                           List<EssayItem> essays) {
    }

    public record EssayItem(Long studentId, String studentName, String text, String storageKey) {
    }

    public record PaperReq(String subject, String period, String grade, String markMode, Boolean withComment,
                           String standardAnswer, List<String> pages) {
    }

    public record SaveQuestionsReq(String subject, String difficulty, List<JsonNode> questions) {
    }

    public record TaskView(String taskId, String agentCode, String agentName, String status, String statusLabel,
                           long estimate, long charged, int totalItems, int successItems, String userMessage,
                           Long sessionId, java.time.LocalDateTime createdAt) {
    }

    // ---------------- 智能体与计费公示 ----------------

    /** 智能体列表与单价公示（PRD 2.4 ➕P0）。 */
    @GetMapping("/api/open/ai/agents")
    public R<List<AiAgent>> agents() {
        return R.ok(agentService.list());
    }

    @GetMapping("/api/ai/agents")
    public R<List<AiAgent>> myAgents() {
        return R.ok(agentService.listFor(LoginHelper.require()));
    }

    /** 推荐问题按身份类型过滤（游客不推荐学情类问题）。 */
    @GetMapping("/api/ai/recommend")
    public R<List<String>> recommend(@RequestParam String agentCode) {
        CurrentIdentity ci = LoginHelper.require();
        return R.ok(recommendMapper.selectList(Wrappers.<AiRecommendQuestion>lambdaQuery()
                        .eq(AiRecommendQuestion::getAgentCode, agentCode)
                        .and(w -> w.isNull(AiRecommendQuestion::getIdentityType)
                                .or().eq(AiRecommendQuestion::getIdentityType, ci.type().name()))
                        .orderByAsc(AiRecommendQuestion::getSortNo))
                .stream().map(AiRecommendQuestion::getQuestion).toList());
    }

    /** 预估费用：提交前展示「预计消耗 N 币」。 */
    @GetMapping("/api/ai/estimate")
    public R<Long> estimate(@RequestParam String agentCode, @RequestParam(defaultValue = "1") int units) {
        return R.ok(agentService.estimate(agentService.get(agentCode), units));
    }

    // ---------------- 对话 ----------------

    @PostMapping("/api/ai/chat")
    public R<ChatService.ChatReply> chat(@RequestBody ChatReq req) {
        return R.ok(chatService.ask(LoginHelper.require(), req.agentCode() == null ? "ask" : req.agentCode(),
                req.sessionId(), req.question(), kbParams(req), null, null));
    }

    /** 流式输出：事件 start(taskId) → delta(文本片段)… → done(结果、扣费、AI 标识)。 */
    @PostMapping(value = "/api/ai/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatReq req) {
        CurrentIdentity ci = LoginHelper.require();
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(5).toMillis());
        // 流式线程没有 Web 会话，显式带上身份（租户隔离、工具的数据权限都依赖它）
        STREAM_POOL.submit(() -> LoginHelper.runAs(ci, () -> {
            try {
                ChatService.ChatReply reply = chatService.ask(ci, req.agentCode() == null ? "ask" : req.agentCode(),
                        req.sessionId(), req.question(), kbParams(req),
                        taskId -> send(emitter, "start", Map.of("taskId", taskId)),
                        delta -> send(emitter, "delta", delta));
                send(emitter, "done", reply);
                emitter.complete();
            } catch (BizException e) {
                send(emitter, "error", Map.of("code", e.getCode(), "msg", e.getMessage()));
                emitter.complete();
            } catch (Exception e) {
                log.error("流式对话异常", e);
                send(emitter, "error", Map.of("code", ErrorCode.AI_FAILED.code(), "msg", ErrorCode.AI_FAILED.msg()));
                emitter.complete();
            }
        }));
        return emitter;
    }

    private JsonNode kbParams(ChatReq req) {
        if (req.kbIds() == null || req.kbIds().isEmpty()) {
            return null;
        }
        ObjectNode n = om.createObjectNode();
        req.kbIds().forEach(n.putArray("kbIds")::add);
        return n;
    }

    private static void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException | IllegalStateException e) {
            // 客户端断开：不影响任务结算
        }
    }

    @PostMapping("/api/ai/tasks/{taskId}/stop")
    public R<Void> stop(@PathVariable String taskId) {
        runtime.stop(taskId, LoginHelper.userId());
        return R.ok();
    }

    /** 重试失败项：同一任务 ID，幂等（AC-11）。 */
    @PostMapping("/api/ai/tasks/{taskId}/retry")
    public R<AgentRuntime.TaskResult> retry(@PathVariable String taskId) {
        return R.ok(runtime.retry(LoginHelper.require(), taskId));
    }

    @GetMapping("/api/ai/tasks/{taskId}")
    public R<AgentRuntime.TaskResult> task(@PathVariable String taskId) {
        return R.ok(runtime.get(LoginHelper.require(), taskId));
    }

    /** 教师端消费记录（PRD 7.1 ➕P0）：时间、智能体、会话 / 任务、扣费、状态。 */
    @GetMapping("/api/ai/tasks")
    public R<PageResult<TaskView>> myTasks(@RequestParam(required = false) String agentCode, PageQuery q) {
        Map<String, String> names = new java.util.HashMap<>();
        agentService.list().forEach(a -> names.put(a.getAgentCode(), a.getAgentName()));
        var page = taskMapper.selectPage(q.toPage(), Wrappers.<AiTask>lambdaQuery()
                .eq(AiTask::getUserId, LoginHelper.userId()).eq(agentCode != null, AiTask::getAgentCode, agentCode)
                .orderByDesc(AiTask::getCreatedAt));
        return R.ok(PageResult.of(page, t -> new TaskView(t.getTaskId(), t.getAgentCode(), names.get(t.getAgentCode()),
                t.getTaskStatus(), STATUS_LABELS.getOrDefault(t.getTaskStatus(), t.getTaskStatus()), t.getEstimate(),
                t.getCharged(), t.getTotalItems(), t.getSuccessItems(), t.getUserMessage(), t.getSessionId(),
                t.getCreatedAt())));
    }

    @GetMapping("/api/ai/sessions")
    public R<List<AiSession>> sessions(@RequestParam(required = false) String agentCode,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(chatService.sessions(LoginHelper.require(), agentCode, keyword));
    }

    @GetMapping("/api/ai/sessions/{id}/messages")
    public R<List<AiMessage>> messages(@PathVariable Long id) {
        return R.ok(chatService.messages(LoginHelper.require(), id));
    }

    @PutMapping("/api/ai/sessions/{id}")
    public R<Void> patchSession(@PathVariable Long id, @RequestBody SessionPatch p) {
        chatService.update(LoginHelper.require(), id, p.title(), p.pinned(), p.deleted());
        return R.ok();
    }

    @PostMapping("/api/ai/messages/{id}/feedback")
    public R<Void> feedback(@PathVariable Long id, @RequestBody FeedbackReq req) {
        chatService.feedback(LoginHelper.require(), id, req.liked(), req.comment());
        return R.ok();
    }

    // ---------------- 出题 ----------------

    /** params：period、grade、subject、difficulty、type、count、requirement。 */
    @PostMapping("/api/ai/questions/generate")
    public R<AgentRuntime.TaskResult> genQuestions(@RequestBody ObjectNode params) {
        int count = Math.max(1, params.path("count").asInt(5));
        return R.ok(runtime.run(LoginHelper.require(), new AgentRuntime.RunRequest("question", null,
                params.path("requirement").asText(""), params, List.of(new AgentRuntime.Item("题目",
                params.path("requirement").asText(""))), count, null, false, null, null)));
    }

    /** 生成的题一键加入个人题库（PRD 7.3 ➕P0）。 */
    @PostMapping("/api/ai/questions/save")
    public R<List<Long>> saveQuestions(@RequestBody SaveQuestionsReq req) {
        List<QuestionApi.NewQuestion> qs = new ArrayList<>();
        for (JsonNode q : req.questions()) {
            qs.add(new QuestionApi.NewQuestion(req.subject(), q.path("type").asText("SINGLE"), req.difficulty(),
                    q.path("stem").asText(), q.path("options").toString(), q.path("answer").asText(),
                    q.path("analysis").asText(), "AI"));
        }
        return R.ok(questionApi.saveToPersonalBank(LoginHelper.userId(), qs));
    }

    // ---------------- PPT ----------------

    @GetMapping("/api/open/ai/ppt/templates")
    public R<List<Map<String, String>>> templates() {
        return R.ok(PptBuilder.THEMES.entrySet().stream()
                .map(e -> Map.of("code", e.getKey(), "name", e.getValue().name())).toList());
    }

    /** 第二步：生成大纲（免费，可编辑）。 */
    @PostMapping("/api/ai/ppt/outline")
    public R<AgentRuntime.TaskResult> outline(@RequestBody ObjectNode params) {
        params.put("op", "outline");
        return R.ok(runtime.run(LoginHelper.require(), new AgentRuntime.RunRequest("ppt", null,
                params.path("topic").asText(""), params, null, 1, null, true, null, null)));
    }

    /** 第三步：选模板生成课件，按页计费；课件自动进入独立备课。params：outline、template、catalogId。 */
    @PostMapping("/api/ai/ppt/generate")
    public R<AgentRuntime.TaskResult> generatePpt(@RequestBody ObjectNode params) {
        params.put("op", "generate");
        int pages = PptSkill.estimatePages(params.path("outline"));
        return R.ok(runtime.run(LoginHelper.require(), new AgentRuntime.RunRequest("ppt", null,
                params.path("outline").path("title").asText("课件"), params, null, pages, null, false, null, null)));
    }

    @GetMapping("/api/ai/files/link")
    public R<String> fileLink(@RequestParam String taskId) {
        AgentRuntime.TaskResult t = runtime.get(LoginHelper.require(), taskId);
        JsonNode out;
        try {
            out = om.readTree(t.items().getFirst().output());
        } catch (Exception e) {
            throw new BizException(ErrorCode.NOT_FOUND, "该任务没有生成文件");
        }
        return R.ok(exportLinkApi.createLink(out.path("storageKey").asText(), out.path("title").asText("课件") + ".pptx",
                Duration.ofHours(2)));
    }

    // ---------------- 批改 ----------------

    /** 作文批改（可一次多篇）。每篇可指定学生，用于按学生追踪进步。 */
    @PostMapping("/api/ai/essay/correct")
    public R<AgentRuntime.TaskResult> essay(@RequestBody EssayReq req) {
        BizException.throwIf(req.essays() == null || req.essays().isEmpty(), ErrorCode.BAD_REQUEST, "请上传作文");
        ObjectNode p = om.createObjectNode();
        p.put("subject", req.subject() == null ? "CHINESE" : req.subject());
        p.put("period", req.period());
        p.put("grade", req.grade());
        p.put("genre", req.genre());
        p.put("fullScore", req.fullScore() == null ? 60 : req.fullScore());
        p.put("title", req.title());
        ObjectNode students = p.putObject("students");
        List<AgentRuntime.Item> items = new ArrayList<>();
        for (int i = 0; i < req.essays().size(); i++) {
            EssayItem e = req.essays().get(i);
            ObjectNode s = students.putObject(String.valueOf(i + 1));
            if (e.studentId() != null) {
                s.put("studentId", e.studentId());
            }
            s.put("name", e.studentName());
            items.add(new AgentRuntime.Item(e.studentName() == null ? "第" + (i + 1) + "篇" : e.studentName(),
                    e.storageKey() != null ? OcrService.STORAGE_PREFIX + e.storageKey() : e.text()));
        }
        return R.ok(runtime.run(LoginHelper.require(), new AgentRuntime.RunRequest("essay", null, req.title(), p, items,
                items.size(), null, false, null, null)));
    }

    /** 学生作文进步追踪（按学生，而不是按教师账号）。 */
    @GetMapping("/api/ai/essay/history")
    public R<List<AiEssayRecord>> essayHistory(@RequestParam(required = false) Long studentId) {
        return R.ok(essayMapper.selectList(Wrappers.<AiEssayRecord>lambdaQuery()
                .eq(AiEssayRecord::getUserId, LoginHelper.userId())
                .eq(studentId != null, AiEssayRecord::getStudentId, studentId)
                .orderByAsc(AiEssayRecord::getCreatedAt)));
    }

    @PostMapping("/api/ai/paper/correct")
    public R<AgentRuntime.TaskResult> paper(@RequestBody PaperReq req) {
        BizException.throwIf(req.pages() == null || req.pages().isEmpty(), ErrorCode.BAD_REQUEST, "请按顺序上传试卷图片");
        ObjectNode p = om.valueToTree(req);
        p.remove("pages");
        List<AgentRuntime.Item> items = new ArrayList<>();
        for (int i = 0; i < req.pages().size(); i++) {
            String page = req.pages().get(i);
            items.add(new AgentRuntime.Item("第" + (i + 1) + "页", page.startsWith("text:") ? page.substring(5)
                    : OcrService.STORAGE_PREFIX + page));
        }
        return R.ok(runtime.run(LoginHelper.require(), new AgentRuntime.RunRequest("paper_correct", null, "试卷批改", p,
                items, items.size(), null, false, null, null)));
    }

    /** 上传图片（作文原图、试卷页），返回存储键。 */
    @PostMapping("/api/ai/upload")
    public R<String> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        BizException.throwIf(!Set.of("jpg", "jpeg", "png", "webp", "txt").contains(ext.toLowerCase()), ErrorCode.BAD_REQUEST,
                "只支持 jpg / png / webp 图片");
        return R.ok(storage.put("ai-upload", ext, file.getBytes()));
    }

    // ---------------- 知识库 ----------------

    public record KbReq(String name, String intro, boolean shared) {
    }

    public record MemberReq(Long userId, String role) {
    }

    public record DocPatch(String name, String folder) {
    }

    @PostMapping("/api/ai/kb")
    public R<AiKb> createKb(@RequestBody KbReq req) {
        return R.ok(kbService.create(LoginHelper.userId(), req.name(), req.intro(), req.shared()));
    }

    @GetMapping("/api/ai/kb")
    public R<List<KbService.KbView>> myKb() {
        return R.ok(kbService.mine(LoginHelper.userId()));
    }

    @PostMapping("/api/ai/kb/{kbId}/members")
    public R<Void> addMember(@PathVariable Long kbId, @RequestBody MemberReq req) {
        kbService.addMember(LoginHelper.userId(), kbId, req.userId(), req.role());
        return R.ok();
    }

    @PostMapping("/api/ai/kb/{kbId}/docs")
    public R<AiKbDoc> uploadDoc(@PathVariable Long kbId, @RequestParam(required = false) String folder,
                                @RequestParam("file") MultipartFile file) throws IOException {
        return R.ok(kbService.upload(LoginHelper.userId(), kbId, folder, file.getOriginalFilename(), file.getBytes()));
    }

    @GetMapping("/api/ai/kb/{kbId}/docs")
    public R<List<AiKbDoc>> docs(@PathVariable Long kbId, @RequestParam(required = false) String folder,
                                 @RequestParam(required = false) String keyword) {
        return R.ok(kbService.docs(LoginHelper.userId(), kbId, folder, keyword));
    }

    @PutMapping("/api/ai/kb/docs/{docId}")
    public R<Void> patchDoc(@PathVariable Long docId, @RequestBody DocPatch p) {
        kbService.renameOrMove(LoginHelper.userId(), docId, p.name(), p.folder());
        return R.ok();
    }

    @PostMapping("/api/ai/kb/docs/{docId}/delete")
    public R<Void> deleteDoc(@PathVariable Long docId) {
        kbService.deleteDoc(LoginHelper.userId(), docId);
        return R.ok();
    }
}
