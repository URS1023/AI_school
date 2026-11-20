package com.jinbang.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.alibaba.excel.EasyExcel;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.core.R;
import com.jinbang.system.domain.EduAcademicYear;
import com.jinbang.system.domain.EduClass;
import com.jinbang.system.domain.EduGrade;
import com.jinbang.system.domain.EduJoinRequest;
import com.jinbang.system.domain.EduParentBinding;
import com.jinbang.system.domain.EduStudent;
import com.jinbang.system.domain.EduTeachRel;
import com.jinbang.system.domain.EduTeacher;
import com.jinbang.system.service.ImportResult;
import com.jinbang.system.service.JoinSchoolService;
import com.jinbang.system.service.ParentService;
import com.jinbang.system.service.SchoolOrgService;
import com.jinbang.system.service.StudentService;
import com.jinbang.system.service.TeacherService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 学校管理（教务）：学年、年级、班级、教师、学生、家长、入校审核。 */
@RestController
@RequestMapping("/api/school")
@RequiredArgsConstructor
public class SchoolController {
    private final SchoolOrgService orgService;
    private final TeacherService teacherService;
    private final StudentService studentService;
    private final ParentService parentService;
    private final JoinSchoolService joinService;
    private final com.jinbang.system.service.DirectoryService directoryService;
    private final com.jinbang.system.service.DataScopeService dataScopeService;

    public record YearReq(int startYear, boolean current) {
    }

    public record GradeReq(Long yearId, int level) {
    }

    public record ClassReq(Long gradeId, String name, String classType) {
    }

    public record TeacherIdsReq(List<Long> ids) {
    }

    public record TransferReq(Long classId) {
    }

    public record StatusReq(String status) {
    }

    public record JoinDecideReq(boolean approve, String reason) {
    }

    // ---------------- 学年 / 年级 / 班级 ----------------

    @GetMapping("/years")
    public R<List<EduAcademicYear>> years() {
        return R.ok(orgService.listYears());
    }

    @GetMapping("/years/current")
    public R<EduAcademicYear> currentYear() {
        return R.ok(orgService.currentYear());
    }

    @SaCheckPermission("school:year:manage")
    @PostMapping("/years")
    public R<EduAcademicYear> createYear(@RequestBody YearReq req) {
        return R.ok(orgService.createYear(req.startYear(), req.current()));
    }

    @SaCheckPermission("school:year:manage")
    @PostMapping("/years/{id}/current")
    public R<Void> setCurrent(@PathVariable Long id) {
        orgService.setCurrentYear(id);
        return R.ok();
    }

    /** 学年升级：force=false 时只做校验并返回提示。 */
    @SaCheckPermission("school:year:manage")
    @PostMapping("/years/upgrade")
    public R<SchoolOrgService.UpgradeResult> upgrade(@RequestParam(defaultValue = "false") boolean force) {
        return R.ok(orgService.upgrade(force));
    }

    @GetMapping("/grades")
    public R<List<EduGrade>> grades(@RequestParam Long yearId) {
        return R.ok(orgService.listGrades(yearId));
    }

    @SaCheckPermission("school:org:manage")
    @PostMapping("/grades")
    public R<EduGrade> createGrade(@RequestBody GradeReq req) {
        return R.ok(orgService.createGrade(req.yearId(), req.level()));
    }

    /** 当前学年里当前身份能看到的班级（班级选择器用）；subjects 为空表示该班全学科可见。 */
    public record MyClass(Long classId, String className, Long gradeId, String gradeName, java.util.Set<String> subjects) {
    }

    @GetMapping("/my-classes")
    public R<List<MyClass>> myClasses() {
        var scope = dataScopeService.current();
        var year = orgService.currentYear();
        if (year == null) {
            return R.ok(List.of());
        }
        boolean wide = scope.isSchoolWide() || !scope.schoolSubjects().isEmpty();
        List<MyClass> out = new java.util.ArrayList<>();
        for (EduGrade g : orgService.listGrades(year.getId())) {
            for (EduClass c : orgService.listClasses(g.getId())) {
                boolean full = scope.isSchoolWide() || scope.fullClassIds().contains(c.getId());
                java.util.Set<String> subs = full ? null : scope.classSubjects().get(c.getId());
                if (!full && subs == null && !wide) {
                    continue;
                }
                if (!full && subs == null) {
                    subs = scope.schoolSubjects();
                }
                out.add(new MyClass(c.getId(), c.getClassName(), g.getId(), g.getGradeName(), subs));
            }
        }
        return R.ok(out);
    }

