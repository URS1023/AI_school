package com.jinbang.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.common.datascope.DataScopeProvider;
import com.jinbang.common.datascope.VisibleScope;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.common.security.DataScope;
import com.jinbang.common.security.IdentityType;
import com.jinbang.common.security.LoginHelper;
import com.jinbang.system.domain.EduClass;
import com.jinbang.system.domain.EduParentBinding;
import com.jinbang.system.domain.EduTeachRel;
import com.jinbang.system.domain.SysUserIdentity;
import com.jinbang.system.mapper.EduClassMapper;
import com.jinbang.system.mapper.EduParentBindingMapper;
import com.jinbang.system.mapper.EduTeachRelMapper;
import com.jinbang.system.mapper.SysUserIdentityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计算当前身份的数据范围（PRD 8.2 R2、R7；8.4 权限矩阵）。
 * 只认「当前学年、有效」的任教关系：换班、离职、学年升级后旧班立即不可见（8.3）。
 */
@Service
@RequiredArgsConstructor
public class DataScopeService implements DataScopeProvider {
    private final EduTeachRelMapper teachRelMapper;
    private final EduClassMapper classMapper;
    private final EduParentBindingMapper bindingMapper;
    private final SysUserIdentityMapper identityMapper;

    @Override
    public VisibleScope current() {
        return LoginHelper.current().map(this::of).orElse(VisibleScope.none(-1));
    }

    public VisibleScope of(CurrentIdentity ci) {
        long tenant = ci.tenantId();
        if (ci.isPlatform()) {
            return new VisibleScope(DataScope.ALL, tenant, Set.of(), Map.of(), Set.of(), Set.of());
        }
        return switch (ci.type()) {
            case SCHOOL_ADMIN -> new VisibleScope(DataScope.SCHOOL, tenant, Set.of(), Map.of(), Set.of(), Set.of());
            case TEACHER -> teacherScope(ci);
            case STUDENT -> {
                SysUserIdentity identity = identityMapper.selectById(ci.identityId());
                Set<Long> ids = identity == null || identity.getStudentId() == null ? Set.of() : Set.of(identity.getStudentId());
                yield new VisibleScope(DataScope.SELF, tenant, Set.of(), Map.of(), Set.of(), ids);
            }
            case PARENT -> {
                Set<Long> ids = new HashSet<>();
                bindingMapper.selectList(Wrappers.<EduParentBinding>lambdaQuery()
                                .eq(EduParentBinding::getIdentityId, ci.identityId())
                                .eq(EduParentBinding::getBindStatus, "BOUND"))
                        .forEach(b -> ids.add(b.getStudentId()));
                yield new VisibleScope(DataScope.SELF, tenant, Set.of(), Map.of(), Set.of(), ids);
            }
            default -> VisibleScope.none(tenant);
        };
    }

    private VisibleScope teacherScope(CurrentIdentity ci) {
        if (ci.hasRole(RoleService.SCHOOL_LEADER)) {
            return new VisibleScope(DataScope.SCHOOL, ci.tenantId(), Set.of(), Map.of(), Set.of(), Set.of());
        }
        List<EduTeachRel> rels = teachRelMapper.selectList(Wrappers.<EduTeachRel>lambdaQuery()
                .eq(EduTeachRel::getIdentityId, ci.identityId()).eq(EduTeachRel::getRelStatus, "ACTIVE"));
        Set<Long> full = new HashSet<>();
        Map<Long, Set<String>> partial = new HashMap<>();
        Set<String> subjects = new HashSet<>();
        DataScope scope = DataScope.SELF;
        for (EduTeachRel r : rels) {
            switch (r.getRelType()) {
                case "SCHOOL_LEADER" -> {
                    return new VisibleScope(DataScope.SCHOOL, ci.tenantId(), Set.of(), Map.of(), Set.of(), Set.of());
                }
                case "GRADE_LEADER" -> {
                    classMapper.selectList(Wrappers.<EduClass>lambdaQuery().eq(EduClass::getGradeId, r.getGradeId()))
                            .forEach(c -> full.add(c.getId()));
                    scope = DataScope.widest(scope, DataScope.GRADE);
                }
                case "SUBJECT_LEADER" -> {
                    subjects.add(r.getSubject());
                    scope = DataScope.widest(scope, DataScope.SUBJECT_GROUP);
                }
                case "HEAD_TEACHER" -> {
                    full.add(r.getClassId());
                    scope = DataScope.widest(scope, DataScope.CLASS);
                }
                case "SUBJECT_TEACHER" -> {
                    partial.computeIfAbsent(r.getClassId(), k -> new HashSet<>()).add(r.getSubject());
                    scope = DataScope.widest(scope, DataScope.CLASS);
                }
                default -> {
                }
            }
        }
        return new VisibleScope(scope, ci.tenantId(), Set.copyOf(full), Map.copyOf(partial), Set.copyOf(subjects), Set.of());
    }

    /** 当前身份是否是指定类型（便于 Controller 里做简单分流）。 */
    public static boolean is(IdentityType type) {
        return LoginHelper.current().map(ci -> ci.type() == type).orElse(false);
    }
}
