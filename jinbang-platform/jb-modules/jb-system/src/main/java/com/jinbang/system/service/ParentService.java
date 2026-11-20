package com.jinbang.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jinbang.api.system.IdentityRevokedEvent;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.datascope.DataScopeProvider;
import com.jinbang.common.log.OperLog;
import com.jinbang.common.security.IdentityType;
import com.jinbang.common.security.LoginHelper;
import com.jinbang.common.tenant.TenantContext;
import com.jinbang.system.domain.EduClass;
import com.jinbang.system.domain.EduParentBinding;
import com.jinbang.system.domain.EduStudent;
import com.jinbang.system.domain.SysTenant;
import com.jinbang.system.domain.SysUserIdentity;
import com.jinbang.system.mapper.EduClassMapper;
import com.jinbang.system.mapper.EduParentBindingMapper;
import com.jinbang.system.mapper.EduStudentMapper;
import com.jinbang.system.mapper.SysTenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 家长绑定（PRD 11.10）：学号 + 姓名 + 班级邀请码三项都对才能绑定，可绑定多个孩子；
 * 班主任端可见绑定状态、可解绑，解绑后家长立即看不到孩子的任何数据（AC-15）。
 */
@Service
@RequiredArgsConstructor
public class ParentService {
    private static final int MAX_TRIES_PER_HOUR = 10;

    private final EduParentBindingMapper bindingMapper;
    private final EduStudentMapper studentMapper;
    private final EduClassMapper classMapper;
    private final SysTenantMapper tenantMapper;
    private final IdentityService identityService;
    private final DataScopeProvider dataScope;
    private final ApplicationEventPublisher events;
    private final Cache<Long, AtomicInteger> tries = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).build();

    public record BindReq(Long schoolId, String studentNo, String studentName, String inviteCode, String relation) {
    }

    @Transactional
    public EduParentBinding bind(BindReq req) {
        Long userId = LoginHelper.userId();
        // 防止枚举学号：每个账号每小时最多尝试 10 次
        if (tries.get(userId, k -> new AtomicInteger()).incrementAndGet() > MAX_TRIES_PER_HOUR) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "尝试次数过多，请 1 小时后再试");
        }
        SysTenant school = BizException.notNull(tenantMapper.selectById(req.schoolId()), "学校");
        return TenantContext.withTenant(school.getId(), () -> {
            EduStudent s = studentMapper.selectOne(Wrappers.<EduStudent>lambdaQuery()
                    .eq(EduStudent::getStudentNo, req.studentNo()).eq(EduStudent::getStudentStatus, "ACTIVE"));
            EduClass c = s == null ? null : classMapper.selectById(s.getClassId());
            // 三项任何一项不对都给同一个提示，不泄露学号是否存在
            if (s == null || c == null || !s.getStudentName().equals(req.studentName())
                    || c.getParentInviteCode() == null || !c.getParentInviteCode().equalsIgnoreCase(req.inviteCode())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "学号、姓名或班级邀请码不正确，请向班主任核实");
            }
            boolean bound = bindingMapper.exists(Wrappers.<EduParentBinding>lambdaQuery()
                    .eq(EduParentBinding::getParentUserId, userId).eq(EduParentBinding::getStudentId, s.getId())
                    .eq(EduParentBinding::getBindStatus, "BOUND"));
            BizException.throwIf(bound, ErrorCode.CONFLICT, "已绑定该学生");
            SysUserIdentity identity = identityService.findActive(userId, school.getId(), IdentityType.PARENT)
                    .orElseGet(() -> identityService.create(userId, school.getId(), IdentityType.PARENT,
                            school.getTenantName() + " · 家长", null, List.of(RoleService.PARENT)));
            EduParentBinding b = new EduParentBinding();
            b.setParentUserId(userId);
            b.setIdentityId(identity.getId());
            b.setStudentId(s.getId());
            b.setRelation(req.relation());
            b.setBindStatus("BOUND");
            bindingMapper.insert(b);
            return b;
        });
    }

    /** 班主任查看本班家长绑定状态。 */
    public List<EduParentBinding> listByClass(Long classId) {
        dataScope.checkClass(classId, null);
        List<Long> studentIds = studentMapper.selectList(Wrappers.<EduStudent>lambdaQuery()
                .eq(EduStudent::getClassId, classId)).stream().map(EduStudent::getId).toList();
        return studentIds.isEmpty() ? List.of() : bindingMapper.selectList(Wrappers.<EduParentBinding>lambdaQuery()
                .in(EduParentBinding::getStudentId, studentIds).eq(EduParentBinding::getBindStatus, "BOUND"));
    }

    @Transactional
    @OperLog(module = "教务-家长", action = "解绑家长")
    public void unbind(Long bindingId) {
        EduParentBinding b = BizException.notNull(bindingMapper.selectById(bindingId), "绑定记录");
        EduStudent s = BizException.notNull(studentMapper.selectById(b.getStudentId()), "学生");
        boolean self = LoginHelper.current().map(ci -> ci.userId().equals(b.getParentUserId())).orElse(false);
        if (!self) {
            dataScope.checkClass(s.getClassId(), null);
        }
        if (!"BOUND".equals(b.getBindStatus())) {
            return;
        }
        b.setBindStatus("UNBOUND");
        b.setUnboundAt(LocalDateTime.now());
        b.setUnboundBy(LoginHelper.userId());
        bindingMapper.updateById(b);
        boolean hasOther = bindingMapper.exists(Wrappers.<EduParentBinding>lambdaQuery()
                .eq(EduParentBinding::getIdentityId, b.getIdentityId()).eq(EduParentBinding::getBindStatus, "BOUND"));
        if (hasOther) {
            events.publishEvent(new IdentityRevokedEvent(b.getParentUserId(), b.getIdentityId(), b.getTenantId(),
                    "UNBIND_PARENT"));
        } else {
            identityService.revoke(b.getIdentityId(), "UNBIND_PARENT");
        }
    }

    /** 家长查看自己绑定的孩子（跨学校）。 */
    public List<EduParentBinding> myChildren() {
        Long userId = LoginHelper.userId();
        return TenantContext.ignore(() -> bindingMapper.selectList(Wrappers.<EduParentBinding>lambdaQuery()
                .eq(EduParentBinding::getParentUserId, userId).eq(EduParentBinding::getBindStatus, "BOUND")));
    }
}
