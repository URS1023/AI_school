package com.jinbang.api.exam;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 学情模块读取作业 / 考试的作答数据（拆分后改为从 OLAP 读取，接口不变）。
 * 调用方需处于对应租户上下文。
 */
public interface ExamDataApi {

    SourceData load(String sourceType, Long sourceId);

    /** 某些班级在时间范围内的作业和考试（用于班级 / 年级 / 个人学情趋势）。 */
    List<SourceBrief> listSources(Set<Long> classIds, String subject, LocalDateTime from, LocalDateTime to);

    record SourceBrief(String sourceType, Long sourceId, String title, String subject, LocalDateTime time,
                       Set<Long> classIds) {
    }

    record SourceData(String sourceType, Long sourceId, Long tenantId, String title, String subject,
                      LocalDateTime time, Set<Long> classIds, List<ItemDef> items, List<StudentResult> students) {
    }

    record ItemDef(String no, String type, boolean objective, BigDecimal fullScore, Long questionId) {
    }

    /**
     * @param status VALID 有效答卷 / ABSENT 缺考 / EXEMPT 免考 / VOID 作废 / UNFINISHED 未阅完 / MISSING 未交
     */
    record StudentResult(Long studentId, Long classId, String status, BigDecimal total, BigDecimal fullScore,
                         Map<String, BigDecimal> itemScores, Map<String, String> itemAnswers, boolean late) {
    }
}