    @GetMapping("/classes")
    public R<List<EduClass>> classes(@RequestParam Long gradeId) {
        return R.ok(orgService.listClasses(gradeId));
    }

    @SaCheckPermission("school:org:manage")
    @PostMapping("/classes")
    public R<EduClass> createClass(@RequestBody ClassReq req) {
        return R.ok(orgService.createClass(req.gradeId(), req.name(), req.classType()));
    }

    @SaCheckPermission("school:parent:manage")
    @PostMapping("/classes/{id}/invite-code/reset")
    public R<String> resetInvite(@PathVariable Long id) {
        return R.ok(orgService.resetParentInviteCode(id));
    }

    // ---------------- 教师 ----------------

    @SaCheckPermission("school:teacher:manage")
    @GetMapping("/teachers")
    public R<List<EduTeacher>> teachers(@RequestParam(required = false) String status) {
        return R.ok(teacherService.list(status));
    }

    @SaCheckPermission("school:teacher:manage")
    @PostMapping("/teachers")
    public R<EduTeacher> addTeacher(@RequestBody TeacherService.AddTeacherReq req) {
        return R.ok(teacherService.add(req));
    }

    @SaCheckPermission("school:teacher:manage")
    @GetMapping("/teachers/{identityId}/rels")
    public R<List<EduTeachRel>> rels(@PathVariable Long identityId) {
        return R.ok(teacherService.relsOf(identityId));
    }

    @SaCheckPermission("school:teacher:manage")
    @PostMapping("/teachers/{identityId}/rels")
    public R<EduTeachRel> addRel(@PathVariable Long identityId, @RequestBody TeacherService.RelReq req) {
        return R.ok(teacherService.addRel(identityId, req));
    }

    @SaCheckPermission("school:teacher:manage")
    @DeleteMapping("/teach-rels/{relId}")
    public R<Void> endRel(@PathVariable Long relId) {
        teacherService.endRel(relId);
        return R.ok();
    }

    @SaCheckPermission("school:teacher:manage")
    @PostMapping("/classes/{classId}/head-teacher")
    public R<Void> changeHead(@PathVariable Long classId, @RequestBody Map<String, Long> body) {
        teacherService.changeHeadTeacher(classId, body.get("identityId"));
        return R.ok();
    }

    @SaCheckPermission("school:teacher:manage")
    @PostMapping("/teachers/resign")
    public R<Integer> resign(@RequestBody TeacherIdsReq req) {
        return R.ok(teacherService.resign(req.ids()));
    }

    /** Excel 列：姓名、手机号、学科、角色、年级、班级。 */
    @SaCheckPermission("school:teacher:manage")
    @PostMapping("/teachers/import")
    public R<ImportResult> importTeachers(@RequestParam("file") MultipartFile file) {
        return importResult(teacherService.importRows(readRows(file, r -> new TeacherService.TeacherRow(
                cell(r, 0), cell(r, 1), cell(r, 2), cell(r, 3), cell(r, 4), cell(r, 5)))));
    }

    @SaCheckPermission("school:teacher:manage")
    @PostMapping("/teachers/import-json")
    public R<ImportResult> importTeachersJson(@RequestBody List<TeacherService.TeacherRow> rows) {
        return importResult(teacherService.importRows(rows));
    }

    // ---------------- 学生 ----------------

    @SaCheckPermission("school:student:view")
    @GetMapping("/students")
    public R<List<EduStudent>> students(@RequestParam Long classId) {
        return R.ok(studentService.listByClass(classId));
    }

    @SaCheckPermission("school:student:manage")
    @PostMapping("/students")
    public R<EduStudent> addStudent(@RequestBody StudentService.StudentReq req) {
        return R.ok(studentService.add(req));
    }

    /** Excel 列：学号、姓名、性别、年级、班级、学籍号、卡号。 */
    @SaCheckPermission("school:student:manage")
    @PostMapping("/students/import")
    public R<ImportResult> importStudents(@RequestParam("file") MultipartFile file) {
        return importResult(studentService.importRows(readRows(file, r -> new StudentService.StudentRow(
                cell(r, 0), cell(r, 1), cell(r, 2), cell(r, 3), cell(r, 4), cell(r, 5), cell(r, 6)))));
    }

