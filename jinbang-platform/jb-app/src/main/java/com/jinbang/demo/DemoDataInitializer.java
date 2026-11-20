package com.jinbang.demo;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.DataScope;
import com.jinbang.common.security.IdentityType;
import com.jinbang.common.security.LoginHelper;
import com.jinbang.common.tenant.TenantContext;
import com.jinbang.system.domain.EduClass;
import com.jinbang.system.domain.EduGrade;
import com.jinbang.system.domain.SysTenant;
import com.jinbang.system.domain.SysUser;
import com.jinbang.system.mapper.SysTenantMapper;
import com.jinbang.system.service.SchoolOrgService;
import com.jinbang.system.service.StudentService;
import com.jinbang.system.service.TeacherService;
import com.jinbang.system.service.TenantAdminService;
import com.jinbang.system.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 开发环境演示数据（jb.demo-data=true，仅 dev profile 默认开启）：一所学校、两个班、三位老师、六名学生。
 * 所有演示账号密码都是 Demo1234（不要在生产环境开启）。
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "jb.demo-data", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {
    public static final String PASSWORD = "Demo1234";
    private static final String SCHOOL = "演示中学";

    private final SysTenantMapper tenantMapper;
    private final TenantAdminService tenantAdminService;
    private final SchoolOrgService orgService;
    private final TeacherService teacherService;
    private final StudentService studentService;
    private final UserService userService;
    private final com.jinbang.system.service.IdentityService identityService;
    private final com.jinbang.api.account.AccountApi accountApi;

    @Override
    public void run(ApplicationArguments args) {
        if (tenantMapper.exists(Wrappers.<SysTenant>lambdaQuery().eq(SysTenant::getTenantName, SCHOOL))) {
            return;
        }
        CurrentIdentity platform = new CurrentIdentity(0L, 0L, 1L, IdentityType.PLATFORM, Set.of("platform_admin"),
                Set.of("*"), DataScope.ALL, null);
        SysTenant school = LoginHelper.runAs(platform, () -> tenantAdminService.createSchool(new TenantAdminService.CreateSchoolReq(
                SCHOOL, "370100", "1", "演示管理员", "13900000001", "STANDARD", null)));
        CurrentIdentity admin = new CurrentIdentity(0L, 0L, school.getId(), IdentityType.SCHOOL_ADMIN, Set.of("school_admin"),
                Set.of("*"), DataScope.SCHOOL, null);
        LoginHelper.runAs(admin, () -> TenantContext.withTenant(school.getId(), () -> {
            var year = orgService.createYear(2026, true);
            EduGrade g = orgService.createGrade(year.getId(), 10);
            EduClass c1 = orgService.createClass(g.getId(), "高一1班", "ADMIN");
            EduClass c2 = orgService.createClass(g.getId(), "高一2班", "ADMIN");
            teacherService.add(new TeacherService.AddTeacherReq("王老师", "13900000002", "CHINESE", List.of(
                    new TeacherService.RelReq("HEAD_TEACHER", null, c1.getId(), null),
                    new TeacherService.RelReq("SUBJECT_TEACHER", null, c1.getId(), "CHINESE"))));
            teacherService.add(new TeacherService.AddTeacherReq("李老师", "13900000003", "MATH", List.of(
                    new TeacherService.RelReq("SUBJECT_TEACHER", null, c1.getId(), "MATH"),
                    new TeacherService.RelReq("SUBJECT_TEACHER", null, c2.getId(), "MATH"))));
            teacherService.add(new TeacherService.AddTeacherReq("赵主任", "13900000004", "MATH", List.of(
                    new TeacherService.RelReq("GRADE_LEADER", g.getId(), null, null))));
            String[][] students = {{"260101", "张三", "男"}, {"260102", "李四", "女"}, {"260103", "王五", "男"},
                    {"260201", "赵六", "女"}, {"260202", "孙七", "男"}, {"260203", "周八", "女"}};
            for (String[] st : students) {
                Long classId = st[0].startsWith("2601") ? c1.getId() : c2.getId();
                var s = studentService.add(new StudentService.StudentReq(st[0], st[1], st[2], classId, null, null));
                if (st[0].equals("260101")) {
                    studentService.openAccount(s.getId());
                }
            }
            return null;
        }));
        // 游客教师（自注册效果：访客学校、送 500 币），用于体验游客视角和 403 提示
        SysUser visitor = userService.create("13900000009", null, "游客老师", PASSWORD, false);
        identityService.ensureVisitorIdentity(visitor.getId(), visitor.getNickname());
        accountApi.grantCoins(visitor.getId(), 500, "REGISTER", String.valueOf(visitor.getId()), "注册赠送");
        for (String account : List.of("13900000001", "13900000002", "13900000003", "13900000004", "s" + school.getId() + "260101")) {
            SysUser u = userService.findByAccount(account).orElseThrow();
            userService.setPassword(u, PASSWORD);
        }
        log.info("""
                演示数据已就绪（{}，邀请码 {}），密码均为 {}：
                  学校管理员 13900000001 | 班主任(高一1班, 语文) 13900000002 | 数学任课(1班、2班) 13900000003
                  年级主任(高一) 13900000004 | 游客教师 13900000009 | 学生 张三 s{}260101""", SCHOOL, school.getInviteCode(), PASSWORD, school.getId());
    }
}
