package com.jinbang.exam.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.exam.domain.ExAnswer;
import com.jinbang.exam.domain.ExAnswerItem;
import com.jinbang.exam.domain.ExScoreChange;
import com.jinbang.exam.mapper.ExAnswerItemMapper;
import com.jinbang.exam.mapper.ExAnswerMapper;
import com.jinbang.exam.mapper.ExScoreChangeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 判分规则：客观题自动判分；手写分数识别和人工给分都必须在 [0, 满分]（AC-18）；
 * 每次改分留痕；答卷总分与状态随之重算。
 */
@Service
@RequiredArgsConstructor
public class ScoringService {
    public static final Set<String> MANUAL_SOURCES = Set.of("MANUAL", "AI", "IMPORT");

    private final ExAnswerMapper answerMapper;
    private final ExAnswerItemMapper itemMapper;
    private final ExScoreChangeMapper changeMapper;

    public List<ExAnswerItem> items(Long answerId) {
        return itemMapper.selectList(Wrappers.<ExAnswerItem>lambdaQuery().eq(ExAnswerItem::getAnswerId, answerId)
                .orderByAsc(ExAnswerItem::getId));
    }

    /** 客观题判分：整题全对得满分，否则 0 分（多选部分对的规则可在「题目给分规则」中扩展）。 */
    public static BigDecimal autoScore(ExAnswerItem it) {
        String std = normalize(it.getItemType(), it.getStdAnswer());
        String stu = normalize(it.getItemType(), it.getStudentAnswer());
        if (std.isEmpty() || stu.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return std.equals(stu) ? it.getFullScore() : BigDecimal.ZERO;
    }

    static String normalize(String type, String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().toUpperCase(Locale.ROOT);
        if ("JUDGE".equals(type)) {
            if (Set.of("对", "正确", "T", "TRUE", "√", "V", "A").contains(t)) {
                return "T";
            }
            if (Set.of("错", "错误", "F", "FALSE", "×", "X", "B").contains(t)) {
                return "F";
            }
            return t;
        }
        char[] cs = t.replaceAll("[^A-H]", "").toCharArray();
        java.util.Arrays.sort(cs);
        return new String(cs);
    }

    /** 识别出来的手写分数：越界不入账，标记待复核（AC-18）。 */
    public void applyRecognizedScore(ExAnswerItem it, BigDecimal score) {
        if (score == null) {
            return;
        }
        if (score.signum() < 0 || score.compareTo(it.getFullScore()) > 0) {
            it.setScore(null);
            it.setScoreSource(null);
            it.setNeedsReview(true);
            it.setReviewReason("识别分数 " + score.stripTrailingZeros().toPlainString() + " 超出本题满分 "
                    + it.getFullScore().stripTrailingZeros().toPlainString() + "，请人工复核");
            return;
        }
        it.setScore(score);
        it.setScoreSource("RECOGNIZED");
        it.setNeedsReview(false);
        it.setReviewReason(null);
    }

    /**
     * 人工 / AI / 导入给分。越界直接拒绝（AC-18）。返回是否发生了改分（原来有分且不同）。
     */
    public boolean setScore(ExAnswer a, ExAnswerItem it, BigDecimal score, String source, Long operator, String reason,
                            boolean afterPublish) {
        if (score == null || score.signum() < 0 || score.compareTo(it.getFullScore()) > 0) {
            throw new BizException(ErrorCode.SCORE_OUT_OF_RANGE, "得分必须在 0 到 "
                    + it.getFullScore().stripTrailingZeros().toPlainString() + " 分之间");
        }
        BigDecimal old = it.getScore();
        BigDecimal oldTotal = a.getTotalScore();
        it.setScore(score);
        it.setScoreSource(source);
        it.setNeedsReview(false);
        it.setReviewReason(null);
        it.setMarkedBy(operator);
        it.setMarkedAt(LocalDateTime.now());
        itemMapper.updateById(it);
        recalc(a);
        boolean changed = old != null && old.compareTo(score) != 0;
        if (changed || (old == null && afterPublish)) {
            ExScoreChange c = new ExScoreChange();
            c.setAnswerId(a.getId());
            c.setStudentId(a.getStudentId());
            c.setItemNo(it.getItemNo());
            c.setOldScore(old);
            c.setNewScore(score);
            c.setOldTotal(oldTotal);
            c.setNewTotal(a.getTotalScore());
            c.setReason(reason);
            c.setChangeKind(afterPublish ? "AFTER_PUBLISH" : "BEFORE_PUBLISH");
            c.setAfterPublish(afterPublish);
            c.setNotified(false);
            c.setOperatorId(operator);
            changeMapper.insert(c);
        }
        return changed;
    }

    /** 重算总分、主客观分和状态：全部题目有分且无待复核 → MARKED。 */
    public void recalc(ExAnswer a) {
        List<ExAnswerItem> items = items(a.getId());
        BigDecimal obj = BigDecimal.ZERO;
        BigDecimal sub = BigDecimal.ZERO;
        BigDecimal full = BigDecimal.ZERO;
        boolean complete = true;
        for (ExAnswerItem it : items) {
            full = full.add(it.getFullScore());
            if (it.getScore() == null || Boolean.TRUE.equals(it.getNeedsReview())) {
                complete = false;
                continue;
            }
            if (Boolean.TRUE.equals(it.getObjective())) {
                obj = obj.add(it.getScore());
            } else {
                sub = sub.add(it.getScore());
            }
        }
        a.setObjectiveScore(obj);
        a.setSubjectiveScore(sub);
        a.setTotalScore(obj.add(sub));
        a.setFullScore(full);
        if (!"EXCEPTION".equals(a.getAnswerStatus()) && !"REPLACED".equals(a.getAnswerStatus())) {
            a.setAnswerStatus(complete ? "MARKED" : "MARKING");
        }
        answerMapper.updateById(a);
    }
}
