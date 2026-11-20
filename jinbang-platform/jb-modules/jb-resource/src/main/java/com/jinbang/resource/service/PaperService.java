package com.jinbang.resource.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinbang.api.resource.QuestionApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.LoginHelper;
import com.jinbang.resource.domain.QPaper;
import com.jinbang.resource.domain.QPaperItem;
import com.jinbang.resource.domain.QQuestion;
import com.jinbang.resource.domain.ResBasket;
import com.jinbang.resource.mapper.QPaperItemMapper;
import com.jinbang.resource.mapper.QPaperMapper;
import com.jinbang.resource.mapper.QQuestionMapper;
import com.jinbang.resource.mapper.ResBasketMapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 试题篮与组卷（PRD 3.3、6.11、6.12）：选题组卷、智能组卷（知识点 + 难度 + 各题型题量）、
 * 分数设置、换题、调序、试卷分析、回收站、导出 Word。试卷是答题卡和作业 / 考试的来源（QuestionApi）。
 */
@Service
@RequiredArgsConstructor
public class PaperService implements QuestionApi {
    public static final Set<String> OBJECTIVE = Set.of("SINGLE", "MULTIPLE", "JUDGE");
    private static final Map<String, String> SECTION = Map.of("SINGLE", "单项选择题", "MULTIPLE", "多项选择题",
            "JUDGE", "判断题", "BLANK", "填空题", "ANSWER", "解答题", "SHORT", "简答题", "ESSAY", "作文");
    private static final List<String> SECTION_ORDER = List.of("SINGLE", "MULTIPLE", "JUDGE", "BLANK", "SHORT", "ANSWER", "ESSAY");

    private final QPaperMapper paperMapper;
    private final QPaperItemMapper itemMapper;
    private final QQuestionMapper questionMapper;
    private final ResBasketMapper basketMapper;
    private final QuestionService questionService;
    private final ObjectMapper om;

    public static BigDecimal defaultScore(String type) {
        return BigDecimal.valueOf(switch (type) {
            case "SINGLE", "BLANK" -> 5;
            case "MULTIPLE" -> 6;
            case "JUDGE" -> 2;
            case "ANSWER" -> 12;
            case "ESSAY" -> 60;
            default -> 10;
        });
    }

    public record PaperView(QPaper paper, List<Section> sections, Analysis analysis) {
    }

    public record Section(String title, String type, List<Item> items) {
    }

    public record Item(Long itemId, int no, BigDecimal score, QQuestion question) {
    }

    public record Analysis(Map<String, Long> byType, Map<String, Long> byDifficulty, Map<String, Long> byKnowledge,
                           Map<String, BigDecimal> scoreByType) {
    }

    /** 选题组卷：用试题篮里的题生成试卷，标题默认「日期 + 学科 + 作业」。 */
    @Transactional
    public PaperView createFromBasket(CurrentIdentity ci, String title, String subject) {
        List<Long> qids = basketMapper.selectList(Wrappers.<ResBasket>lambdaQuery().eq(ResBasket::getUserId, ci.userId())
                .eq(ResBasket::getItemType, "QUESTION").orderByAsc(ResBasket::getSortNo))
                .stream().map(ResBasket::getItemId).toList();
        BizException.throwIf(qids.isEmpty(), ErrorCode.BAD_REQUEST, "试题篮是空的");
        PaperView v = create(ci, title, subject, "MANUAL", qids);
        basketMapper.delete(Wrappers.<ResBasket>lambdaQuery().eq(ResBasket::getUserId, ci.userId())
                .eq(ResBasket::getItemType, "QUESTION"));
        return v;
    }

    public record SmartReq(String title, String period, String subject, List<String> knowledge, String difficulty,
                           Map<String, Integer> typeCounts) {
    }

