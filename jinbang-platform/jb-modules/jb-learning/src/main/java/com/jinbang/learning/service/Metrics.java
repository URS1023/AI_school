package com.jinbang.learning.service;

import com.jinbang.api.exam.ExamDataApi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 学情指标的唯一实现（PRD 6.6「指标口径」，所有报表必须用它）：
 * <ul>
 *   <li>有效答卷 = 已扫描、已识别且全部题目阅完；缺考 / 免考 / 作废 / 未阅完 / 未交不计入均分和得分率，单独列人数（AC-08）</li>
 *   <li>题目得分率 = 该题实得分之和 ÷（该题满分 × 有效人数）</li>
 *   <li>平均得分率 = 实得总分之和 ÷ 应得满分之和，按分值加权（AC-07）</li>
 *   <li>正确率：客观题答对人数 ÷ 有效人数；主观题得满分才算「正确」</li>
 *   <li>成绩分档默认 C：优秀 [85,100]、良好 [75,85)、合格 [60,75)、不合格 [0,60)，左闭右开，满分归优秀（D3）</li>
 *   <li>临界生 = 及格线 ± 5% 满分（含边界）</li>
 *   <li>任何比率都夹在 [0,1]、失分不为负（缺陷 21、22，AC-18 的兜底）</li>
 * </ul>
 */
public final class Metrics {
    private Metrics() {
    }

    public record Bands(BigDecimal excellent, BigDecimal good, BigDecimal pass, BigDecimal critical) {
        public static final Bands DEFAULT = new Bands(BigDecimal.valueOf(85), BigDecimal.valueOf(75), BigDecimal.valueOf(60),
                BigDecimal.valueOf(5));
    }

    public record Counts(int total, int valid, int absent, int exempt, int voided, int unfinished, int missing, int late) {
    }

    public record ItemStat(String no, String type, boolean objective, BigDecimal fullScore, BigDecimal avg,
                           BigDecimal scoreRate, BigDecimal correctRate) {
    }

    public record StudentRow(Long studentId, Long classId, BigDecimal score, BigDecimal rate, String band, int classRank,
                             int gradeRank, boolean critical) {
    }

    public record Report(Counts counts, BigDecimal avgScore, BigDecimal avgRate, BigDecimal avgHundred,
                         BigDecimal maxScore, BigDecimal minScore, BigDecimal fullScore, Map<String, Integer> bands,
                         List<ItemStat> items, Map<String, BigDecimal> typeRates, List<StudentRow> students) {
    }

