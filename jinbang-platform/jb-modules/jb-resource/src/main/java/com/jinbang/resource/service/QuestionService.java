package com.jinbang.resource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.core.PageQuery;
import com.jinbang.common.core.PageResult;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.resource.domain.QQuestion;
import com.jinbang.resource.domain.ResCatalog;
import com.jinbang.resource.mapper.QQuestionMapper;
import com.jinbang.resource.mapper.ResCatalogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 题库（PRD 3.3、6.11）：统一题目模型，平台题、校本题、个人题、AI 生成题在同一张表，用 scope 区分（➕P0 打通）。
 * 个人题库可以「引用」平台题（复制一份并记录来源 ref_question_id）。
 */
@Service
@RequiredArgsConstructor
public class QuestionService {
    private final QQuestionMapper questionMapper;
    private final ResCatalogMapper catalogMapper;
    private final ObjectMapper om;

    public record QuestionQuery(String scope, String period, String subject, String type, String difficulty, String source,
                        String knowledge, Long catalogId, Integer year, String keyword, PageQuery page) {
    }

    public record QuestionReq(String period, String subject, String type, String difficulty, String stem,
                              List<String> options, String answer, String analysis, String knowledge, Long catalogId,
                              String source) {
    }

    public PageResult<QQuestion> list(CurrentIdentity ci, QuestionQuery q) {
        LambdaQueryWrapper<QQuestion> w = Wrappers.<QQuestion>lambdaQuery().eq(QQuestion::getDeleted, false);
        scope(w, ci, q.scope());
        w.eq(notBlank(q.period()), QQuestion::getPeriod, q.period())
                .eq(notBlank(q.subject()), QQuestion::getSubject, q.subject())
                .eq(notBlank(q.type()), QQuestion::getQType, q.type())
                .eq(notBlank(q.difficulty()), QQuestion::getDifficulty, q.difficulty())
                .eq(notBlank(q.source()), QQuestion::getQSource, q.source())
                .like(notBlank(q.knowledge()), QQuestion::getKnowledge, q.knowledge())
                .eq(q.catalogId() != null, QQuestion::getCatalogId, q.catalogId())
                .eq(q.year() != null, QQuestion::getQYear, q.year())
                .like(notBlank(q.keyword()), QQuestion::getStem, q.keyword())
                .orderByDesc(QQuestion::getUsedCount).orderByAsc(QQuestion::getId);
        return PageResult.of(questionMapper.selectPage((q.page() == null ? new PageQuery() : q.page()).toPage(), w));
    }

    private void scope(LambdaQueryWrapper<QQuestion> w, CurrentIdentity ci, String scope) {
        switch (scope == null ? "PLATFORM" : scope) {
            case "MINE" -> w.eq(QQuestion::getQScope, "PERSONAL").eq(QQuestion::getOwnerUserId, ci.userId());
            case "SCHOOL" -> {
                if (!ci.type().isSchoolMember()) {
                    throw new BizException(ErrorCode.FORBIDDEN);
                }
                w.eq(QQuestion::getQScope, "SCHOOL").eq(QQuestion::getTenantId, ci.tenantId());
            }
            default -> w.eq(QQuestion::getQScope, "PLATFORM");
        }
    }

    public QQuestion get(CurrentIdentity ci, Long id) {
        QQuestion q = BizException.notNull(questionMapper.selectById(id), "题目");
        if (!canSee(ci, q)) {
            throw new BizException(ErrorCode.NOT_FOUND, "题目不存在");
        }
        return q;
    }

    public boolean canSee(CurrentIdentity ci, QQuestion q) {
        return switch (q.getQScope()) {
            case "PLATFORM" -> true;
            case "SCHOOL" -> Objects.equals(q.getTenantId(), ci.tenantId());
            default -> Objects.equals(q.getOwnerUserId(), ci.userId());
        };
    }

    @Transactional
    public QQuestion create(CurrentIdentity ci, QuestionReq r) {
        return insertPersonal(ci.userId(), r.period(), r.subject(), r.type(), r.difficulty(), r.stem(),
                r.options() == null ? null : toJson(r.options()), r.answer(), r.analysis(), r.knowledge(), r.catalogId(),
                r.source() == null ? "PERSONAL" : r.source(), null, null);
    }

