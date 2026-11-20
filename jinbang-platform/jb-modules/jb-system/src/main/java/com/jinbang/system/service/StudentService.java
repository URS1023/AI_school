package com.jinbang.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.Constants;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.datascope.DataScopeProvider;
import com.jinbang.common.datascope.VisibleScope;
import com.jinbang.common.log.OperLog;
import com.jinbang.common.security.IdentityType;
import com.jinbang.common.tenant.TenantContext;
import com.jinbang.system.domain.EduClass;
import com.jinbang.system.domain.EduGrade;
import com.jinbang.system.domain.EduStudent;
import com.jinbang.system.domain.EduStudentClassLog;
import com.jinbang.system.domain.SysUser;
import com.jinbang.system.mapper.EduClassMapper;
import com.jinbang.system.mapper.EduGradeMapper;
import com.jinbang.system.mapper.EduStudentClassLogMapper;
import com.jinbang.system.mapper.EduStudentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 学生管理（手册 4.4：学号、姓名、性别、学籍号、卡号；导入模板）。 */
@Service
@RequiredArgsConstructor
public class StudentService {
    private final EduStudentMapper studentMapper;
    private final EduClassMapper classMapper;
    private final EduGradeMapper gradeMapper;
    private final EduStudentClassLogMapper classLogMapper;
    private final SchoolOrgService orgService;
    private final UserService userService;
    private final IdentityService identityService;
    private final DataScopeProvider dataScope;

    @Value("${jb.school.student-no-length:6}")
    private int studentNoLength;

    public record StudentReq(String studentNo, String name, String gender, Long classId, String registerNo,
                             String cardNo) {
    }

    public EduStudent get(Long id) {
        return BizException.notNull(studentMapper.selectById(id), "学生");
    }

