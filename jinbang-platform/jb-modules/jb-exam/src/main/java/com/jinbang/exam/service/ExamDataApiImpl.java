package com.jinbang.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.exam.ExamDataApi;
import com.jinbang.exam.domain.ExAnswer;
import com.jinbang.exam.domain.ExAnswerItem;
import com.jinbang.exam.domain.ExExam;
import com.jinbang.exam.domain.ExExaminee;
import com.jinbang.exam.domain.ExHomework;
import com.jinbang.exam.mapper.ExAnswerMapper;
import com.jinbang.exam.mapper.ExExamMapper;
import com.jinbang.exam.mapper.ExExamineeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 给学情模块的作答数据：答卷状态按「指标口径」归类（有效 / 缺考 / 免考 / 作废 / 未阅完 / 未交）。 */
@Service
@RequiredArgsConstructor
public class ExamDataApiImpl implements ExamDataApi {
    private final SourceService sourceService;
    private final SheetService sheetService;
    private final ScoringService scoring;
    private final ExAnswerMapper answerMapper;
    private final ExExamMapper examMapper;
    private final ExExamineeMapper examineeMapper;

    @Override
    public SourceData load(String sourceType, Long sourceId) {
        SourceService.Source s = sourceService.load(sourceType, sourceId);
        List<ExAnswer> currentAnswers = answerMapper.selectList(Wrappers.<ExAnswer>lambdaQuery().eq(ExAnswer::getSourceType, sourceType)
                .eq(ExAnswer::getSourceId, sourceId).eq(ExAnswer::getIsCurrent, true)
                .in(ExAnswer::getAnswerStatus, "MARKING", "MARKED"));
        Map<Long, List<ExAnswerItem>> itemsByAnswer = new HashMap<>();
        currentAnswers.forEach(a -> itemsByAnswer.put(a.getId(), scoring.items(a.getId())));
        List<ItemDef> items;
        if (s.sheetVersionId() != null) {
            items = sheetService.blocks(sheetService.version(s.sheetVersionId())).stream()
                    .map(b -> new ItemDef(b.no(), b.type(), b.objective(), b.fullScore(), b.questionId())).toList();
        } else {
            // 教辅作业：题目来自各页模板，取实际扫描到的页面题目并集
            Map<String, ItemDef> union = new LinkedHashMap<>();
            itemsByAnswer.values().forEach(l -> l.forEach(it -> union.putIfAbsent(it.getItemNo(),
                    new ItemDef(it.getItemNo(), it.getItemType(), Boolean.TRUE.equals(it.getObjective()), it.getFullScore(), null))));
            items = new ArrayList<>(union.values());
        }
        BigDecimal full = items.stream().map(ItemDef::fullScore).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<Long, Long> targets = sourceService.targets(s);
        Map<Long, String> examStatus = sourceService.examineeStatus(s);
        Map<Long, List<ExAnswer>> answers = currentAnswers.stream().collect(Collectors.groupingBy(ExAnswer::getStudentId));
        List<StudentResult> students = new ArrayList<>();
        targets.forEach((studentId, classId) -> {
            String es = examStatus.getOrDefault(studentId, "NORMAL");
            List<ExAnswer> mine = answers.getOrDefault(studentId, List.of());
            String status;
            if (!"NORMAL".equals(es)) {
                status = es;
            } else if (mine.isEmpty()) {
                status = s.isExam() ? "ABSENT" : "MISSING";
            } else {
                status = mine.stream().allMatch(a -> "MARKED".equals(a.getAnswerStatus())) ? "VALID" : "UNFINISHED";
            }
            Map<String, BigDecimal> scores = new LinkedHashMap<>();
            Map<String, String> ans = new LinkedHashMap<>();
            BigDecimal total = mine.isEmpty() ? null : BigDecimal.ZERO;
            for (ExAnswer a : mine) {
                for (ExAnswerItem it : itemsByAnswer.get(a.getId())) {
                    scores.put(it.getItemNo(), it.getScore());
                    ans.put(it.getItemNo(), it.getStudentAnswer());
                }
                total = total.add(a.getTotalScore() == null ? BigDecimal.ZERO : a.getTotalScore());
            }
            students.add(new StudentResult(studentId, classId, status, total, full, scores, ans,
                    mine.stream().anyMatch(a -> Boolean.TRUE.equals(a.getLateFlag()))));
        });
        return new SourceData(s.type(), s.id(), s.tenantId(), s.title(), s.subject(), s.time(), s.classIds(), items, students);
    }

    @Override
    public List<SourceBrief> listSources(Set<Long> classIds, String subject, LocalDateTime from, LocalDateTime to) {
        List<SourceBrief> out = new ArrayList<>();
        for (ExHomework h : sourceService.homeworksOfClasses(classIds)) {
            if ((subject == null || subject.equals(h.getSubject())) && in(h.getCreatedAt(), from, to)) {
                out.add(new SourceBrief(SourceService.HOMEWORK, h.getId(), h.getTitle(), h.getSubject(), h.getCreatedAt(),
                        SourceService.ids(h.getClassIds())));
            }
        }
        if (!classIds.isEmpty()) {
            Map<Long, Set<Long>> examClasses = examineeMapper.selectList(Wrappers.<ExExaminee>lambdaQuery()
                            .in(ExExaminee::getClassId, classIds)).stream()
                    .collect(Collectors.groupingBy(ExExaminee::getExamId, Collectors.mapping(ExExaminee::getClassId, Collectors.toSet())));
            if (!examClasses.isEmpty()) {
                for (ExExam e : examMapper.selectByIds(examClasses.keySet())) {
                    LocalDateTime t = e.getExamTime() == null ? e.getCreatedAt() : e.getExamTime();
                    if ((subject == null || subject.equals(e.getSubject())) && in(t, from, to)) {
                        out.add(new SourceBrief(SourceService.EXAM, e.getId(), e.getExamName(), e.getSubject(), t,
                                examClasses.get(e.getId())));
                    }
                }
            }
        }
        out.sort(Comparator.comparing(SourceBrief::time));
        return out;
    }

    private static boolean in(LocalDateTime t, LocalDateTime from, LocalDateTime to) {
        return t != null && (from == null || !t.isBefore(from)) && (to == null || !t.isAfter(to));
    }
}