    QQuestion insertPersonal(Long userId, String period, String subject, String type, String difficulty, String stem,
                             String optionsJson, String answer, String analysis, String knowledge, Long catalogId,
                             String source, Long groupId, Integer groupNo) {
        BizException.throwIf(stem == null || stem.isBlank(), ErrorCode.BAD_REQUEST, "题文不能为空");
        BizException.throwIf(type == null || type.isBlank(), ErrorCode.BAD_REQUEST, "请选择题型");
        QQuestion q = new QQuestion();
        q.setQScope("PERSONAL");
        q.setOwnerUserId(userId);
        q.setPeriod(period);
        q.setSubject(subject);
        q.setQType(type);
        q.setDifficulty(difficulty == null ? "3" : difficulty);
        q.setQSource(source);
        q.setStem(stem);
        q.setOptions(optionsJson);
        q.setAnswer(answer);
        q.setAnalysis(analysis);
        q.setKnowledge(knowledge);
        q.setCatalogId(catalogId);
        q.setGroupId(groupId);
        q.setGroupNo(groupNo);
        q.setUsedCount(0L);
        q.setDeleted(false);
        questionMapper.insert(q);
        return q;
    }

    @Transactional
    public QQuestion update(CurrentIdentity ci, Long id, QuestionReq r) {
        QQuestion q = own(ci, id);
        if (r.stem() != null) {
            q.setStem(r.stem());
        }
        if (r.type() != null) {
            q.setQType(r.type());
        }
        if (r.difficulty() != null) {
            q.setDifficulty(r.difficulty());
        }
        if (r.options() != null) {
            q.setOptions(toJson(r.options()));
        }
        if (r.answer() != null) {
            q.setAnswer(r.answer());
        }
        if (r.analysis() != null) {
            q.setAnalysis(r.analysis());
        }
        if (r.knowledge() != null) {
            q.setKnowledge(r.knowledge());
        }
        if (r.catalogId() != null) {
            q.setCatalogId(r.catalogId());
        }
        questionMapper.updateById(q);
        return q;
    }

    /** 移动目录 / 设置知识点 / 删除（批量）。 */
    @Transactional
    public int batch(CurrentIdentity ci, List<Long> ids, String op, String value) {
        int n = 0;
        for (Long id : ids) {
            QQuestion q = own(ci, id);
            switch (op) {
                case "MOVE" -> q.setCatalogId(value == null ? null : Long.valueOf(value));
                case "KNOWLEDGE" -> q.setKnowledge(value);
                case "DELETE" -> q.setDeleted(true);
                default -> throw new BizException(ErrorCode.BAD_REQUEST, "不支持的操作");
            }
            questionMapper.updateById(q);
            n++;
        }
        return n;
    }

    /** 把平台 / 校本题收进我的题库（引用复制）。 */
    @Transactional
    public QQuestion collect(CurrentIdentity ci, Long id, Long catalogId) {
        QQuestion src = get(ci, id);
        QQuestion q = insertPersonal(ci.userId(), src.getPeriod(), src.getSubject(), src.getQType(), src.getDifficulty(),
                src.getStem(), src.getOptions(), src.getAnswer(), src.getAnalysis(), src.getKnowledge(), catalogId,
                src.getQSource(), null, null);
        q.setRefQuestionId(src.getId());
        questionMapper.updateById(q);
        return q;
    }

    /** 类题即推：同学科、同知识点、同题型，难度相近。 */
    public List<QQuestion> similar(CurrentIdentity ci, Long id, int limit) {
        QQuestion q = get(ci, id);
        return questionMapper.selectList(Wrappers.<QQuestion>lambdaQuery().eq(QQuestion::getQScope, "PLATFORM")
                        .eq(QQuestion::getDeleted, false).eq(QQuestion::getSubject, q.getSubject())
                        .eq(QQuestion::getQType, q.getQType())
                        .like(q.getKnowledge() != null, QQuestion::getKnowledge, q.getKnowledge())
                        .ne(QQuestion::getId, id).last("LIMIT " + Math.max(1, Math.min(limit, 20))));
    }

    // ---------------- 批量录题 ----------------

    public record ImportPreview(List<QuestionImportParser.ParsedQuestion> questions,
                                List<QuestionImportParser.ParseError> errors, boolean ok) {
    }

    /** 检查区预览。 */
    public ImportPreview preview(String text) {
        QuestionImportParser.Result r = QuestionImportParser.parse(text);
        return new ImportPreview(QuestionImportParser.valid(r), r.errors(), r.errors().isEmpty());
    }

