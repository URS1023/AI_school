package com.jinbang.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.api.operation.NotifyApi;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.common.log.OperLog;
import com.jinbang.common.security.IdentityType;
import com.jinbang.common.security.LoginHelper;
import com.jinbang.common.tenant.TenantContext;
import com.jinbang.system.domain.EduJoinRequest;
import com.jinbang.system.domain.SysTenant;
import com.jinbang.system.domain.SysUser;
import com.jinbang.system.mapper.EduJoinRequestMapper;
import com.jinbang.system.mapper.SysTenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 游客加入学校（PRD 11.9、R5）：输入学校邀请码申请 → 学校管理员审核 → 通过后新增该校教师身份，个人数据保留。
 */
@Service
@RequiredArgsConstructor
public class JoinSchoolService {
    private final EduJoinRequestMapper requestMapper;
    private final SysTenantMapper tenantMapper;
    private final UserService userService;
    private final IdentityService identityService;
    private final TeacherService teacherService;
    private final NotifyApi notifyApi;

    public record ApplyReq(String inviteCode, String realName, String subject, String remark) {
    }

    @Transactional
    public EduJoinRequest apply(ApplyReq req) {
        Long userId = LoginHelper.userId();
        SysTenant school = tenantMapper.selectOne(Wrappers.<SysTenant>lambdaQuery()
                .eq(SysTenant::getInviteCode, req.inviteCode() == null ? "" : req.inviteCode().trim().toUpperCase()));
        if (school == null || !"ACTIVE".equals(school.getTenantStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "学校邀请码不正确，请向学校管理员索取");
        }
        BizException.throwIf(identityService.findActive(userId, school.getId(), IdentityType.TEACHER).isPresent(),
                ErrorCode.CONFLICT, "你已经是该校教师");
        SysUser user = userService.get(userId);
        BizException.throwIf(user.getPhone() == null, ErrorCode.BAD_REQUEST, "请先绑定手机号");
        boolean pending = TenantContext.ignore(() -> requestMapper.exists(Wrappers.<EduJoinRequest>lambdaQuery()
                .eq(EduJoinRequest::getUserId, userId).eq(EduJoinRequest::getTenantId, school.getId())
                .eq(EduJoinRequest::getReqStatus, "PENDING")));
        BizException.throwIf(pending, ErrorCode.CONFLICT, "已提交申请，请等待学校审核");
        EduJoinRequest r = new EduJoinRequest();
        r.setTenantId(school.getId());
        r.setUserId(userId);
        r.setRealName(req.realName());
        r.setSubject(req.subject());
        r.setRemark(req.remark());
        r.setReqStatus("PENDING");
        requestMapper.insert(r);
        return r;
    }

    public List<EduJoinRequest> myRequests() {
        Long userId = LoginHelper.userId();
        return TenantContext.ignore(() -> requestMapper.selectList(Wrappers.<EduJoinRequest>lambdaQuery()
                .eq(EduJoinRequest::getUserId, userId).orderByDesc(EduJoinRequest::getCreatedAt)));
    }

    public List<EduJoinRequest> listForSchool(String status) {
        return requestMapper.selectList(Wrappers.<EduJoinRequest>lambdaQuery()
                .eq(status != null, EduJoinRequest::getReqStatus, status).orderByDesc(EduJoinRequest::getCreatedAt));
    }

    @Transactional
    @OperLog(module = "教务-入校审核", action = "审核入校申请")
    public void decide(Long requestId, boolean approve, String reason) {
        EduJoinRequest r = BizException.notNull(requestMapper.selectById(requestId), "申请");
        BizException.throwIf(!"PENDING".equals(r.getReqStatus()), ErrorCode.STATE_TRANSITION_DENIED, "该申请已处理");
        SysTenant school = tenantMapper.selectById(r.getTenantId());
        r.setDecidedBy(LoginHelper.userId());
        r.setDecidedAt(LocalDateTime.now());
        if (approve) {
            SysUser user = userService.get(r.getUserId());
            teacherService.add(new TeacherService.AddTeacherReq(r.getRealName(), user.getPhone(), r.getSubject(), List.of()));
            r.setReqStatus("APPROVED");
            notifyApi.send(List.of(r.getUserId()), "SYSTEM", "加入学校申请已通过",
                    "你已成为「" + school.getTenantName() + "」的教师，可在头像菜单「身份切换」进入学校身份；任教班级由管理员分配。",
                    "join:" + r.getId());
        } else {
            BizException.throwIf(reason == null || reason.isBlank(), ErrorCode.BAD_REQUEST, "请填写驳回原因");
            r.setReqStatus("REJECTED");
            r.setRejectReason(reason);
            notifyApi.send(List.of(r.getUserId()), "SYSTEM", "加入学校申请未通过",
                    "「" + school.getTenantName() + "」驳回了你的申请：" + reason, "join:" + r.getId());
        }
        requestMapper.updateById(r);
    }
}