    /** 智能组卷 / 快速出卷：按知识点、难度、各题型题量从平台题库抽题。题不够时如实告知缺多少。 */
    @Transactional
    public PaperView smart(CurrentIdentity ci, SmartReq req) {
        List<Long> chosen = new ArrayList<>();
        List<String> shortage = new ArrayList<>();
        for (Map.Entry<String, Integer> e : req.typeCounts().entrySet()) {
            List<QQuestion> pool = questionMapper.selectList(Wrappers.<QQuestion>lambdaQuery()
                    .eq(QQuestion::getQScope, "PLATFORM").eq(QQuestion::getDeleted, false)
                    .eq(req.subject() != null, QQuestion::getSubject, req.subject())
                    .eq(req.period() != null, QQuestion::getPeriod, req.period())
                    .eq(QQuestion::getQType, e.getKey())
                    .and(req.knowledge() != null && !req.knowledge().isEmpty(), w -> req.knowledge()
                            .forEach(k -> w.or().like(QQuestion::getKnowledge, k))));
            int target = req.difficulty() == null ? 0 : Integer.parseInt(req.difficulty());
            pool.sort(Comparator.comparingInt(q -> Math.abs(Integer.parseInt(q.getDifficulty() == null ? "3" : q.getDifficulty()) - target)));
            if (target == 0) {
                Collections.shuffle(pool);
            }
            int need = e.getValue();
            pool.stream().limit(need).forEach(q -> chosen.add(q.getId()));
            if (pool.size() < need) {
                shortage.add(SECTION.getOrDefault(e.getKey(), e.getKey()) + "缺 " + (need - pool.size()) + " 题");
            }
        }
        BizException.throwIf(chosen.isEmpty(), ErrorCode.NOT_FOUND, "题库中没有符合条件的题目");
        PaperView v = create(ci, req.title(), req.subject(), "SMART", chosen);
        if (!shortage.isEmpty()) {
            v.paper().setSealLine("题量不足：" + String.join("，", shortage));
        }
        return v;
    }

    private PaperView create(CurrentIdentity ci, String title, String subject, String mode, List<Long> qids) {
        QPaper p = new QPaper();
        p.setOwnerUserId(ci.userId());
        p.setTenantId(ci.tenantId());
        p.setTitle(title == null || title.isBlank() ? LocalDate.now() + " " + (subject == null ? "" : subject) + " 作业" : title);
        p.setSubject(subject);
        p.setPaperMode(mode);
        p.setFolder("我的试卷");
        p.setDeleted(false);
        p.setTotalScore(BigDecimal.ZERO);
        paperMapper.insert(p);
        List<QQuestion> qs = new ArrayList<>(questionMapper.selectByIds(qids));
        qs.removeIf(q -> !questionService.canSee(ci, q));
        qs.sort(Comparator.comparingInt((QQuestion q) -> SECTION_ORDER.indexOf(q.getQType()) < 0 ? 99
                : SECTION_ORDER.indexOf(q.getQType())).thenComparingInt(q -> qids.indexOf(q.getId())));
        int no = 1;
        for (QQuestion q : qs) {
            QPaperItem it = new QPaperItem();
            it.setPaperId(p.getId());
            it.setQuestionId(q.getId());
            it.setSectionTitle(SECTION.getOrDefault(q.getQType(), "其他"));
            it.setItemNo(no++);
            it.setScore(defaultScore(q.getQType()));
            itemMapper.insert(it);
            questionMapper.update(Wrappers.<QQuestion>lambdaUpdate().setSql("used_count = used_count + 1")
                    .eq(QQuestion::getId, q.getId()));
        }
        recalcTotal(p.getId());
        return view(ci, p.getId());
    }

    public PaperView view(CurrentIdentity ci, Long paperId) {
        QPaper p = own(ci, paperId);
        List<QPaperItem> items = itemMapper.selectList(Wrappers.<QPaperItem>lambdaQuery().eq(QPaperItem::getPaperId, paperId)
                .orderByAsc(QPaperItem::getItemNo));
        Map<Long, QQuestion> qs = items.isEmpty() ? Map.of() : questionMapper.selectByIds(items.stream()
                .map(QPaperItem::getQuestionId).toList()).stream().collect(Collectors.toMap(QQuestion::getId, q -> q));
        Map<String, List<Item>> sec = new LinkedHashMap<>();
        Map<String, String> secType = new LinkedHashMap<>();
        for (QPaperItem it : items) {
            QQuestion q = qs.get(it.getQuestionId());
            sec.computeIfAbsent(it.getSectionTitle(), k -> new ArrayList<>()).add(new Item(it.getId(), it.getItemNo(), it.getScore(), q));
            if (q != null) {
                secType.putIfAbsent(it.getSectionTitle(), q.getQType());
            }
        }
        List<Section> sections = sec.entrySet().stream().map(e -> new Section(e.getKey(), secType.get(e.getKey()), e.getValue())).toList();
        List<QQuestion> all = items.stream().map(i -> qs.get(i.getQuestionId())).filter(java.util.Objects::nonNull).toList();
        Map<String, BigDecimal> scoreByType = new LinkedHashMap<>();
        for (QPaperItem it : items) {
            QQuestion q = qs.get(it.getQuestionId());
            if (q != null) {
                scoreByType.merge(q.getQType(), it.getScore(), BigDecimal::add);
            }
        }
        Analysis a = new Analysis(
                all.stream().collect(Collectors.groupingBy(QQuestion::getQType, LinkedHashMap::new, Collectors.counting())),
                all.stream().collect(Collectors.groupingBy(q -> q.getDifficulty() == null ? "3" : q.getDifficulty(), LinkedHashMap::new, Collectors.counting())),
                all.stream().collect(Collectors.groupingBy(q -> q.getKnowledge() == null ? "未标注" : q.getKnowledge(), LinkedHashMap::new, Collectors.counting())),
                scoreByType);
        return new PaperView(p, sections, a);
    }

