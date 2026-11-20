package com.jinbang.learning.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.jinbang.api.ai.AiApi;
import com.jinbang.common.core.PageQuery;
import com.jinbang.common.core.PageResult;
import com.jinbang.common.core.R;
import com.jinbang.common.security.LoginHelper;
import com.jinbang.learning.domain.LnBandSetting;
import com.jinbang.learning.domain.LnWrongBook;
import com.jinbang.learning.domain.LnWrongItem;
import com.jinbang.learning.service.LearningService;
import com.jinbang.learning.service.WrongService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class LearningController {
    private final LearningService learningService;
    private final WrongService wrongService;

    public record KeyReq(String itemNo, boolean on) {
    }

    public record BookReq(String name, String subject, Long studentId, List<Long> wrongIds) {
    }

    public record SheetReq(Long sheetId) {
    }

    @SaCheckPermission("learning:view")
    @GetMapping("/api/learning/{type}/{id}/report")
    public R<LearningService.SourceReport> report(@PathVariable String type, @PathVariable Long id,
                                                  @RequestParam(required = false) Long classId) {
        return R.ok(learningService.report(LoginHelper.require(), type.toUpperCase(), id, classId));
    }

    @SaCheckPermission("learning:view")
    @GetMapping("/api/learning/{type}/{id}/students/{studentId}")
    public R<LearningService.StudentDetail> student(@PathVariable String type, @PathVariable Long id,
                                                    @PathVariable Long studentId) {
        return R.ok(learningService.studentDetail(LoginHelper.require(), type.toUpperCase(), id, studentId));
    }

    @SaCheckPermission("learning:view")
    @GetMapping("/api/learning/classes/{classId}/trend")
    public R<LearningService.ClassTrend> trend(@PathVariable Long classId, @RequestParam(required = false) String subject,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return R.ok(learningService.classTrend(LoginHelper.require(), classId, subject, from, to));
    }

    @SaCheckPermission("learning:view")
    @GetMapping("/api/learning/{type}/{id}/review")
    public R<List<LearningService.ReviewItem>> review(@PathVariable String type, @PathVariable Long id,
                                                      @RequestParam(required = false) Long classId,
                                                      @RequestParam(required = false) BigDecimal minRate,
                                                      @RequestParam(required = false) BigDecimal maxRate,
                                                      @RequestParam(defaultValue = "false") boolean keyOnly) {
        return R.ok(learningService.review(LoginHelper.require(), type.toUpperCase(), id, classId, minRate, maxRate, keyOnly));
    }

    @SaCheckPermission("learning:view")
    @PostMapping("/api/learning/{type}/{id}/key")
    public R<Void> key(@PathVariable String type, @PathVariable Long id, @RequestBody KeyReq req) {
        learningService.toggleKey(LoginHelper.require(), type.toUpperCase(), id, req.itemNo(), req.on());
        return R.ok();
    }

    @SaCheckPermission("school:datacenter")
    @GetMapping("/api/learning/big-screen")
    public R<List<LearningService.SubjectBoard>> bigScreen(@RequestParam(defaultValue = "30") int days) {
        return R.ok(learningService.bigScreen(LoginHelper.require(), days));
    }

    @SaCheckPermission("school:org:manage")
    @PostMapping("/api/learning/bands")
    public R<LnBandSetting> bands(@RequestBody LnBandSetting s) {
        return R.ok(learningService.saveBands(s));
    }

    // ---------------- 错题 ----------------

    @SaCheckPermission(value = {"wrong:view", "parent:self"}, mode = SaMode.OR)
    @GetMapping("/api/learning/wrong")
    public R<PageResult<LnWrongItem>> wrong(@RequestParam(required = false) List<Long> classIds,
                                            @RequestParam(required = false) List<Long> studentIds,
                                            @RequestParam(required = false) String subject,
                                            @RequestParam(required = false) String itemType,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(required = false) Boolean mastered, PageQuery page) {
        return R.ok(wrongService.list(LoginHelper.require(), new WrongService.Filter(classIds, studentIds, subject, itemType,
                from, to, mastered, page)));
    }

    @SaCheckPermission("wrong:view")
    @PostMapping("/api/learning/wrong/{id}/master")
    public R<Void> master(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean mastered) {
        wrongService.master(LoginHelper.require(), id, mastered);
        return R.ok();
    }

    @SaCheckPermission("wrong:view")
    @PostMapping("/api/learning/wrong/{id}/variants")
    public R<AiApi.AiResult> variants(@PathVariable Long id, @RequestParam(defaultValue = "2") int count) {
        return R.ok(wrongService.variants(LoginHelper.require(), id, count));
    }

    @SaCheckPermission("wrong:view")
    @PostMapping("/api/learning/wrong-books")
    public R<LnWrongBook> createBook(@RequestBody BookReq req) {
        return R.ok(wrongService.createBook(LoginHelper.require(), req.name(), req.subject(), req.studentId(), req.wrongIds()));
    }

    @SaCheckPermission("wrong:view")
    @GetMapping("/api/learning/wrong-books")
    public R<List<LnWrongBook>> books(@RequestParam(required = false) String subject) {
        return R.ok(wrongService.books(LoginHelper.require(), subject));
    }

    @SaCheckPermission("wrong:view")
    @PostMapping("/api/learning/wrong-books/{id}/sheet")
    public R<LnWrongBook> sheet(@PathVariable Long id, @RequestBody SheetReq req) {
        return R.ok(wrongService.markSheet(LoginHelper.require(), id, req.sheetId()));
    }

    @SaCheckPermission("wrong:view")
    @PostMapping("/api/learning/wrong-books/{id}/delete")
    public R<Void> deleteBook(@PathVariable Long id) {
        wrongService.deleteBook(LoginHelper.require(), id);
        return R.ok();
    }
}