    /** 按班级列学生，带数据权限校验（班主任只能看本班，R2/R8）。 */
    public List<EduStudent> listByClass(Long classId) {
        VisibleScope scope = dataScope.current();
        if (!scope.isSchoolWide() && !scope.anyClassIds().contains(classId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return studentMapper.selectList(Wrappers.<EduStudent>lambdaQuery().eq(EduStudent::getClassId, classId)
                .eq(EduStudent::getStudentStatus, "ACTIVE").orderByAsc(EduStudent::getStudentNo));
    }

    @Transactional
    @OperLog(module = "教务-学生", action = "添加学生")
    public EduStudent add(StudentReq req) {
        List<String> errs = validate(req, new HashSet<>());
        if (!errs.isEmpty()) {
            throw new BizException(ErrorCode.IMPORT_INVALID, String.join("；", errs));
        }
        return insert(req);
    }

    private EduStudent insert(StudentReq req) {
        EduClass c = orgService.getClass(req.classId());
        EduStudent s = new EduStudent();
        s.setStudentNo(req.studentNo().trim());
        s.setStudentName(req.name().trim());
        s.setGender(req.gender());
        s.setClassId(req.classId());
        s.setRegisterNo(req.registerNo());
        s.setCardNo(req.cardNo());
        s.setStudentStatus("ACTIVE");
        EduGrade g = gradeMapper.selectById(c.getGradeId());
        if (g != null) {
            s.setEnrollYear(orgService.currentYear().getStartYear() - ((g.getGradeLevel() - 1) % 3));
        }
        studentMapper.insert(s);
        EduStudentClassLog log = new EduStudentClassLog();
        log.setStudentId(s.getId());
        log.setClassId(req.classId());
        log.setYearId(c.getYearId());
        log.setJoinedAt(LocalDateTime.now());
        classLogMapper.insert(log);
        return s;
    }

    private List<String> validate(StudentReq r, Set<String> seenNos) {
        List<String> errs = new ArrayList<>();
        String no = r.studentNo() == null ? "" : r.studentNo().trim();
        if (!no.matches("\\d{" + studentNoLength + "}")) {
            errs.add("学号必须是 " + studentNoLength + " 位数字（答题卡手写学号按此位数识别）");
        } else if (!seenNos.add(no)) {
            errs.add("学号与表格中其他行重复");
        } else if (studentMapper.exists(Wrappers.<EduStudent>lambdaQuery().eq(EduStudent::getStudentNo, no))) {
            errs.add("学号已存在");
        }
        if (r.name() == null || r.name().isBlank()) {
            errs.add("姓名不能为空");
        }
        if (r.gender() != null && !r.gender().isBlank() && !Set.of("男", "女").contains(r.gender())) {
            errs.add("性别只能填男或女");
        }
        if (r.classId() == null || classMapper.selectById(r.classId()) == null) {
            errs.add("班级不存在");
        }
        return errs;
    }

    public record StudentRow(String studentNo, String name, String gender, String gradeName, String className,
                             String registerNo, String cardNo) {
    }

    @Transactional
    @OperLog(module = "教务-学生", action = "导入学生")
    public ImportResult importRows(List<StudentRow> rows) {
        Long yearId = orgService.currentYear().getId();
        List<ImportResult.RowError> errors = new ArrayList<>();
        List<StudentReq> reqs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            StudentRow r = rows.get(i);
            int rowNo = i + 2;
            EduGrade g = r.gradeName() == null ? null : gradeMapper.selectOne(Wrappers.<EduGrade>lambdaQuery()
                    .eq(EduGrade::getYearId, yearId).eq(EduGrade::getGradeName, r.gradeName().trim()));
            EduClass c = g == null || r.className() == null ? null : classMapper.selectOne(Wrappers.<EduClass>lambdaQuery()
                    .eq(EduClass::getGradeId, g.getId()).eq(EduClass::getClassName, r.className().trim()));
            if (c == null) {
                errors.add(new ImportResult.RowError(rowNo, "年级/班级", "当前学年找不到该年级或班级"));
            }
            StudentReq req = new StudentReq(r.studentNo(), r.name(), r.gender(), c == null ? null : c.getId(),
                    r.registerNo(), r.cardNo());
            for (String e : validate(req, seen)) {
                if (c != null || !e.equals("班级不存在")) {
                    errors.add(new ImportResult.RowError(rowNo, "", e));
                }
            }
            reqs.add(req);
        }
        if (!errors.isEmpty()) {
            return new ImportResult(rows.size(), 0, errors);
        }
        reqs.forEach(this::insert);
        return new ImportResult(rows.size(), reqs.size(), List.of());
    }

    /** 学生转班：新班老师可以看该生全部历史；旧班统计保留历史记录（PRD 8.3）。 */
    @Transactional
    @OperLog(module = "教务-学生", action = "学生转班")
    public void transfer(Long studentId, Long newClassId) {
        EduStudent s = get(studentId);
        EduClass c = orgService.getClass(newClassId);
        orgService.moveStudent(s, newClassId, c.getYearId(), LocalDateTime.now());
    }

    /** 转出 / 毕业：原校归档只读。 */
    @Transactional
    @OperLog(module = "教务-学生", action = "学生转出")
    public void changeStatus(Long studentId, String status) {
        BizException.throwIf(!Set.of("ACTIVE", "TRANSFERRED", "GRADUATED").contains(status), ErrorCode.BAD_REQUEST);
        EduStudent s = get(studentId);
        s.setStudentStatus(status);
        studentMapper.updateById(s);
        if (!"ACTIVE".equals(status) && s.getUserId() != null) {
            identityService.findActive(s.getUserId(), s.getTenantId(), IdentityType.STUDENT)
                    .ifPresent(i -> identityService.revoke(i.getId(), "STUDENT_" + status));
        }
    }

    /**
     * 开通学生账号（学生端）。登录名 = s + 学校ID + 学号，初始密码 123456，首次登录强制改密。
     */
    @Transactional
    @OperLog(module = "教务-学生", action = "开通学生账号")
    public String openAccount(Long studentId) {
        EduStudent s = get(studentId);
        long tenantId = TenantContext.currentTenantId();
        String username = "s" + tenantId + s.getStudentNo();
        if (s.getUserId() == null) {
            SysUser u = userService.create(null, username, s.getStudentName(), Constants.DEFAULT_PASSWORD, true);
            s.setUserId(u.getId());
            studentMapper.updateById(s);
        }
        if (identityService.findActive(s.getUserId(), tenantId, IdentityType.STUDENT).isEmpty()) {
            identityService.create(s.getUserId(), tenantId, IdentityType.STUDENT, s.getStudentName(), s.getId(),
                    List.of(RoleService.STUDENT));
        }
        return username;
    }
}
