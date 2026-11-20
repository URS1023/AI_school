package com.jinbang.learning.service;

import com.jinbang.api.ai.AiToolProvider;
import com.jinbang.api.exam.ExamDataApi;
import com.jinbang.api.system.SchoolApi;
import com.jinbang.common.datascope.DataScopeProvider;
import com.jinbang.common.datascope.VisibleScope;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.LoginHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 「学情分析」工具（PRD 6.6 ➕P0 AI 学情报告、7.11 学情分析助手）：只读取提问者权限范围内的班级数据；
 * 游客没有班级 → 返回空 → 学情助手按「空结果」处理，不扣费（修复缺陷：游客问学情）。
 */
@Component
@RequiredArgsConstructor
public class LearningAiTool implements AiToolProvider {
    private static final Pattern INTENT = Pattern.compile("(学情|错题|成绩|得分率|班级.*(情况|表现)|薄弱)");

    private final ExamDataApi examData;
    private final SchoolApi schoolApi;
    private final DataScopeProvider dataScope;
    private final LearningService learningService;

    @Override
    public String name() {
        return "学情分析";
    }

    @Override
    public boolean matches(String agentCode, String question) {
        return "learning".equals(agentCode) || ("ask".equals(agentCode) && INTENT.matcher(question).find());
    }

    @Override
    public String run(CurrentIdentity identity, String question) {
        return LoginHelper.runAs(identity, () -> {
            VisibleScope scope = dataScope.current();
            Set<Long> classes = scope.isSchoolWide()
                    ? schoolApi.classesOfCurrentYear(identity.tenantId()).stream().map(SchoolApi.ClassDTO::id).collect(Collectors.toSet())
                    : scope.anyClassIds();
            if (classes.isEmpty()) {
                return "";
            }
            List<ExamDataApi.SourceBrief> sources = examData.listSources(classes, null, LocalDateTime.now().minusDays(60), null);
            StringBuilder sb = new StringBuilder();
            sources.stream().skip(Math.max(0, sources.size() - 5)).forEach(b -> {
                ExamDataApi.SourceData d = examData.load(b.sourceType(), b.sourceId());
                Metrics.Report r = Metrics.compute(d, learningService.bands(d.subject()), s -> classes.contains(s.classId()));
                if (r.counts().valid() == 0) {
                    return;
                }
                String weak = r.items().stream().filter(i -> i.scoreRate().compareTo(BigDecimal.valueOf(0.6)) < 0)
                        .map(i -> "第" + i.no() + "题(" + pct(i.scoreRate()) + ")").collect(Collectors.joining("、"));
                sb.append("《").append(d.title()).append("》").append(d.subject() == null ? "" : d.subject())
                        .append("：有效 ").append(r.counts().valid()).append(" 人，缺考/未交 ")
                        .append(r.counts().absent() + r.counts().missing()).append(" 人，平均得分率 ").append(pct(r.avgRate()))
                        .append("，分档 ").append(r.bands()).append(weak.isEmpty() ? "" : "，薄弱题 " + weak).append("\n");
            });
            return sb.toString();
        });
    }

    private static String pct(BigDecimal r) {
        return r.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
