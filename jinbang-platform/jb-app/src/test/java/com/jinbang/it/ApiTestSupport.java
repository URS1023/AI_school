package com.jinbang.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinbang.system.auth.SmsSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 接口级集成测试基类：真实启动全部模块（H2），通过 HTTP 调用验证 PRD 第 17 章验收用例。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(ApiTestSupport.TestBeans.class)
public abstract class ApiTestSupport {
    static final Map<String, String> SMS = new ConcurrentHashMap<>();
    private static final AtomicLong PHONE_SEQ = new AtomicLong(13800000000L + (System.nanoTime() % 1_000_000) * 100);

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper om;

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        SmsSender capturingSms() {
            return (phone, scene, code) -> SMS.put(scene + ":" + phone, code);
        }
    }

    protected static String newPhone() {
        return String.valueOf(PHONE_SEQ.incrementAndGet());
    }

    protected JsonNode call(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body));
        }
        String s = mvc.perform(req).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return s.isEmpty() ? om.createObjectNode() : om.readTree(s);
    }

    protected JsonNode post(String path, String token, Object body) throws Exception {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path), token, body);
    }

    protected JsonNode get(String path, String token) throws Exception {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path), token, null);
    }

    /** 断言成功并返回 data。 */
    protected JsonNode ok(JsonNode r) {
        assertThat(r.path("code").asInt()).as(r.toString()).isEqualTo(200);
        return r.path("data");
    }

    protected String sms(String phone, String scene) throws Exception {
        ok(post("/api/auth/sms/send", null, Map.of("phone", phone, "scene", scene)));
        return SMS.get(scene + ":" + phone);
    }

    /** 自注册一个游客教师，返回 token。 */
    protected String registerVisitor(String phone) throws Exception {
        String code = sms(phone, "REGISTER");
        return ok(post("/api/auth/register", null, Map.of("phone", phone, "code", code, "password", "Teach1234",
                "nickname", "游客" + phone.substring(7)))).path("token").asText();
    }

    protected JsonNode login(String account, String password) throws Exception {
        return post("/api/auth/login", null, Map.of("account", account, "password", password));
    }

    /** 用初始密码登录并完成强制改密，返回可用 token。 */
    protected String loginAndChangeDefault(String account, String initial, String newPwd) throws Exception {
        JsonNode first = login(account, initial);
        if (first.path("code").asInt() != 200) {
            return ok(login(account, newPwd)).path("token").asText();
        }
        String token = first.path("data").path("token").asText();
        if (first.path("data").path("mustChangePassword").asBoolean()) {
            ok(post("/api/auth/password/change", token, Map.of("oldPassword", initial, "newPassword", newPwd)));
        }
        return token;
    }

    protected String adminToken() throws Exception {
        return loginAndChangeDefault("admin", "Admin@12345", "Admin@67890");
    }
}
