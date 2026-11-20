package com.jinbang.exam.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.core.R;
import com.jinbang.common.security.LoginHelper;
import com.jinbang.common.tenant.TenantContext;
import com.jinbang.exam.domain.ExAnswer;
import com.jinbang.exam.domain.ExExam;
import com.jinbang.exam.domain.ExExaminee;
import com.jinbang.exam.domain.ExHomework;
import com.jinbang.exam.domain.ExProblemPaper;
import com.jinbang.exam.domain.ExScanBatch;
import com.jinbang.exam.domain.ExSheet;
import com.jinbang.exam.domain.ExSheetVersion;
import com.jinbang.exam.service.ExamService;
import com.jinbang.exam.service.FlowService;
import com.jinbang.exam.service.HomeworkService;
import com.jinbang.exam.service.MarkingService;
import com.jinbang.exam.service.PortalService;
import com.jinbang.exam.service.ScanService;
import com.jinbang.exam.service.SheetService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ExamController {
    private final SheetService sheetService;
    private final HomeworkService homeworkService;
    private final ExamService examService;
    private final ScanService scanService;
    private final MarkingService markingService;
    private final PortalService portalService;
    private final com.jinbang.exam.service.WorkbookService workbookService;
    private final com.jinbang.exam.service.DataCenterService dataCenterService;

    @Value("${jb.recognition.callback-token:}")
    private String callbackToken;

    public record BlocksReq(List<SheetService.Block> blocks) {
    }

    public record BatchReq(String sourceType, Long sourceId, String scanType) {
    }

    public record AssignStudentReq(Long studentId) {
    }

    public record ScoreReq(Long answerId, String itemNo, BigDecimal score, String reason) {
    }

    public record ObjectiveReq(Long answerId, String itemNo, String studentAnswer) {
    }

    public record ProblemReq(Long answerId, String itemNo, String problemType, String remark) {
    }

    public record MarkersReq(String itemNo, List<Long> identityIds) {
    }

    public record ActionReq(String action) {
    }

    public record ClassesReq(List<Long> classIds) {
    }

    public record ExamineeReq(Long studentId, String status) {
    }

    public record SheetBindReq(Long sheetId) {
    }

    // ---------------- 答题卡 ----------------

    @SaCheckPermission(value = {"homework:assign", "exam:create"}, mode = SaMode.OR)
    @PostMapping("/api/exam/sheets")
    public R<SheetService.SheetView> createSheet(@RequestBody SheetService.SheetReq req) {
        return R.ok(sheetService.create(LoginHelper.require(), req));
    }

    @GetMapping("/api/exam/sheets")
    public R<List<ExSheet>> sheets() {
        return R.ok(sheetService.list(LoginHelper.require()));
    }

    @GetMapping("/api/exam/sheets/{id}")
    public R<SheetService.SheetView> sheet(@PathVariable Long id) {
        return R.ok(sheetService.view(LoginHelper.require(), id));
    }

    @PutMapping("/api/exam/sheets/{id}/blocks")
    public R<SheetService.SheetView> updateBlocks(@PathVariable Long id, @RequestBody BlocksReq req) {
        return R.ok(sheetService.updateBlocks(LoginHelper.require(), id, req.blocks()));
    }

    @PostMapping("/api/exam/sheets/{id}/download")
    public R<SheetService.Download> download(@PathVariable Long id) {
        return R.ok(sheetService.download(LoginHelper.require(), id));
    }

    @GetMapping("/api/exam/sheets/{id}/versions")
    public R<List<ExSheetVersion>> versions(@PathVariable Long id) {
        sheetService.view(LoginHelper.require(), id);
        return R.ok(sheetService.versions(id));
    }

    // ---------------- 作业 ----------------

    @SaCheckPermission("homework:assign")
    @PostMapping("/api/exam/homeworks")
    public R<ExHomework> assign(@RequestBody HomeworkService.AssignReq req) {
        return R.ok(homeworkService.assign(LoginHelper.require(), req));
    }

    @SaCheckPermission("homework:assign")
    @GetMapping("/api/exam/homeworks")
    public R<List<HomeworkService.HomeworkCard>> homeworks(@RequestParam(required = false) Long classId) {
        return R.ok(homeworkService.list(LoginHelper.require(), classId, scanService));
    }

    @SaCheckPermission("homework:scan")
    @PostMapping("/api/exam/homeworks/{id}/complete-scan")
    public R<Void> completeScan(@PathVariable Long id) {
        homeworkService.completeScan(LoginHelper.require(), id);
        return R.ok();
    }

    @SaCheckPermission("homework:mark")
    @PostMapping("/api/exam/homeworks/{id}/finish")
    public R<Void> finish(@PathVariable Long id) {
        homeworkService.finishMarking(LoginHelper.require(), id);
        return R.ok();
    }

    @SaCheckPermission("homework:assign")
    @PostMapping("/api/exam/homeworks/{id}/close")
    public R<Void> close(@PathVariable Long id) {
        homeworkService.close(LoginHelper.require(), id);
        return R.ok();
    }

    @SaCheckPermission("homework:assign")
    @PostMapping("/api/exam/homeworks/{id}/publish-answer")
    public R<Void> publishAnswer(@PathVariable Long id) {
        homeworkService.publishAnswer(LoginHelper.require(), id);
        return R.ok();
    }

    // ---------------- 考试 ----------------

    @SaCheckPermission("exam:create")
    @PostMapping("/api/exam/exams")
    public R<ExExam> createExam(@RequestBody ExamService.CreateReq req) {
        return R.ok(examService.create(LoginHelper.require(), req));
    }

    @SaCheckPermission(value = {"exam:create", "exam:mark", "learning:view"}, mode = SaMode.OR)
    @GetMapping("/api/exam/exams")
    public R<List<ExExam>> exams(@RequestParam(required = false) String status) {
        return R.ok(examService.list(LoginHelper.require(), status));
    }

    @GetMapping("/api/exam/exams/{id}")
    public R<ExExam> exam(@PathVariable Long id) {
        return R.ok(examService.get(LoginHelper.require(), id));
    }

    @GetMapping("/api/open/exam/status-labels")
    public R<Map<String, String>> labels() {
        return R.ok(FlowService.examLabels());
    }

    @SaCheckPermission("exam:create")
    @PostMapping("/api/exam/exams/{id}/sheet")
    public R<ExExam> bindSheet(@PathVariable Long id, @RequestBody SheetBindReq req) {
        return R.ok(examService.bindSheet(LoginHelper.require(), id, req.sheetId()));
    }

    @SaCheckPermission("exam:create")
    @PostMapping("/api/exam/exams/{id}/examinees/import-classes")
    public R<Integer> importClasses(@PathVariable Long id, @RequestBody ClassesReq req) {
        return R.ok(examService.importFromClasses(examService.get(LoginHelper.require(), id), req.classIds()));
    }

    @GetMapping("/api/exam/exams/{id}/examinees")
    public R<List<ExExaminee>> examinees(@PathVariable Long id) {
        return R.ok(examService.examinees(LoginHelper.require(), id));
    }

    @SaCheckPermission("exam:create")
    @PostMapping("/api/exam/exams/{id}/examinees/status")
    public R<Void> examineeStatus(@PathVariable Long id, @RequestBody ExamineeReq req) {
        examService.setExamineeStatus(LoginHelper.require(), id, req.studentId(), req.status());
        return R.ok();
    }

    @SaCheckPermission(value = {"exam:create", "exam:publish"}, mode = SaMode.OR)
    @PostMapping("/api/exam/exams/{id}/flow")
    public R<ExExam> flow(@PathVariable Long id, @RequestBody ActionReq req) {
        return R.ok(examService.advance(LoginHelper.require(), id, req.action()));
    }

    @SaCheckPermission("exam:publish")
    @PostMapping("/api/exam/exams/{id}/publish")
    public R<ExExam> publish(@PathVariable Long id, @RequestBody(required = false) ClassesReq req) {
        return R.ok(examService.publish(LoginHelper.require(), id, req == null ? null : req.classIds()));
    }

    @SaCheckPermission("exam:publish")
    @PostMapping("/api/exam/exams/{id}/withdraw")
    public R<ExExam> withdraw(@PathVariable Long id) {
        return R.ok(examService.withdraw(LoginHelper.require(), id));
    }

    // ---------------- 扫描 ----------------

    @SaCheckPermission(value = {"homework:scan", "exam:scan"}, mode = SaMode.OR)
    @PostMapping("/api/exam/scan/batches")
    public R<ExScanBatch> createBatch(@RequestBody BatchReq req) {
        return R.ok(scanService.createBatch(LoginHelper.require(), req.sourceType(), req.sourceId(), req.scanType()));
    }

    /** 识别服务回调：服务间调用，用共享 token 鉴权（常量时间比较）。 */
    @PostMapping("/api/exam/recognition/callback")
    public R<ScanService.IngestResult> callback(@RequestHeader(value = "X-Recognition-Token", required = false) String token,
                                                @RequestBody ScanService.CallbackReq req) {
        if (token == null || callbackToken.isBlank() || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                callbackToken.getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(ErrorCode.FORBIDDEN, "识别服务凭证无效");
        }
        ExScanBatch batch = scanService.batchForCallback(req.batchId());
        return R.ok(TenantContext.withTenant(batch.getTenantId(), () -> scanService.ingest(batch, req)));
    }

    @GetMapping("/api/exam/{type}/{id}/stats")
    public R<ScanService.BatchStats> stats(@PathVariable String type, @PathVariable Long id) {
        return R.ok(scanService.stats(LoginHelper.require(), type.toUpperCase(), id));
    }

    @GetMapping("/api/exam/{type}/{id}/exceptions")
    public R<List<ExAnswer>> exceptions(@PathVariable String type, @PathVariable Long id) {
        return R.ok(scanService.exceptions(LoginHelper.require(), type.toUpperCase(), id));
    }

    @GetMapping("/api/exam/{type}/{id}/duplicates")
    public R<List<ExAnswer>> duplicates(@PathVariable String type, @PathVariable Long id) {
        return R.ok(scanService.duplicates(LoginHelper.require(), type.toUpperCase(), id));
    }

    @GetMapping("/api/exam/{type}/{id}/unsubmitted")
    public R<List<Long>> unsubmitted(@PathVariable String type, @PathVariable Long id) {
        return R.ok(scanService.unsubmitted(LoginHelper.require(), type.toUpperCase(), id));
    }

    /** 分配给该考生 / 重新分配（AC-19）。 */
    @PostMapping("/api/exam/answers/{id}/assign")
    public R<ExAnswer> assignStudent(@PathVariable Long id, @RequestBody AssignStudentReq req) {
        return R.ok(scanService.assign(LoginHelper.require(), id, req.studentId()));
    }

    @PostMapping("/api/exam/answers/{id}/restore")
    public R<ExAnswer> restore(@PathVariable Long id) {
        return R.ok(scanService.restore(LoginHelper.require(), id));
    }

    // ---------------- 阅卷 ----------------

    @GetMapping("/api/exam/{type}/{id}/marking/progress")
    public R<List<MarkingService.QuestionProgress>> progress(@PathVariable String type, @PathVariable Long id) {
        return R.ok(markingService.progress(LoginHelper.require(), type.toUpperCase(), id));
    }

    @GetMapping("/api/exam/{type}/{id}/marking/next")
    public R<MarkingService.NextItem> next(@PathVariable String type, @PathVariable Long id, @RequestParam String itemNo) {
        return R.ok(markingService.next(LoginHelper.require(), type.toUpperCase(), id, itemNo));
    }

    @SaCheckPermission(value = {"homework:mark", "exam:mark"}, mode = SaMode.OR)
    @PostMapping("/api/exam/marking/submit")
    public R<ExAnswer> submit(@RequestBody ScoreReq req) {
        return R.ok(markingService.submit(LoginHelper.require(), req.answerId(), req.itemNo(), req.score(), req.reason()));
    }

    @SaCheckPermission(value = {"homework:mark", "exam:mark"}, mode = SaMode.OR)
    @PostMapping("/api/exam/marking/objective")
    public R<ExAnswer> objective(@RequestBody ObjectiveReq req) {
        return R.ok(markingService.correctObjective(LoginHelper.require(), req.answerId(), req.itemNo(), req.studentAnswer()));
    }

    @GetMapping("/api/exam/{type}/{id}/marking/objective-matrix")
    public R<List<Map<String, Object>>> matrix(@PathVariable String type, @PathVariable Long id) {
        return R.ok(markingService.objectiveMatrix(LoginHelper.require(), type.toUpperCase(), id));
    }

    @SaCheckPermission(value = {"homework:assign", "exam:create"}, mode = SaMode.OR)
    @PostMapping("/api/exam/{type}/{id}/marking/assign")
    public R<Void> markers(@PathVariable String type, @PathVariable Long id, @RequestBody MarkersReq req) {
        markingService.assignMarkers(LoginHelper.require(), type.toUpperCase(), id, req.itemNo(), req.identityIds());
        return R.ok();
    }

    @SaCheckPermission(value = {"homework:mark", "exam:mark"}, mode = SaMode.OR)
    @PostMapping("/api/exam/{type}/{id}/marking/ai")
    public R<MarkingService.AiMarkResult> aiMark(@PathVariable String type, @PathVariable Long id, @RequestParam String itemNo) {
        return R.ok(markingService.aiMark(LoginHelper.require(), type.toUpperCase(), id, itemNo));
    }

    @PostMapping("/api/exam/problems")
    public R<ExProblemPaper> report(@RequestBody ProblemReq req) {
        return R.ok(markingService.report(LoginHelper.require(), req.answerId(), req.itemNo(), req.problemType(), req.remark()));
    }

    @GetMapping("/api/exam/{type}/{id}/problems")
    public R<List<ExProblemPaper>> problems(@PathVariable String type, @PathVariable Long id) {
        return R.ok(markingService.problems(LoginHelper.require(), type.toUpperCase(), id));
    }

    @PostMapping("/api/exam/problems/{id}/handle")
    public R<Void> handle(@PathVariable Long id) {
        markingService.handleProblem(LoginHelper.require(), id);
        return R.ok();
    }

    // ---------------- 学生端 / 家长端 ----------------

    @SaCheckPermission(value = {"student:self", "parent:self"}, mode = SaMode.OR)
    @GetMapping("/api/portal/results")
    public R<List<PortalService.ResultView>> results(@RequestParam(required = false) Long studentId) {
        return R.ok(portalService.results(LoginHelper.require(), studentId));
    }

    @SaCheckPermission(value = {"student:self", "parent:self"}, mode = SaMode.OR)
    @GetMapping("/api/portal/homeworks")
    public R<List<PortalService.HomeworkTodo>> todo(@RequestParam(required = false) Long studentId) {
        return R.ok(portalService.homeworks(LoginHelper.require(), studentId));
    }

    @SaCheckPermission("student:self")
    @PostMapping("/api/portal/homeworks/{id}/submit")
    public R<ExAnswer> submitOnline(@PathVariable Long id, @RequestBody HomeworkService.OnlineSubmit req) {
        var ci = LoginHelper.require();
        return R.ok(homeworkService.submitOnline(ci, id, portalService.resolveStudent(ci, null), req));
    }

    // ---------------- 教辅页面模板库（运营后台）/ 数据中心 ----------------

    @SaCheckPermission("op:resource")
    @PostMapping("/api/admin/workbook/pages")
    public R<com.jinbang.exam.domain.ExWorkbookPage> saveWorkbookPage(@RequestBody com.jinbang.exam.service.WorkbookService.PageReq req) {
        return R.ok(workbookService.save(req));
    }

    @GetMapping("/api/exam/workbook/{bookCode}/pages")
    public R<List<com.jinbang.exam.domain.ExWorkbookPage>> workbookPages(@PathVariable String bookCode) {
        return R.ok(workbookService.pages(bookCode));
    }

    @SaCheckPermission("school:datacenter")
    @GetMapping("/api/exam/datacenter/scan")
    public R<com.jinbang.exam.service.DataCenterService.Report> dataCenter(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return R.ok(dataCenterService.report(from, to));
    }
}
