package com.jinbang.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.system.IdentityRevokedEvent;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.log.OperLog;
import com.jinbang.common.security.IdentityType;
import com.jinbang.common.tenant.TenantContext;
import com.jinbang.system.domain.EduClass;
import com.jinbang.system.domain.EduGrade;
import com.jinbang.system.domain.EduTeachRel;
import com.jinbang.system.domain.EduTeacher;
import com.jinbang.system.domain.SysTenant;
import com.jinbang.system.domain.SysUser;
import com.jinbang.system.domain.SysUserIdentity;
import com.jinbang.system.mapper.EduClassMapper;
import com.jinbang.system.mapper.EduGradeMapper;
import com.jinbang.system.mapper.EduTeachRelMapper;
import com.jinbang.system.mapper.EduTeacherMapper;
import com.jinbang.system.mapper.SysTenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 教师管理（手册 4.5）：5 种角色——任课教师、班主任、年级主任、学科组长、校领导。
 * 离职：数据跟着组织走，作品跟着人走（PRD 8.3）。
 */
@Service
@RequiredArgsConstructor
public class TeacherService {
    public static final Map<String, String> REL_ROLE = Map.of(
            "SUBJECT_TEACHER", RoleService.TEACHER,
            "HEAD_TEACHER", RoleService.HEAD_TEACHER,
            "GRADE_LEADER", RoleService.GRADE_LEADER,
            "SUBJECT_LEADER", RoleService.SUBJECT_LEADER,
            "SCHOOL_LEADER", RoleService.SCHOOL_LEADER);
    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");

    private final EduTeacherMapper teacherMapper;
    private final EduTeachRelMapper relMapper;
    private final EduClassMapper classMapper;
    private final EduGradeMapper gradeMapper;
    private final SysTenantMapper tenantMapper;
    private final UserService userService;
    private final IdentityService identityService;
    private final RoleService roleService;
    private final SchoolOrgService orgService;
    private final ApplicationEventPublisher events;

    /** @param relType SUBJECT_TEACHER 需要 classId+subject；HEAD_TEACHER 需要 classId；GRADE_LEADER 需要 gradeId；SUBJECT_LEADER 需要 subject */
    public record RelReq(String relType, Long gradeId, Long classId, String subject) {
    }

    public record AddTeacherReq(String name, String phone, String subjects, List<RelReq> rels) {
    }

    public List<EduTeacher> list(String status) {
        return teacherMapper.selectList(Wrappers.<EduTeacher>lambdaQuery()
                .eq(status != null, EduTeacher::getTeacherStatus, status).orderByAsc(EduTeacher::getTeacherName));
    }

    public EduTeacher getByIdentity(Long identityId) {
        return BizException.notNull(teacherMapper.selectOne(Wrappers.<EduTeacher>lambdaQuery()
                .eq(EduTeacher::getIdentityId, identityId)), "教师");
    }

    public List<EduTeachRel> relsOf(Long identityId) {
        return relMapper.selectList(Wrappers.<EduTeachRel>lambdaQuery().eq(EduTeachRel::getIdentityId, identityId)
                .eq(EduTeachRel::getRelStatus, "ACTIVE"));
    }

    @Transactional
    @OperLog(module = "教务-教师", action = "添加教师")
    public EduTeacher add(AddTeacherReq req) {
        long tenantId = TenantContext.currentTenantId();
        if (req.name() == null || req.name().isBlank() || req.phone() == null || !PHONE.matcher(req.phone()).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写教师姓名和正确的手机号");
        }
        SysUser user = userService.findOrCreateByPhone(req.phone(), req.name());
        BizException.throwIf(identityService.findActive(user.getId(), tenantId, IdentityType.TEACHER).isPresent(),
                ErrorCode.CONFLICT, "该手机号已是本校教师");
        SysTenant tenant = tenantMapper.selectById(tenantId);
        Set<String> roleKeys = new LinkedHashSet<>();
        roleKeys.add(RoleService.TEACHER);
        List<RelReq> rels = req.rels() == null ? List.of() : req.rels();
        rels.forEach(r -> roleKeys.add(roleOf(r.relType())));
        SysUserIdentity identity = identityService.create(user.getId(), tenantId, IdentityType.TEACHER,
                (tenant == null ? "" : tenant.getTenantName() + " · ") + req.name(), null, roleKeys);
        EduTeacher t = new EduTeacher();
        t.setIdentityId(identity.getId());
        t.setUserId(user.getId());
        t.setTeacherName(req.name());
        t.setPhone(req.phone());
        t.setSubjects(req.subjects());
        t.setTeacherStatus("ACTIVE");
        teacherMapper.insert(t);
        for (RelReq r : rels) {
            addRelInternal(identity.getId(), r);
        }
        return t;
    }

