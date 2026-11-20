package com.jinbang.learning.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.ai.AiApi;
import com.jinbang.api.exam.ExamDataApi;
import com.jinbang.api.exam.ScoresChangedEvent;
import com.jinbang.api.resource.QuestionApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.core.PageQuery;
import com.jinbang.common.core.PageResult;
import com.jinbang.common.datascope.DataScopeProvider;
import com.jinbang.common.datascope.VisibleScope;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.IdentityType;
import com.jinbang.common.tenant.TenantContext;
import com.jinbang.learning.domain.LnWrongBook;
import com.jinbang.learning.domain.LnWrongItem;
import com.jinbang.learning.mapper.LnWrongBookMapper;
import com.jinbang.learning.mapper.LnWrongItemMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 错题重练（PRD 6.7）：成绩变化时自动沉淀错题；错题列表、掌握标记、错题本、AI 变式题。 */
@Service
@RequiredArgsConstructor
public class WrongService {
    private final LnWrongItemMapper wrongMapper;
    private final LnWrongBookMapper bookMapper;
    private final ExamDataApi examData;
    private final QuestionApi questionApi;
    private final AiApi aiApi;
    private final DataScopeProvider dataScope;

    /** 成绩变化 → 重建该次作业 / 考试的错题（已标记掌握的保留）。 */
    @EventListener
    public void onScoresChanged(ScoresChangedEvent e) {
        // 错题是派生数据：重建失败只记日志，不能让阅卷、扫描的主流程回滚；下次成绩变化会再次重建
        try {
            TenantContext.withTenant(e.tenantId(), () -> rebuild(e.sourceType(), e.sourceId()));
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(WrongService.class).error("错题重建失败 {}:{}", e.sourceType(), e.sourceId(), ex);
        }
    }

    @Transactional
    public void rebuild(String type, Long id) {
        ExamDataApi.SourceData d = examData.load(type, id);
        wrongMapper.delete(Wrappers.<LnWrongItem>lambdaQuery().eq(LnWrongItem::getSourceType, type)
                .eq(LnWrongItem::getSourceId, id).eq(LnWrongItem::getMastered, false));
        Set<String> mastered = wrongMapper.selectList(Wrappers.<LnWrongItem>lambdaQuery().eq(LnWrongItem::getSourceType, type)
                .eq(LnWrongItem::getSourceId, id)).stream().map(w -> w.getStudentId() + ":" + w.getItemNo()).collect(Collectors.toSet());
        for (ExamDataApi.StudentResult s : d.students()) {
            if (!"VALID".equals(s.status()) && !"UNFINISHED".equals(s.status())) {
                continue;
            }
            for (ExamDataApi.ItemDef it : d.items()) {
                BigDecimal sc = s.itemScores().get(it.no());
                if (sc == null || sc.compareTo(it.fullScore()) >= 0 || mastered.contains(s.studentId() + ":" + it.no())) {
                    continue;
                }
                LnWrongItem w = new LnWrongItem();
                w.setStudentId(s.studentId());
                w.setClassId(s.classId());
                w.setSourceType(type);
                w.setSourceId(id);
                w.setSourceTitle(d.title());
                w.setSubject(d.subject());
                w.setItemNo(it.no());
                w.setItemType(it.type());
                w.setQuestionId(it.questionId());
                w.setScore(sc.max(BigDecimal.ZERO));
                w.setFullScore(it.fullScore());
                String ans = s.itemAnswers().get(it.no());
                w.setStudentAnswer(ans == null ? null : ans.substring(0, Math.min(500, ans.length())));
                w.setMastered(false);
                w.setOccurredAt(d.time());
                wrongMapper.insert(w);
            }
        }
    }

    public record Filter(List<Long> classIds, List<Long> studentIds, String subject, String itemType, LocalDate from,
                         LocalDate to, Boolean mastered, PageQuery page) {
    }