    public static BigDecimal rate(BigDecimal got, BigDecimal full) {
        if (got == null || full == null || full.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal r = got.divide(full, 4, RoundingMode.HALF_UP);
        return r.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    public static String band(BigDecimal rate, Bands b) {
        BigDecimal pct = rate.multiply(BigDecimal.valueOf(100));
        if (pct.compareTo(b.excellent()) >= 0) {
            return "优秀";
        }
        if (pct.compareTo(b.good()) >= 0) {
            return "良好";
        }
        if (pct.compareTo(b.pass()) >= 0) {
            return "合格";
        }
        return "不合格";
    }

    public static boolean critical(BigDecimal rate, Bands b) {
        BigDecimal pct = rate.multiply(BigDecimal.valueOf(100));
        return pct.subtract(b.pass()).abs().compareTo(b.critical()) <= 0;
    }

    /** 实得分夹在 [0, 满分]（识别或导入出错时不会把得分率算到 100% 以上）。 */
    static BigDecimal clamp(BigDecimal score, BigDecimal full) {
        if (score == null) {
            return BigDecimal.ZERO;
        }
        return score.max(BigDecimal.ZERO).min(full);
    }

    public static Report compute(ExamDataApi.SourceData d, Bands b, Predicate<ExamDataApi.StudentResult> filter) {
        List<ExamDataApi.StudentResult> all = d.students();
        List<ExamDataApi.StudentResult> scoped = all.stream().filter(filter).toList();
        Counts counts = new Counts(scoped.size(), cnt(scoped, "VALID"), cnt(scoped, "ABSENT"), cnt(scoped, "EXEMPT"),
                cnt(scoped, "VOID"), cnt(scoped, "UNFINISHED"), cnt(scoped, "MISSING"),
                (int) scoped.stream().filter(ExamDataApi.StudentResult::late).count());
        BigDecimal full = d.items().stream().map(ExamDataApi.ItemDef::fullScore).reduce(BigDecimal.ZERO, BigDecimal::add);

        // 年级排名用全部有效答卷，班级排名用同班有效答卷
        List<ExamDataApi.StudentResult> gradeValid = all.stream().filter(s -> "VALID".equals(s.status())).toList();
        Map<Long, BigDecimal> totals = gradeValid.stream().collect(Collectors.toMap(ExamDataApi.StudentResult::studentId,
                s -> studentTotal(s, d.items())));
        List<ExamDataApi.StudentResult> valid = scoped.stream().filter(s -> "VALID".equals(s.status())).toList();

        BigDecimal got = valid.stream().map(s -> totals.get(s.studentId())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal should = full.multiply(BigDecimal.valueOf(valid.size()));
        BigDecimal avgRate = rate(got, should);
        BigDecimal avgScore = valid.isEmpty() ? null : got.divide(BigDecimal.valueOf(valid.size()), 2, RoundingMode.HALF_UP);

        Map<String, Integer> bands = new LinkedHashMap<>();
        for (String k : List.of("优秀", "良好", "合格", "不合格")) {
            bands.put(k, 0);
        }
        List<StudentRow> rows = new ArrayList<>();
        for (ExamDataApi.StudentResult s : valid) {
            BigDecimal t = totals.get(s.studentId());
            BigDecimal r = rate(t, full);
            bands.merge(band(r, b), 1, Integer::sum);
            int gradeRank = 1 + (int) totals.values().stream().filter(x -> x.compareTo(t) > 0).count();
            int classRank = 1 + (int) gradeValid.stream().filter(x -> x.classId().equals(s.classId()))
                    .filter(x -> totals.get(x.studentId()).compareTo(t) > 0).count();
            rows.add(new StudentRow(s.studentId(), s.classId(), t, r, band(r, b), classRank, gradeRank, critical(r, b)));
        }
        rows.sort(Comparator.comparing(StudentRow::score).reversed());

        List<ItemStat> items = new ArrayList<>();
        Map<String, BigDecimal[]> typeAgg = new LinkedHashMap<>();
        for (ExamDataApi.ItemDef it : d.items()) {
            BigDecimal sum = BigDecimal.ZERO;
            int correct = 0;
            for (ExamDataApi.StudentResult s : valid) {
                BigDecimal sc = clamp(s.itemScores().get(it.no()), it.fullScore());
                sum = sum.add(sc);
                if (sc.compareTo(it.fullScore()) == 0) {
                    correct++;
                }
            }
            BigDecimal should1 = it.fullScore().multiply(BigDecimal.valueOf(valid.size()));
            items.add(new ItemStat(it.no(), it.type(), it.objective(), it.fullScore(),
                    valid.isEmpty() ? null : sum.divide(BigDecimal.valueOf(valid.size()), 2, RoundingMode.HALF_UP),
                    rate(sum, should1), valid.isEmpty() ? BigDecimal.ZERO
                    : BigDecimal.valueOf(correct).divide(BigDecimal.valueOf(valid.size()), 4, RoundingMode.HALF_UP)));
            BigDecimal[] agg = typeAgg.computeIfAbsent(it.type(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            agg[0] = agg[0].add(sum);
            agg[1] = agg[1].add(should1);
        }
        Map<String, BigDecimal> typeRates = new LinkedHashMap<>();
        typeAgg.forEach((k, v) -> typeRates.put(k, rate(v[0], v[1])));
        return new Report(counts, avgScore, avgRate, avgRate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP),
                valid.stream().map(s -> totals.get(s.studentId())).max(Comparator.naturalOrder()).orElse(null),
                valid.stream().map(s -> totals.get(s.studentId())).min(Comparator.naturalOrder()).orElse(null),
                full, bands, items, typeRates, rows);
    }

    /** 学生总分按逐题得分重算并夹紧（不直接信任答卷上的 total）。 */
    static BigDecimal studentTotal(ExamDataApi.StudentResult s, List<ExamDataApi.ItemDef> items) {
        BigDecimal t = BigDecimal.ZERO;
        for (ExamDataApi.ItemDef it : items) {
            t = t.add(clamp(s.itemScores().get(it.no()), it.fullScore()));
        }
        return t;
    }

    private static int cnt(List<ExamDataApi.StudentResult> l, String status) {
        return (int) l.stream().filter(s -> status.equals(s.status())).count();
    }

    public static final Set<String> NOT_IN_AVERAGE = Set.of("ABSENT", "EXEMPT", "VOID", "UNFINISHED", "MISSING");
}