    @Transactional
    @OperLog(module = "教务-教师", action = "分配任教关系")
    public EduTeachRel addRel(Long identityId, RelReq r) {
        EduTeacher t = getByIdentity(identityId);
        BizException.throwIf(!"ACTIVE".equals(t.getTeacherStatus()), ErrorCode.STATE_TRANSITION_DENIED, "该教师已离职");
        roleService.grant(identityId, t.getTenantId(), List.of(roleOf(r.relType())));
        EduTeachRel rel = addRelInternal(identityId, r);
        identityService.refreshSessionsOf(t.getUserId(), identityId);
        return rel;
    }

    /** 结束任教关系（换班、换学科）：旧班立即不可见，旧的导出链接失效（PRD 8.3）。 */
    @Transactional
    @OperLog(module = "教务-教师", action = "结束任教关系")
    public void endRel(Long relId) {
        EduTeachRel rel = BizException.notNull(relMapper.selectById(relId), "任教关系");
        if (!"ACTIVE".equals(rel.getRelStatus())) {
            return;
        }
        rel.setRelStatus("ENDED");
        rel.setEndedAt(LocalDateTime.now());
        relMapper.updateById(rel);
        if ("HEAD_TEACHER".equals(rel.getRelType())) {
            classMapper.update(Wrappers.<EduClass>lambdaUpdate().set(EduClass::getHeadTeacherIdentityId, null)
                    .eq(EduClass::getId, rel.getClassId()).eq(EduClass::getHeadTeacherIdentityId, rel.getIdentityId()));
        }
        EduTeacher t = getByIdentity(rel.getIdentityId());
        boolean stillHasType = relsOf(rel.getIdentityId()).stream().anyMatch(r -> r.getRelType().equals(rel.getRelType()));
        if (!stillHasType && !"SUBJECT_TEACHER".equals(rel.getRelType())) {
            roleService.revoke(rel.getIdentityId(), t.getTenantId(), roleOf(rel.getRelType()));
        }
        identityService.refreshSessionsOf(t.getUserId(), rel.getIdentityId());
        events.publishEvent(new IdentityRevokedEvent(t.getUserId(), rel.getIdentityId(), t.getTenantId(),
                "HEAD_TEACHER".equals(rel.getRelType()) ? "HEAD_TEACHER_CHANGED" : "TRANSFER_CLASS"));
    }

    /** 更换班主任：新班主任看本班全部历史，旧班主任失去本班全科权限。 */
    @Transactional
    @OperLog(module = "教务-教师", action = "更换班主任")
    public void changeHeadTeacher(Long classId, Long newIdentityId) {
        EduClass c = orgService.getClass(classId);
        relMapper.selectList(Wrappers.<EduTeachRel>lambdaQuery().eq(EduTeachRel::getClassId, classId)
                        .eq(EduTeachRel::getRelType, "HEAD_TEACHER").eq(EduTeachRel::getRelStatus, "ACTIVE"))
                .forEach(r -> endRel(r.getId()));
        addRel(newIdentityId, new RelReq("HEAD_TEACHER", c.getGradeId(), classId, null));
    }

    /**
     * 批量离职（PRD 8.3、AC-14）：学校数据立即不可见、导出链接失效；个人备课归个人，账号保留，身份退回游客。
     */
    @Transactional
    @OperLog(module = "教务-教师", action = "批量离职")
    public int resign(List<Long> identityIds) {
        int n = 0;
        for (Long identityId : identityIds) {
            EduTeacher t = getByIdentity(identityId);
            if (!"ACTIVE".equals(t.getTeacherStatus())) {
                continue;
            }
            relsOf(identityId).forEach(r -> {
                r.setRelStatus("ENDED");
                r.setEndedAt(LocalDateTime.now());
                relMapper.updateById(r);
            });
            classMapper.update(Wrappers.<EduClass>lambdaUpdate().set(EduClass::getHeadTeacherIdentityId, null)
                    .eq(EduClass::getHeadTeacherIdentityId, identityId));
            t.setTeacherStatus("RESIGNED");
            t.setResignedAt(LocalDateTime.now());
            teacherMapper.updateById(t);
            identityService.revoke(identityId, "RESIGN");
            if (identityService.activeIdentities(t.getUserId()).isEmpty()) {
                identityService.ensureVisitorIdentity(t.getUserId(), t.getTeacherName());
            }
            n++;
        }
        return n;
    }

    public record TeacherRow(String name, String phone, String subject, String role, String gradeName,
                             String className) {
    }