    @SaCheckPermission("school:student:manage")
    @PostMapping("/students/import-json")
    public R<ImportResult> importStudentsJson(@RequestBody List<StudentService.StudentRow> rows) {
        return importResult(studentService.importRows(rows));
    }

    @SaCheckPermission("school:student:manage")
    @PostMapping("/students/{id}/transfer")
    public R<Void> transfer(@PathVariable Long id, @RequestBody TransferReq req) {
        studentService.transfer(id, req.classId());
        return R.ok();
    }

    @SaCheckPermission("school:student:manage")
    @PostMapping("/students/{id}/status")
    public R<Void> status(@PathVariable Long id, @RequestBody StatusReq req) {
        studentService.changeStatus(id, req.status());
        return R.ok();
    }

    @SaCheckPermission("school:student:manage")
    @PostMapping("/students/{id}/account")
    public R<String> openAccount(@PathVariable Long id) {
        return R.ok(studentService.openAccount(id));
    }

    // ---------------- 家长 ----------------

    @PostMapping("/parent/bind")
    public R<EduParentBinding> bind(@RequestBody ParentService.BindReq req) {
        return R.ok(parentService.bind(req));
    }

    @GetMapping("/parent/children")
    public R<List<EduParentBinding>> children() {
        return R.ok(parentService.myChildren());
    }

    @SaCheckPermission("school:parent:manage")
    @GetMapping("/classes/{classId}/parents")
    public R<List<EduParentBinding>> parents(@PathVariable Long classId) {
        return R.ok(parentService.listByClass(classId));
    }

    /** 班主任解绑，或家长自己解绑。 */
    @PostMapping("/parent/bindings/{id}/unbind")
    public R<Void> unbind(@PathVariable Long id) {
        parentService.unbind(id);
        return R.ok();
    }

    // ---------------- 游客加入学校 ----------------

    @PostMapping("/join")
    public R<EduJoinRequest> apply(@RequestBody JoinSchoolService.ApplyReq req) {
        return R.ok(joinService.apply(req));
    }

    @GetMapping("/join/mine")
    public R<List<EduJoinRequest>> mine() {
        return R.ok(joinService.myRequests());
    }

    @SaCheckPermission("school:join:audit")
    @GetMapping("/join/requests")
    public R<List<EduJoinRequest>> requests(@RequestParam(required = false) String status) {
        return R.ok(joinService.listForSchool(status));
    }

    @SaCheckPermission("school:join:audit")
    @PostMapping("/join/requests/{id}/decide")
    public R<Void> decide(@PathVariable Long id, @RequestBody JoinDecideReq req) {
        joinService.decide(id, req.approve(), req.reason());
        return R.ok();
    }

    // ---------------- 通讯录（前端用） ----------------

    public record NamesReq(List<Long> studentIds, List<Long> userIds) {
    }

    /** 按 ID 批量取姓名（带数据权限，看不到的不返回）。 */
    @PostMapping("/names")
    public R<com.jinbang.system.service.DirectoryService.Names> names(@RequestBody NamesReq req) {
        return R.ok(directoryService.names(com.jinbang.common.security.LoginHelper.require(), req.studentIds(), req.userIds()));
    }

    @GetMapping("/teachers/simple")
    public R<List<com.jinbang.system.service.DirectoryService.TeacherBrief>> simpleTeachers(
            @RequestParam(required = false) String keyword) {
        return R.ok(directoryService.teachers(com.jinbang.common.security.LoginHelper.require(), keyword));
    }

    // ---------------- 工具 ----------------

    private static R<ImportResult> importResult(ImportResult r) {
        R<ImportResult> out = r.ok() ? R.ok(r) : R.fail(ErrorCode.IMPORT_INVALID);
        out.setData(r);
        return out;
    }

    private static <T> List<T> readRows(MultipartFile file, Function<Map<Integer, String>, T> mapper) {
        try {
            List<Map<Integer, String>> rows = EasyExcel.read(file.getInputStream()).sheet().headRowNumber(1).doReadSync();
            return rows.stream().map(mapper).toList();
        } catch (IOException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "无法读取 Excel 文件");
        }
    }

    private static String cell(Map<Integer, String> row, int i) {
        String v = row.get(i);
        return v == null ? null : v.trim();
    }
}
