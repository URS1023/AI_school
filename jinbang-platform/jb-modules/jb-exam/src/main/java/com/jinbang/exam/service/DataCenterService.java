package com.jinbang.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.exam.domain.ExScanBatch;
import com.jinbang.exam.mapper.ExScanBatchMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 数据中心（PRD 6.10）：学科扫描量（按日期 × 学科）、教师扫描量（扫描作业数、总扫描次数）。本校数据，按租户隔离。 */
@Service
@RequiredArgsConstructor
public class DataCenterService {
    private final ExScanBatchMapper batchMapper;
    private final SourceService sourceService;

    public record DailyVolume(LocalDate date, String subject, long images, int batches) {
    }

    public record TeacherVolume(Long userId, Set<String> subjects, int works, int scans, long images) {
    }

    public record ScanReport(List<DailyVolume> daily, List<TeacherVolume> teachers) {
    }

    public ScanReport report(LocalDate from, LocalDate to) {
        List<ExScanBatch> batches = batchMapper.selectList(Wrappers.<ExScanBatch>lambdaQuery()
                .ge(from != null, ExScanBatch::getCreatedAt, from == null ? null : from.atStartOfDay())
                .le(to != null, ExScanBatch::getCreatedAt, to == null ? null : to.atTime(23, 59, 59)));
        Map<String, DailyVolume> daily = new TreeMap<>();
        Map<Long, Set<String>> teacherWorks = new HashMap<>();
        Map<Long, Set<String>> teacherSubjects = new HashMap<>();
        Map<Long, int[]> teacherScans = new HashMap<>();
        Map<Long, long[]> teacherImages = new HashMap<>();
        Map<String, String> subjectCache = new HashMap<>();
        for (ExScanBatch b : batches) {
            String key = b.getSourceType() + ":" + b.getSourceId();
            String subject = subjectCache.computeIfAbsent(key, k -> {
                String sub = sourceService.load(b.getSourceType(), b.getSourceId()).subject();
                return sub == null ? "未分科" : sub;
            });
            LocalDate d = b.getCreatedAt().toLocalDate();
            String dk = d + "|" + subject;
            DailyVolume old = daily.get(dk);
            daily.put(dk, new DailyVolume(d, subject, (old == null ? 0 : old.images()) + b.getImageCount(),
                    (old == null ? 0 : old.batches()) + 1));
            Long u = b.getScannedBy();
            teacherWorks.computeIfAbsent(u, k -> new HashSet<>()).add(key);
            teacherSubjects.computeIfAbsent(u, k -> new HashSet<>()).add(subject);
            teacherScans.computeIfAbsent(u, k -> new int[1])[0]++;
            teacherImages.computeIfAbsent(u, k -> new long[1])[0] += b.getImageCount();
        }
        List<TeacherVolume> teachers = new ArrayList<>();
        teacherWorks.forEach((u, works) -> teachers.add(new TeacherVolume(u, teacherSubjects.get(u), works.size(),
                teacherScans.get(u)[0], teacherImages.get(u)[0])));
        return new ScanReport(new ArrayList<>(daily.values()), teachers);
    }
}