    public List<QPaper> myPapers(CurrentIdentity ci, boolean recycle) {
        return paperMapper.selectList(Wrappers.<QPaper>lambdaQuery().eq(QPaper::getOwnerUserId, ci.userId())
                .eq(QPaper::getDeleted, recycle).orderByDesc(QPaper::getCreatedAt));
    }

    /** 分数设置：itemId → 分值。 */
    @Transactional
    public PaperView setScores(CurrentIdentity ci, Long paperId, Map<Long, BigDecimal> scores) {
        own(ci, paperId);
        scores.forEach((itemId, score) -> {
            BizException.throwIf(score == null || score.signum() <= 0, ErrorCode.BAD_REQUEST, "分值必须大于 0");
            itemMapper.update(Wrappers.<QPaperItem>lambdaUpdate().set(QPaperItem::getScore, score)
                    .eq(QPaperItem::getId, itemId).eq(QPaperItem::getPaperId, paperId));
        });
        recalcTotal(paperId);
        return view(ci, paperId);
    }

    /** 调整题序：按传入的 itemId 顺序重新编号。 */
    @Transactional
    public PaperView reorder(CurrentIdentity ci, Long paperId, List<Long> itemIds) {
        own(ci, paperId);
        int no = 1;
        for (Long id : itemIds) {
            itemMapper.update(Wrappers.<QPaperItem>lambdaUpdate().set(QPaperItem::getItemNo, no++)
                    .eq(QPaperItem::getId, id).eq(QPaperItem::getPaperId, paperId));
        }
        return view(ci, paperId);
    }