    /** 教师导入（手册导入模板：姓名、手机号、学科、角色、年级、班级）。 */
    @Transactional
    @OperLog(module = "教务-教师", action = "导入教师")
    public ImportResult importRows(List<TeacherRow> rows) {
        Long yearId = orgService.currentYear().getId();
        List<ImportResult.RowError> errors = new ArrayList<>();
        List<AddTeacherReq> reqs = new ArrayList<>();
        Set<String> phones = new java.util.HashSet<>();
        Map<String, String> roleNames = Map.of("任课教师", "SUBJECT_TEACHER", "班主任", "HEAD_TEACHER",
                "年级主任", "GRADE_LEADER", "学科组长", "SUBJECT_LEADER", "校领导", "SCHOOL_LEADER");
        for (int i = 0; i < rows.size(); i++) {
            TeacherRow r = rows.get(i);
            int rowNo = i + 2; // 第 1 行是表头
            if (r.name() == null || r.name().isBlank()) {
                errors.add(new ImportResult.RowError(rowNo, "姓名", "不能为空"));
            }
            if (r.phone() == null || !PHONE.matcher(r.phone().trim()).matches()) {
                errors.add(new ImportResult.RowError(rowNo, "手机号", "格式不正确"));
                continue;
            }
            if (!phones.add(r.phone().trim())) {
                errors.add(new ImportResult.RowError(rowNo, "手机号", "与表格中其他行重复"));
            }
            String relType = roleNames.get(r.role() == null || r.role().isBlank() ? "任课教师" : r.role().trim());
            if (relType == null) {
                errors.add(new ImportResult.RowError(rowNo, "角色", "只能是任课教师/班主任/年级主任/学科组长/校领导"));
                continue;
            }
            EduGrade grade = null;
            EduClass clazz = null;
            if (r.gradeName() != null && !r.gradeName().isBlank()) {
                grade = gradeMapper.selectOne(Wrappers.<EduGrade>lambdaQuery().eq(EduGrade::getYearId, yearId)
                        .eq(EduGrade::getGradeName, r.gradeName().trim()));
                if (grade == null) {
                    errors.add(new ImportResult.RowError(rowNo, "年级", "当前学年没有该年级"));
                    continue;
                }
                if (r.className() != null && !r.className().isBlank()) {
                    clazz = classMapper.selectOne(Wrappers.<EduClass>lambdaQuery().eq(EduClass::getGradeId, grade.getId())
                            .eq(EduClass::getClassName, r.className().trim()));
                    if (clazz == null) {
                        errors.add(new ImportResult.RowError(rowNo, "班级", "该年级下没有该班级"));
                        continue;
                    }
                }
            }
            String need = switch (relType) {
                case "SUBJECT_TEACHER" -> clazz == null || blank(r.subject()) ? "任课教师需要填写年级、班级和学科" : null;
                case "HEAD_TEACHER" -> clazz == null ? "班主任需要填写年级和班级" : null;
                case "GRADE_LEADER" -> grade == null ? "年级主任需要填写年级" : null;
                case "SUBJECT_LEADER" -> blank(r.subject()) ? "学科组长需要填写学科" : null;
                default -> null;
            };
            if (need != null) {
                errors.add(new ImportResult.RowError(rowNo, "角色", need));
                continue;
            }
            reqs.add(new AddTeacherReq(r.name().trim(), r.phone().trim(), r.subject(), List.of(new RelReq(relType,
                    grade == null ? null : grade.getId(), clazz == null ? null : clazz.getId(), r.subject()))));
        }
        if (!errors.isEmpty()) {
            return new ImportResult(rows.size(), 0, errors);
        }
        reqs.forEach(this::add);
        return new ImportResult(rows.size(), reqs.size(), List.of());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private String roleOf(String relType) {
        String key = REL_ROLE.get(relType);
        if (key == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的任教类型：" + relType);
        }
        return key;
    }

    private EduTeachRel addRelInternal(Long identityId, RelReq r) {
        Long yearId = orgService.currentYear().getId();
        roleOf(r.relType());
        Long gradeId = r.gradeId();
        if (r.classId() != null) {
            EduClass c = orgService.getClass(r.classId());
            gradeId = c.getGradeId();
        }
        switch (r.relType()) {
            case "SUBJECT_TEACHER" -> BizException.throwIf(r.classId() == null || blank(r.subject()),
                    ErrorCode.BAD_REQUEST, "任课需要指定班级和学科");
            case "HEAD_TEACHER" -> {
                BizException.throwIf(r.classId() == null, ErrorCode.BAD_REQUEST, "班主任需要指定班级");
                EduClass c = orgService.getClass(r.classId());
                BizException.throwIf(c.getHeadTeacherIdentityId() != null
                                && !Objects.equals(c.getHeadTeacherIdentityId(), identityId),
                        ErrorCode.CONFLICT, "该班已有班主任，请先更换");
                c.setHeadTeacherIdentityId(identityId);
                classMapper.updateById(c);
            }
            case "GRADE_LEADER" -> BizException.throwIf(gradeId == null, ErrorCode.BAD_REQUEST, "年级主任需要指定年级");
            case "SUBJECT_LEADER" -> BizException.throwIf(blank(r.subject()), ErrorCode.BAD_REQUEST, "学科组长需要指定学科");
            default -> {
            }
        }
        EduTeachRel rel = new EduTeachRel();
        rel.setYearId(yearId);
        rel.setIdentityId(identityId);
        rel.setRelType(r.relType());
        rel.setGradeId(gradeId);
        rel.setClassId(r.classId());
        rel.setSubject(r.subject());
        rel.setRelStatus("ACTIVE");
        relMapper.insert(rel);
        return rel;
    }
}