    /** 导入：有错误一题都不保存。题组的小题共享同一个 groupId。 */
    @Transactional
    public List<Long> importText(CurrentIdentity ci, String text, String period, String subject, Long catalogId) {
        QuestionImportParser.Result r = QuestionImportParser.parse(text);
        if (!r.errors().isEmpty()) {
            throw new BizException(ErrorCode.IMPORT_INVALID, "有 " + r.errors().size() + " 处错误，请在检查区修正后再导入", r.errors());
        }
        List<Long> ids = new ArrayList<>();
        java.util.Map<Integer, Long> groups = new java.util.HashMap<>();
        java.util.Map<Integer, Integer> groupSeq = new java.util.HashMap<>();
        for (QuestionImportParser.ParsedQuestion p : QuestionImportParser.valid(r)) {
            Long gid = null;
            Integer gno = null;
            if (p.groupIndex() != null) {
                gid = groups.get(p.groupIndex());
                if (gid == null) {
                    QQuestion g = insertPersonal(ci.userId(), period, subject, "GROUP", p.difficulty(), p.groupStem(), null,
                            null, null, null, catalogId, "IMPORT", null, 0);
                    gid = g.getId();
                    groups.put(p.groupIndex(), gid);
                }
                gno = groupSeq.merge(p.groupIndex(), 1, Integer::sum);
            }
            ids.add(insertPersonal(ci.userId(), period, subject, p.type(), p.difficulty(), p.stem(),
                    p.options().isEmpty() ? null : toJson(p.options()), p.answer(), p.analysis(), null, catalogId,
                    "IMPORT", gid, gno).getId());
        }
        return ids;
    }

    // ---------------- 目录 ----------------

    public List<ResCatalog> catalogs(CurrentIdentity ci, String type, String period, String subject) {
        LambdaQueryWrapper<ResCatalog> w = Wrappers.<ResCatalog>lambdaQuery().eq(ResCatalog::getCatalogType, type)
                .eq(notBlank(period), ResCatalog::getPeriod, period).eq(notBlank(subject), ResCatalog::getSubject, subject)
                .orderByAsc(ResCatalog::getParentId).orderByAsc(ResCatalog::getSortNo);
        if ("QUESTION_DIR".equals(type)) {
            w.eq(ResCatalog::getOwnerUserId, ci.userId());
        }
        return catalogMapper.selectList(w);
    }

    public ResCatalog saveDir(CurrentIdentity ci, Long id, Long parentId, String name, String period, String subject) {
        BizException.throwIf(name == null || name.isBlank(), ErrorCode.BAD_REQUEST, "目录名称不能为空");
        ResCatalog c;
        if (id == null) {
            c = new ResCatalog();
            c.setCatalogType("QUESTION_DIR");
            c.setOwnerUserId(ci.userId());
            c.setParentId(parentId == null ? 0L : parentId);
            c.setPeriod(period);
            c.setSubject(subject);
            c.setSortNo(0);
        } else {
            c = BizException.notNull(catalogMapper.selectById(id), "目录");
            BizException.throwIf(!ci.userId().equals(c.getOwnerUserId()), ErrorCode.FORBIDDEN);
        }
        c.setCatalogName(name.trim());
        if (id == null) {
            catalogMapper.insert(c);
        } else {
            catalogMapper.updateById(c);
        }
        return c;
    }

    @Transactional
    public void deleteDir(CurrentIdentity ci, Long id) {
        ResCatalog c = BizException.notNull(catalogMapper.selectById(id), "目录");
        BizException.throwIf(!ci.userId().equals(c.getOwnerUserId()), ErrorCode.FORBIDDEN);
        BizException.throwIf(catalogMapper.exists(Wrappers.<ResCatalog>lambdaQuery().eq(ResCatalog::getParentId, id)),
                ErrorCode.CONFLICT, "请先删除子目录");
        questionMapper.update(Wrappers.<QQuestion>lambdaUpdate().set(QQuestion::getCatalogId, null)
                .eq(QQuestion::getCatalogId, id).eq(QQuestion::getOwnerUserId, ci.userId()));
        catalogMapper.deleteById(id);
    }

    private QQuestion own(CurrentIdentity ci, Long id) {
        QQuestion q = BizException.notNull(questionMapper.selectById(id), "题目");
        if (!"PERSONAL".equals(q.getQScope()) || !ci.userId().equals(q.getOwnerUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return q;
    }

    String toJson(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
