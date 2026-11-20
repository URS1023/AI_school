package com.jinbang.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinbang.account.service.MockPaymentGateway;
import com.jinbang.api.account.AccountApi;
import com.jinbang.api.system.ExportLinkApi;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.DataScope;
import com.jinbang.common.security.IdentityType;
import com.jinbang.common.security.LoginHelper;
import com.jinbang.common.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemAccountIT extends ApiTestSupport {
    @Autowired
    AccountApi accountApi;
    @Autowired
    MockPaymentGateway payGateway;
    @Autowired
    FileStorage storage;
    @Autowired
    ExportLinkApi exportLinkApi;

    @Test
    void registerGivesVisitorIdentityAnd500Coins() throws Exception {
        String token = registerVisitor(newPhone());
        JsonNode me = ok(get("/api/system/user/me", token));
        assertThat(me.path("identity").path("type").asText()).isEqualTo("VISITOR_TEACHER");
        assertThat(me.path("identity").path("tenantId").asLong()).isZero();
        JsonNode acc = ok(get("/api/account/me", token));
        assertThat(acc.path("balance").asLong()).isEqualTo(500);
        assertThat(acc.path("exp").asLong()).isEqualTo(2); // 每日首登 +2
        JsonNode ledger = ok(get("/api/account/coins/ledger", token));
        assertThat(ledger.path("rows").get(0).path("bizLabel").asText()).isEqualTo("注册赠送");
    }

    @Test
    void loginLocksAfterFiveFailures() throws Exception {
        String phone = newPhone();
        registerVisitor(phone);
        for (int i = 0; i < 4; i++) {
            assertThat(login(phone, "wrong").path("code").asInt()).isEqualTo(1002);
        }
        assertThat(login(phone, "wrong").path("code").asInt()).isEqualTo(1003);
        // 锁定期间正确密码也不能登录
        assertThat(login(phone, "Teach1234").path("code").asInt()).isEqualTo(1003);
        // 忘记密码可以解锁
        String code = sms(phone, "RESET");
        ok(post("/api/auth/password/forgot", null, Map.of("phone", phone, "code", code, "newPassword", "NewPass123")));
        ok(login(phone, "NewPass123"));
    }

    @Test
    void ssoTicketIsOneTime() throws Exception {
        String token = registerVisitor(newPhone());
        JsonNode t = ok(post("/api/auth/sso/ticket", token, null));
        String ticket = t.path("ticket").asText();
        JsonNode agent = ok(post("/api/auth/sso/login", null, Map.of("ticket", ticket)));
        assertThat(agent.path("token").asText()).isNotEqualTo(token);
        // 第二次使用同一 ticket 失败
        assertThat(post("/api/auth/sso/login", null, Map.of("ticket", ticket)).path("code").asInt()).isEqualTo(1005);
    }

    /** AC-13 / AC-16：游客直接调学校接口返回 403，并给出申请指引。 */
    @Test
    void visitorGetsForbiddenOnSchoolApis() throws Exception {
        String token = registerVisitor(newPhone());
        JsonNode r = get("/api/school/students?classId=1", token);
        assertThat(r.path("code").asInt()).isEqualTo(403);
        assertThat(r.path("data").path("guide").asText()).contains("邀请码");
        assertThat(post("/api/school/teachers/resign", token, Map.of("ids", List.of(1))).path("code").asInt()).isEqualTo(403);
        // 菜单不下发考试阅卷 / 教务管理
        JsonNode menus = ok(get("/api/system/menu/routers", token));
        assertThat(menus.toString()).doesNotContain("/edu-affairs").doesNotContain("/exam-center");
        // AC-17：占位入口带「即将上线」标记
        assertThat(menus.toString()).contains("\"comingSoon\":true");
    }

    @Test
    void importedTeacherMustChangePasswordFirst() throws Exception {
        SchoolFixture s = SchoolFixture.create(this);
        String phone = newPhone();
        ok(post("/api/school/teachers", s.adminToken(), Map.of("name", "新老师", "phone", phone, "rels", List.of())));
        JsonNode l = ok(login(phone, "123456"));
        assertThat(l.path("mustChangePassword").asBoolean()).isTrue();
        String token = l.path("token").asText();
        assertThat(get("/api/account/me", token).path("code").asInt()).isEqualTo(1004);
        // 弱密码被拒
        assertThat(post("/api/auth/password/change", token, Map.of("oldPassword", "123456", "newPassword", "123")).path("code").asInt())
                .isEqualTo(1008);
        ok(post("/api/auth/password/change", token, Map.of("oldPassword", "123456", "newPassword", "Strong123")));
        ok(get("/api/account/me", token));
    }

    /** R2 / R8：班主任只能看本班，任课教师没有学生管理权限，跨班 403。 */
    @Test
    void dataScopeIsEnforcedAtApiLayer() throws Exception {
        SchoolFixture s = SchoolFixture.create(this);
        JsonNode list = ok(get("/api/school/students?classId=" + s.class1(), s.headToken()));
        assertThat(list.size()).isEqualTo(2);
        assertThat(get("/api/school/students?classId=" + s.class2(), s.headToken()).path("code").asInt()).isEqualTo(403);
        assertThat(get("/api/school/students?classId=" + s.class1(), s.mathToken()).path("code").asInt()).isEqualTo(403);
        assertThat(ok(get("/api/school/students?classId=" + s.class2(), s.adminToken())).size()).isEqualTo(1);
    }

    /** 导入校验：有任何错误就一条都不导入，并逐行给出原因。 */
    @Test
    void studentImportValidatesAllRows() throws Exception {
        SchoolFixture s = SchoolFixture.create(this);
        JsonNode r = post("/api/school/students/import-json", s.adminToken(), List.of(
                Map.of("studentNo", "123", "name", "甲", "gradeName", "高一", "className", "高一1班"),
                Map.of("studentNo", s.stu1No(), "name", "乙", "gradeName", "高一", "className", "高一1班"),
                Map.of("studentNo", "654321", "name", "丙", "gradeName", "高二", "className", "高二1班")));
        assertThat(r.path("code").asInt()).isEqualTo(3004);
        assertThat(r.path("data").path("imported").asInt()).isZero();
        assertThat(r.path("data").path("errors").size()).isGreaterThanOrEqualTo(3);
        assertThat(ok(get("/api/school/students?classId=" + s.class1(), s.adminToken())).size()).isEqualTo(2);
    }

    /** AC-14：离职后看不到学校数据，导出链接失效，身份退回游客。 */
    @Test
    void resignRevokesAccessAndExportLinks() throws Exception {
        SchoolFixture s = SchoolFixture.create(this);
        JsonNode me = ok(get("/api/system/user/me", s.headToken()));
        CurrentIdentity head = om.treeToValue(me.path("identity"), CurrentIdentity.class);
        String key = storage.put("export", "xlsx", "成绩".getBytes());
        String link = LoginHelper.runAs(head, () -> exportLinkApi.createLink(key, "成绩.xlsx", Duration.ofHours(1)));
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(link)
                .header("Authorization", "Bearer " + s.headToken())).andReturn().getResponse().getStatus()).isEqualTo(200);

        ok(post("/api/school/teachers/resign", s.adminToken(), Map.of("ids", List.of(s.headIdentity()))));
        // 旧会话被踢下线
        assertThat(get("/api/school/students?classId=" + s.class1(), s.headToken()).path("code").asInt()).isEqualTo(401);
        // 重新登录后只剩游客身份，学校接口 403，旧导出链接失效
        String again = ok(login(s.headPhone(), "Head12345")).path("token").asText();
        JsonNode ids = ok(get("/api/auth/identities", again)).path("identities");
        assertThat(ids.size()).isEqualTo(1);
        assertThat(ids.get(0).path("type").asText()).isEqualTo("VISITOR_TEACHER");
        assertThat(get("/api/school/students?classId=" + s.class1(), again).path("code").asInt()).isEqualTo(403);
        assertThat(get(link, again).path("code").asInt()).isEqualTo(403);
    }

    /** AC-15：班主任解绑后，家长身份立即失效。 */
    @Test
    void parentBindAndUnbind() throws Exception {
        SchoolFixture s = SchoolFixture.create(this);
        String parent = registerVisitor(newPhone());
        // 姓名不对
        assertThat(post("/api/school/parent/bind", parent, Map.of("schoolId", s.tenantId(), "studentNo", s.stu1No(),
                "studentName", "错名", "inviteCode", s.class1ParentCode())).path("code").asInt()).isEqualTo(400);
        JsonNode b = ok(post("/api/school/parent/bind", parent, Map.of("schoolId", s.tenantId(), "studentNo", s.stu1No(),
                "studentName", "张三", "inviteCode", s.class1ParentCode(), "relation", "父亲")));
        JsonNode ids = ok(get("/api/auth/identities", parent)).path("identities");
        long parentIdentity = b.path("identityId").asLong();
        assertThat(ids.toString()).contains("PARENT");
        ok(post("/api/auth/identity/switch", parent, Map.of("identityId", parentIdentity)));
        assertThat(ok(get("/api/school/classes/" + s.class1() + "/parents", s.headToken())).size()).isEqualTo(1);
        ok(post("/api/school/parent/bindings/" + b.path("id").asLong() + "/unbind", s.headToken(), null));
        // 家长会话里的家长身份被踢下线
        assertThat(get("/api/system/user/me", parent).path("code").asInt()).isEqualTo(401);
        assertThat(ok(get("/api/school/classes/" + s.class1() + "/parents", s.headToken())).size()).isZero();
    }

    /** PRD 11.9：游客用邀请码申请入校 → 管理员审核通过 → 新增教师身份并收到通知。 */
    @Test
    void visitorJoinsSchool() throws Exception {
        SchoolFixture s = SchoolFixture.create(this);
        String phone = newPhone();
        String visitor = registerVisitor(phone);
        long reqId = ok(post("/api/school/join", visitor, Map.of("inviteCode", s.inviteCode(), "realName", "陈老师",
                "subject", "PHYSICS"))).path("id").asLong();
        assertThat(post("/api/school/join", visitor, Map.of("inviteCode", s.inviteCode(), "realName", "陈老师"))
                .path("code").asInt()).isEqualTo(409);
        ok(post("/api/school/join/requests/" + reqId + "/decide", s.adminToken(), Map.of("approve", true)));
        JsonNode ids = ok(get("/api/auth/identities", visitor)).path("identities");
        assertThat(ids.size()).isEqualTo(2);
        assertThat(ok(get("/api/messages/unread-count", visitor)).asLong()).isEqualTo(1);
        // 余额不变（个人数据保留）
        assertThat(ok(get("/api/account/me", visitor)).path("balance").asLong()).isEqualTo(500);
    }

    /** 11.1 / AC-11：冻结 → 结算按实际收费退差额；重复结算幂等；余额不足拒绝。 */
    @Test
    void freezeSettleIsIdempotent() throws Exception {
        String token = registerVisitor(newPhone());
        long uid = ok(get("/api/system/user/me", token)).path("userId").asLong();
        accountApi.freeze(uid, "T-1-" + uid, 30, "AI_TASK", "测试");
        accountApi.freeze(uid, "T-1-" + uid, 30, "AI_TASK", "测试"); // 重复冻结不生效
        assertThat(ok(get("/api/account/me", token)).path("frozen").asLong()).isEqualTo(30);
        assertThat(accountApi.settle("T-1-" + uid, 12, "问答")).isEqualTo(12);
        assertThat(accountApi.settle("T-1-" + uid, 30, "问答")).isEqualTo(12);
        JsonNode acc = ok(get("/api/account/me", token));
        assertThat(acc.path("balance").asLong()).isEqualTo(488);
        assertThat(acc.path("frozen").asLong()).isZero();
        accountApi.freeze(uid, "T-2-" + uid, 10, "AI_TASK", "失败");
        assertThat(accountApi.settle("T-2-" + uid, 0, "失败")).isZero();
        assertThat(ok(get("/api/account/me", token)).path("balance").asLong()).isEqualTo(488);
        assertThatThrownBy(() -> accountApi.freeze(uid, "T-3-" + uid, 10_000, "AI_TASK", "超额"))
                .hasMessageContaining("余额不足");
    }

    @Test
    void rechargeOrderPaysOnce() throws Exception {
        String token = registerVisitor(newPhone());
        String orderNo = ok(post("/api/account/orders", token, Map.of("packageCode", "P30"))).path("orderNo").asText();
        Map<String, String> notify = Map.of("orderNo", orderNo, "channelTxn", "TX1", "sign", payGateway.sign(orderNo, "TX1"));
        assertThat(post("/api/open/pay/notify", null, Map.of("orderNo", orderNo, "channelTxn", "TX1", "sign", "bad"))
                .path("code").asInt()).isEqualTo(403);
        ok(post("/api/open/pay/notify", null, notify));
        ok(post("/api/open/pay/notify", null, notify));
        assertThat(ok(get("/api/account/me", token)).path("balance").asLong()).isEqualTo(830);
    }

    /** PRD 11.8：学年升级，各年级 +1，任教关系清空，当前学年切换。 */
    @Test
    void academicYearUpgrade() throws Exception {
        SchoolFixture s = SchoolFixture.create(this);
        JsonNode r = ok(post("/api/school/years/upgrade?force=true", s.adminToken(), null));
        assertThat(r.path("done").asBoolean()).isTrue();
        assertThat(r.path("promotedClasses").asInt()).isEqualTo(2);
        JsonNode cur = ok(get("/api/school/years/current", s.adminToken()));
        assertThat(cur.path("startYear").asInt()).isEqualTo(2027);
        JsonNode grades = ok(get("/api/school/grades?yearId=" + cur.path("id").asLong(), s.adminToken()));
        assertThat(grades.get(0).path("gradeName").asText()).isEqualTo("高二");
        // 任教关系已清空：班主任原来的 1 班不再可见
        String head = s.headToken();
        assertThat(get("/api/school/students?classId=" + s.class1(), head).path("code").asInt()).isEqualTo(403);
    }

    @SuppressWarnings("unused")
    private static CurrentIdentity platform() {
        return new CurrentIdentity(1L, 1L, 1L, IdentityType.PLATFORM, Set.of(), Set.of("*:*:*"), DataScope.ALL, null);
    }
}