    /** 换题：换成同题型、同知识点的另一道题（不重复卷内已有题）。 */
    @Transactional
    public PaperView replace(CurrentIdentity ci, Long paperId, Long itemId) {
        own(ci, paperId);
        QPaperItem it = BizException.notNull(itemMapper.selectById(itemId), "试卷题目");
        Set<Long> inPaper = itemMapper.selectList(Wrappers.<QPaperItem>lambdaQuery().eq(QPaperItem::getPaperId, paperId))
                .stream().map(QPaperItem::getQuestionId).collect(Collectors.toCollection(HashSet::new));
        QQuestion old = questionMapper.selectById(it.getQuestionId());
        QQuestion candidate = questionService.similar(ci, old.getId(), 20).stream()
                .filter(q -> !inPaper.contains(q.getId())).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "没有可替换的同类题"));
        it.setQuestionId(candidate.getId());
        itemMapper.updateById(it);
        return view(ci, paperId);
    }

    @Transactional
    public void recycle(CurrentIdentity ci, Long paperId, boolean deleted) {
        QPaper p = own(ci, paperId);
        p.setDeleted(deleted);
        paperMapper.updateById(p);
    }

    /** 导出 Word（含密封线、按大题分组、答案解析附后）。 */
    public byte[] exportDocx(CurrentIdentity ci, Long paperId, boolean withAnswer) {
        PaperView v = view(ci, paperId);
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            para(doc, v.paper().getTitle(), 18, true);
            para(doc, "学校：________  姓名：________  班级：________  考号：________", 11, false);
            para(doc, "满分 " + v.paper().getTotalScore().stripTrailingZeros().toPlainString() + " 分", 11, false);
            String[] cn = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
            int s = 0;
            for (Section sec : v.sections()) {
                BigDecimal sum = sec.items().stream().map(Item::score).reduce(BigDecimal.ZERO, BigDecimal::add);
                para(doc, cn[Math.min(s++, 9)] + "、" + sec.title() + "（共 " + sum.stripTrailingZeros().toPlainString() + " 分）", 13, true);
                for (Item it : sec.items()) {
                    if (it.question() == null) {
                        continue;
                    }
                    para(doc, it.no() + ". （" + it.score().stripTrailingZeros().toPlainString() + " 分）" + it.question().getStem(), 11, false);
                    JsonNode opts = it.question().getOptions() == null ? null : om.readTree(it.question().getOptions());
                    if (opts != null && opts.isArray()) {
                        opts.forEach(o -> para(doc, "    " + o.asText(), 11, false));
                    }
                }
            }
            if (withAnswer) {
                para(doc, "参考答案与解析", 14, true);
                for (Section sec : v.sections()) {
                    for (Item it : sec.items()) {
                        if (it.question() != null) {
                            para(doc, it.no() + ". " + nz(it.question().getAnswer()) + "  " + nz(it.question().getAnalysis()), 10, false);
                        }
                    }
                }
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static void para(XWPFDocument doc, String text, int size, boolean bold) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontSize(size);
        r.setBold(bold);
    }

    private void recalcTotal(Long paperId) {
        BigDecimal total = itemMapper.selectList(Wrappers.<QPaperItem>lambdaQuery().eq(QPaperItem::getPaperId, paperId))
                .stream().map(QPaperItem::getScore).reduce(BigDecimal.ZERO, BigDecimal::add);
        paperMapper.update(Wrappers.<QPaper>lambdaUpdate().set(QPaper::getTotalScore, total).eq(QPaper::getId, paperId));
    }

    private QPaper own(CurrentIdentity ci, Long paperId) {
        QPaper p = BizException.notNull(paperMapper.selectById(paperId), "试卷");
        if (!p.getOwnerUserId().equals(ci.userId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "试卷不存在");
        }
        return p;
    }

    // ---------------- QuestionApi ----------------

    @Override
    @Transactional
    public List<Long> saveToPersonalBank(Long userId, List<NewQuestion> questions) {
        List<Long> ids = new ArrayList<>();
        for (NewQuestion q : questions) {
            ids.add(questionService.insertPersonal(userId, null, q.subject(), q.type(), q.difficulty(), q.stem(),
                    q.options(), q.answer(), q.analysis(), null, null, q.source(), null, null).getId());
        }
        return ids;
    }

    /** 考试 / 作业取试卷结构。调用者必须是试卷所有者（学校管理员建考时可传入本校教师的试卷，此处按所有者或同租户放行）。 */
    @Override
    public PaperDTO getPaper(Long paperId) {
        QPaper p = BizException.notNull(paperMapper.selectById(paperId), "试卷");
        CurrentIdentity ci = LoginHelper.require();
        if (!p.getOwnerUserId().equals(ci.userId()) && !java.util.Objects.equals(p.getTenantId(), ci.tenantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "试卷不存在");
        }
        List<QPaperItem> items = itemMapper.selectList(Wrappers.<QPaperItem>lambdaQuery().eq(QPaperItem::getPaperId, paperId)
                .orderByAsc(QPaperItem::getItemNo));
        Map<Long, QQuestion> qs = items.isEmpty() ? Map.of() : questionMapper.selectByIds(items.stream()
                .map(QPaperItem::getQuestionId).toList()).stream().collect(Collectors.toMap(QQuestion::getId, q -> q));
        List<PaperItem> out = items.stream().map(i -> {
            QQuestion q = qs.get(i.getQuestionId());
            String type = q == null ? "OTHER" : q.getQType();
            return new PaperItem(i.getQuestionId(), String.valueOf(i.getItemNo()), type, OBJECTIVE.contains(type),
                    i.getScore(), q == null ? null : q.getAnswer());
        }).toList();
        return new PaperDTO(p.getId(), p.getTitle(), p.getSubject(), p.getTotalScore(), out);
    }

    @Override
    public QuestionBrief getQuestion(Long questionId) {
        if (questionId == null) {
            return null;
        }
        QQuestion q = questionMapper.selectById(questionId);
        CurrentIdentity ci = LoginHelper.current().orElse(null);
        if (q == null || (ci != null && !questionService.canSee(ci, q) && !"PLATFORM".equals(q.getQScope()))) {
            return null;
        }
        return new QuestionBrief(q.getId(), q.getSubject(), q.getQType(), q.getDifficulty(), q.getStem(), q.getOptions(),
                q.getAnswer(), q.getAnalysis(), q.getKnowledge());
    }
}
