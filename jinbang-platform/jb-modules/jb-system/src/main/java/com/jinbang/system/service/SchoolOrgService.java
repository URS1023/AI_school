package com.jinbang.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.system.YearUpgradeCheck;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.log.OperLog;
import com.jinbang.common.tenant.TenantContext;
import com.jinbang.system.domain.EduAcademicYear;
import com.jinbang.system.domain.EduClass;
import com.jinbang.system.domain.EduGrade;
import com.jinbang.system.domain.EduStudent;
import com.jinbang.system.domain.EduStudentClassLog;
import com.jinbang.system.domain.EduTeachRel;
import com.jinbang.system.mapper.EduAcademicYearMapper;
import com.jinbang.system.mapper.EduClassMapper;
import com.jinbang.system.mapper.EduGradeMapper;
import com.jinbang.system.mapper.EduStudentClassLogMapper;
import com.jinbang.system.mapper.EduStudentMapper;
import com.jinbang.system.mapper.EduTeachRelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 学年、年级、班级（手册 4.1–4.3）与学年升级（PRD 11.8）。 */
@Service
@RequiredArgsConstructor
public class SchoolOrgService {
    private static final String[] GRADE_NAMES = {"", "一年级", "二年级", "三年级", "四年级", "五年级", "六年级",
            "七年级", "八年级", "九年级", "高一", "高二", "高三"};
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EduAcademicYearMapper yearMapper;
    private final EduGradeMapper gradeMapper;
    private final EduClassMapper classMapper;
    private final EduStudentMapper studentMapper;
    private final EduStudentClassLogMapper classLogMapper;
    private final EduTeachRelMapper teachRelMapper;
    private final org.springframework.beans.factory.ObjectProvider<YearUpgradeCheck> upgradeChecks;

    public static String gradeName(int level) {
        return level >= 1 && level <= 12 ? GRADE_NAMES[level] : "第" + level + "级";
    }

    public static String periodOf(int level) {
        return level <= 6 ? "3" : level <= 9 ? "2" : "1";
    }

    /** 学段的最高年级：小学 6，初中 9，高中 12。 */
    private static int lastLevelOf(String period) {
        return switch (period) {
            case "3" -> 6;
            case "2" -> 9;
            default -> 12;
        };
    }

    // ---------------- 学年 ----------------

    public List<EduAcademicYear> listYears() {
        return yearMapper.selectList(Wrappers.<EduAcademicYear>lambdaQuery().orderByDesc(EduAcademicYear::getStartYear));
    }