    /** 教师看所教班级的错题；学生只看自己；家长只看绑定的孩子（R7）。 */
    public PageResult<LnWrongItem> list(CurrentIdentity ci, Filter f) {
        VisibleScope scope = dataScope.current();
        LambdaQueryWrapper<LnWrongItem> w = Wrappers.lambdaQuery();
        if (ci.type() == IdentityType.STUDENT || ci.type() == IdentityType.PARENT) {
            BizException.throwIf(scope.studentIds().isEmpty(), ErrorCode.FORBIDDEN);
            w.in(LnWrongItem::getStudentId, scope.studentIds());
            if (f.studentIds() != null && !f.studentIds().isEmpty()) {
                BizException.throwIf(!scope.studentIds().containsAll(f.studentIds()), ErrorCode.FORBIDDEN);
                w.in(LnWrongItem::getStudentId, f.studentIds());
            }
        } else if (!scope.isSchoolWide()) {
            Set<Long> classes = f.classIds() == null || f.classIds().isEmpty() ? scope.anyClassIds() : Set.copyOf(f.classIds());
            for (Long c : classes) {
                BizException.throwIf(!scope.anyClassIds().contains(c) && scope.schoolSubjects().isEmpty(), ErrorCode.FORBIDDEN);
            }
            if (classes.isEmpty() && scope.schoolSubjects().isEmpty()) {
                return new PageResult<>(0, List.of());
            }
            if (!classes.isEmpty()) {
                w.in(LnWrongItem::getClassId, classes);
            }
            // 任课教师只看自己所教学科
            Set<String> subjects = new java.util.HashSet<>(scope.schoolSubjects());
            classes.forEach(c -> subjects.addAll(scope.classSubjects().getOrDefault(c, Set.of())));
            boolean full = classes.stream().allMatch(scope.fullClassIds()::contains);
            if (!full && !subjects.isEmpty()) {
                w.in(LnWrongItem::getSubject, subjects);
            }
            if (f.studentIds() != null && !f.studentIds().isEmpty()) {
                w.in(LnWrongItem::getStudentId, f.studentIds());
            }
        } else {
            w.in(f.classIds() != null && !f.classIds().isEmpty(), LnWrongItem::getClassId, f.classIds())
                    .in(f.studentIds() != null && !f.studentIds().isEmpty(), LnWrongItem::getStudentId, f.studentIds());
        }
        w.eq(f.subject() != null, LnWrongItem::getSubject, f.subject()).eq(f.itemType() != null, LnWrongItem::getItemType, f.itemType())
                .ge(f.from() != null, LnWrongItem::getOccurredAt, f.from() == null ? null : f.from().atStartOfDay())
                .le(f.to() != null, LnWrongItem::getOccurredAt, f.to() == null ? null : f.to().atTime(23, 59, 59))
                .eq(f.mastered() != null, LnWrongItem::getMastered, f.mastered())
                .orderByDesc(LnWrongItem::getOccurredAt).orderByAsc(LnWrongItem::getItemNo);
        return PageResult.of(wrongMapper.selectPage((f.page() == null ? new PageQuery() : f.page()).toPage(), w));
    }

    private LnWrongItem visible(CurrentIdentity ci, Long id) {
        LnWrongItem w = BizException.notNull(wrongMapper.selectById(id), "错题");
        VisibleScope scope = dataScope.current();
        boolean ok = scope.isSchoolWide() || scope.canSeeStudent(w.getStudentId()) || scope.canSee(w.getClassId(), w.getSubject());
        if (!ok) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return w;
    }

    public void master(CurrentIdentity ci, Long id, boolean mastered) {
        LnWrongItem w = visible(ci, id);
        w.setMastered(mastered);
        w.setMasteredAt(mastered ? LocalDateTime.now() : null);
        wrongMapper.updateById(w);
    }

    /** AI 变式题（PRD 6.7 ➕P0）：同知识点 1-3 道，按 AI 出题计费，失败不扣费。 */
    public AiApi.AiResult variants(CurrentIdentity ci, Long id, int count) {
        LnWrongItem w = visible(ci, id);
        QuestionApi.QuestionBrief q = questionApi.getQuestion(w.getQuestionId());
        String base = q == null ? "「" + w.getSourceTitle() + "」第 " + w.getItemNo() + " 题（" + w.getItemType() + "）"
                : q.stem() + (q.knowledge() == null ? "" : "（知识点：" + q.knowledge() + "）");
        int n = Math.max(1, Math.min(count, 3));
        return aiApi.run(ci.userId(), "question", "请针对下面这道错题生成 " + n + " 道同知识点的变式题：" + base,
                Map.of("count", n, "subject", w.getSubject() == null ? "" : w.getSubject(), "difficulty", "中等"));
    }

    // ---------------- 错题本 ----------------

    public LnWrongBook createBook(CurrentIdentity ci, String name, String subject, Long studentId, List<Long> wrongIds) {
        BizException.throwIf(wrongIds == null || wrongIds.isEmpty(), ErrorCode.BAD_REQUEST, "请选择错题");
        wrongIds.forEach(id -> visible(ci, id));
        LnWrongBook b = new LnWrongBook();
        b.setOwnerUserId(ci.userId());
        b.setBookName(name == null || name.isBlank()
                ? LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日")) + (subject == null ? "" : subject) + "错题本"
                : name);
        b.setSubject(subject);
        b.setStudentId(studentId);
        b.setWrongIds(wrongIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        b.setSheetMade(false);
        bookMapper.insert(b);
        return b;
    }

    public List<LnWrongBook> books(CurrentIdentity ci, String subject) {
        return bookMapper.selectList(Wrappers.<LnWrongBook>lambdaQuery().eq(LnWrongBook::getOwnerUserId, ci.userId())
                .eq(subject != null, LnWrongBook::getSubject, subject).orderByDesc(LnWrongBook::getCreatedAt));
    }

    public LnWrongBook markSheet(CurrentIdentity ci, Long bookId, Long sheetId) {
        LnWrongBook b = BizException.notNull(bookMapper.selectById(bookId), "错题本");
        BizException.throwIf(!b.getOwnerUserId().equals(ci.userId()), ErrorCode.FORBIDDEN);
        b.setSheetMade(true);
        b.setSheetId(sheetId);
        bookMapper.updateById(b);
        return b;
    }

    public void deleteBook(CurrentIdentity ci, Long bookId) {
        LnWrongBook b = BizException.notNull(bookMapper.selectById(bookId), "错题本");
        BizException.throwIf(!b.getOwnerUserId().equals(ci.userId()), ErrorCode.FORBIDDEN);
        bookMapper.deleteById(bookId);
    }
}
