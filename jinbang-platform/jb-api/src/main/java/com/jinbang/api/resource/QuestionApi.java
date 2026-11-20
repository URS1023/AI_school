package com.jinbang.api.resource;

import java.math.BigDecimal;
import java.util.List;

/** 题库与试卷契约：AI 出题入题库（PRD 15）、考试/作业从试卷取题目结构与分值。 */
public interface QuestionApi {

    /** 保存到个人题库，返回题目 ID。 */
    List<Long> saveToPersonalBank(Long userId, List<NewQuestion> questions);

    PaperDTO getPaper(Long paperId);

    /** 取单题（错题变式、讲评类题）。不可见时返回 null。 */
    QuestionBrief getQuestion(Long questionId);

    record QuestionBrief(Long id, String subject, String type, String difficulty, String stem, String options,
                         String answer, String analysis, String knowledge) {
    }

    record NewQuestion(String subject, String type, String difficulty, String stem, String options,
                       String answer, String analysis, String source) {
    }

    record PaperDTO(Long id, String title, String subject, BigDecimal totalScore, List<PaperItem> items) {
    }

    /**
     * @param no        题号（如 "1"、"13(2)"）
     * @param objective 是否客观题（选择、判断）
     */
    record PaperItem(Long questionId, String no, String type, boolean objective, BigDecimal fullScore,
                     String answer) {
    }
}