    /** D1：学年一律取学校设置的「当前学年」。 */
    public EduAcademicYear currentYear() {
        return yearMapper.selectList(Wrappers.<EduAcademicYear>lambdaQuery().eq(EduAcademicYear::getIsCurrent, true))
                .stream().findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "学校尚未设置当前学年，请联系学校管理员"));
    }

    @Transactional
    public EduAcademicYear createYear(int startYear, boolean makeCurrent) {
        EduAcademicYear y = new EduAcademicYear();
        y.setStartYear(startYear);
        y.setYearName(startYear + "-" + (startYear + 1) + "学年");
        y.setIsCurrent(false);
        yearMapper.insert(y);
        if (makeCurrent) {
            setCurrentYear(y.getId());
        }
        return y;
    }

    @Transactional
    public void setCurrentYear(Long yearId) {
        EduAcademicYear target = BizException.notNull(yearMapper.selectById(yearId), "学年");
        yearMapper.update(Wrappers.<EduAcademicYear>lambdaUpdate().set(EduAcademicYear::getIsCurrent, false)
                .eq(EduAcademicYear::getIsCurrent, true));
        target.setIsCurrent(true);
        yearMapper.updateById(target);
    }

    // ---------------- 年级 / 班级 ----------------

    public List<EduGrade> listGrades(Long yearId) {
        return gradeMapper.selectList(Wrappers.<EduGrade>lambdaQuery().eq(EduGrade::getYearId, yearId)
                .orderByAsc(EduGrade::getGradeLevel));
    }

    public EduGrade getGrade(Long gradeId) {
        return BizException.notNull(gradeMapper.selectById(gradeId), "年级");
    }

    @Transactional
    public EduGrade createGrade(Long yearId, int level) {
        BizException.notNull(yearMapper.selectById(yearId), "学年");
        if (level < 1 || level > 12) {
            throw new BizException(ErrorCode.BAD_REQUEST, "年级取值 1-12");
        }
        boolean exists = gradeMapper.exists(Wrappers.<EduGrade>lambdaQuery().eq(EduGrade::getYearId, yearId)
                .eq(EduGrade::getGradeLevel, level));
        BizException.throwIf(exists, ErrorCode.CONFLICT, "该学年已有" + gradeName(level));
        EduGrade g = new EduGrade();
        g.setYearId(yearId);
        g.setGradeLevel(level);
        g.setGradeName(gradeName(level));
        g.setPeriod(periodOf(level));
        g.setGradeStatus("ACTIVE");
        gradeMapper.insert(g);
        return g;
    }

    public List<EduClass> listClasses(Long gradeId) {
        return classMapper.selectList(Wrappers.<EduClass>lambdaQuery().eq(EduClass::getGradeId, gradeId)
                .orderByAsc(EduClass::getClassName));
    }

    public EduClass getClass(Long classId) {
        return BizException.notNull(classMapper.selectById(classId), "班级");
    }

    @Transactional
    public EduClass createClass(Long gradeId, String name, String classType) {
        EduGrade g = getGrade(gradeId);
        EduClass c = new EduClass();
        c.setYearId(g.getYearId());
        c.setGradeId(gradeId);
        c.setClassName(name);
        c.setClassType(classType == null ? "ADMIN" : classType);
        c.setParentInviteCode(newInviteCode());
        c.setClassStatus("ACTIVE");
        classMapper.insert(c);
        return c;
    }

    /** 班主任可重置家长邀请码（防止邀请码外泄后被陌生人绑定，PRD 11.10 ➕）。 */
    @Transactional
    public String resetParentInviteCode(Long classId) {
        EduClass c = getClass(classId);
        c.setParentInviteCode(newInviteCode());
        classMapper.updateById(c);
        return c.getParentInviteCode();
    }

    static String newInviteCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    // ---------------- 学年升级 ----------------

    public record UpgradeResult(boolean done, List<String> warnings, Long newYearId, int promotedClasses,
                                int graduatedStudents) {
    }

    /**
     * 学年升级（PRD 11.8）：校验 → 新学年 → 各年级 +1、毕业年级归档 → 班级沿用 → 任教关系清空待重新分配 → 切换当前学年。
     * 有未完成阅卷 / 未发布成绩时先返回提示，管理员确认后带 force=true 再执行。
     */
    @Transactional
    @OperLog(module = "教务-学年", action = "学年升级")
    public UpgradeResult upgrade(boolean force) {
        EduAcademicYear current = currentYear();
        long tenantId = TenantContext.currentTenantId();
        List<String> warnings = new ArrayList<>();
        for (YearUpgradeCheck check : upgradeChecks.orderedStream().toList()) {
            warnings.addAll(check.check(tenantId, current.getId()));
        }
        if (!warnings.isEmpty() && !force) {
            return new UpgradeResult(false, warnings, null, 0, 0);
        }
        int nextStart = current.getStartYear() + 1;
        BizException.throwIf(yearMapper.exists(Wrappers.<EduAcademicYear>lambdaQuery()
                .eq(EduAcademicYear::getStartYear, nextStart)), ErrorCode.CONFLICT, "下一学年已存在");
        EduAcademicYear next = createYear(nextStart, false);
        LocalDateTime now = LocalDateTime.now();
        int promoted = 0;
        int graduated = 0;
        for (EduGrade g : listGrades(current.getId())) {
            List<EduClass> classes = listClasses(g.getId());
            boolean graduating = g.getGradeLevel() >= lastLevelOf(g.getPeriod());
            if (graduating) {
                g.setGradeStatus("GRADUATED");
                gradeMapper.updateById(g);
                for (EduClass c : classes) {
                    c.setClassStatus("ARCHIVED");
                    classMapper.updateById(c);
                    for (EduStudent s : studentsOf(c.getId())) {
                        s.setStudentStatus("GRADUATED");
                        studentMapper.updateById(s);
                        closeClassLog(s.getId(), now);
                        graduated++;
                    }
                }
                continue;
            }
            EduGrade ng = createGrade(next.getId(), g.getGradeLevel() + 1);
            Map<Long, Long> classMap = new HashMap<>();
            for (EduClass c : classes) {
                EduClass nc = new EduClass();
                nc.setYearId(next.getId());
                nc.setGradeId(ng.getId());
                nc.setClassName(c.getClassName().replace(g.getGradeName(), ng.getGradeName()));
                nc.setClassType(c.getClassType());
                nc.setParentInviteCode(newInviteCode());
                nc.setPrevClassId(c.getId());
                nc.setClassStatus("ACTIVE");
                classMapper.insert(nc);
                classMap.put(c.getId(), nc.getId());
                c.setClassStatus("ARCHIVED");
                classMapper.updateById(c);
                promoted++;
            }
            for (Map.Entry<Long, Long> e : classMap.entrySet()) {
                for (EduStudent s : studentsOf(e.getKey())) {
                    moveStudent(s, e.getValue(), next.getId(), now);
                }
            }
        }
        // 任教关系清空，待重新分配
        teachRelMapper.update(Wrappers.<EduTeachRel>lambdaUpdate().set(EduTeachRel::getRelStatus, "ENDED")
                .set(EduTeachRel::getEndedAt, now).eq(EduTeachRel::getYearId, current.getId())
                .eq(EduTeachRel::getRelStatus, "ACTIVE"));
        setCurrentYear(next.getId());
        return new UpgradeResult(true, warnings, next.getId(), promoted, graduated);
    }

    private List<EduStudent> studentsOf(Long classId) {
        return studentMapper.selectList(Wrappers.<EduStudent>lambdaQuery().eq(EduStudent::getClassId, classId)
                .eq(EduStudent::getStudentStatus, "ACTIVE"));
    }

    /** 学生换班：关闭旧的在班记录，写入新的。 */
    public void moveStudent(EduStudent s, Long newClassId, Long yearId, LocalDateTime now) {
        closeClassLog(s.getId(), now);
        s.setClassId(newClassId);
        studentMapper.updateById(s);
        EduStudentClassLog log = new EduStudentClassLog();
        log.setStudentId(s.getId());
        log.setClassId(newClassId);
        log.setYearId(yearId);
        log.setJoinedAt(now);
        classLogMapper.insert(log);
    }

    private void closeClassLog(Long studentId, LocalDateTime now) {
        classLogMapper.update(Wrappers.<EduStudentClassLog>lambdaUpdate().set(EduStudentClassLog::getLeftAt, now)
                .eq(EduStudentClassLog::getStudentId, studentId).isNull(EduStudentClassLog::getLeftAt));
    }
}
