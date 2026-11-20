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
        List<ItemDef> items = s.sheetVersionId() == null ? List.of() : sheetService.blocks(sheetService.version(s.sheetVersionId()))
                .stream().map(b -> new ItemDef(b.no(), b.type(), b.objective(), b.fullScore(), b.questionId())).toList();
        BigDecimal full = items.stream().map(ItemDef::fullScore).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<Long, Long> targets = sourceService.targets(s);
        Map<Long, String> examStatus = sourceService.examineeStatus(s);
        Map<Long, ExAnswer> answers = new HashMap<>();
        answerMapper.selectList(Wrappers.<ExAnswer>lambdaQuery().eq(ExAnswer::getSourceType, sourceType)
                        .eq(ExAnswer::getSourceId, sourceId).eq(ExAnswer::getIsCurrent, true)
                        .in(ExAnswer::getAnswerStatus, "MARKING", "MARKED"))
                .forEach(a -> answers.put(a.getStudentId(), a));
        List<StudentResult> students = new ArrayList<>();
        targets.forEach((studentId, classId) -> {
            String es = examStatus.getOrDefault(studentId, "NORMAL");
            ExAnswer a = answers.get(studentId);
            String status;
            if (!"NORMAL".equals(es)) {
                status = es;
            } else if (a == null) {
                status = s.isExam() ? "ABSENT" : "MISSING";
            } else {
                status = "MARKED".equals(a.getAnswerStatus()) ? "VALID" : "UNFINISHED";
            }
            Map<String, BigDecimal> scores = new LinkedHashMap<>();
            Map<String, String> ans = new LinkedHashMap<>();
            if (a != null) {
                for (ExAnswerItem it : scoring.items(a.getId())) {
                    scores.put(it.getItemNo(), it.getScore());
                    ans.put(it.getItemNo(), it.getStudentAnswer());
                }
            }
            students.add(new StudentResult(studentId, classId, status, a == null ? null : a.getTotalScore(), full, scores,
                    ans, a != null && Boolean.TRUE.equals(a.getLateFlag())));
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
