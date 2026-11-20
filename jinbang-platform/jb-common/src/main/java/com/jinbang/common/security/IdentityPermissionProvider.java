package com.jinbang.common.security;

import cn.dev33.satoken.stp.StpInterface;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Sa-Token 权限来源：取「当前身份」的权限码和角色，而不是用户的全部角色。
 * 切换身份后，接口权限立即跟着变（R8：后端二次校验，不能只靠前端隐藏菜单）。
 */
@Component
public class IdentityPermissionProvider implements StpInterface {
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return LoginHelper.current().map(i -> new ArrayList<>(i.perms())).orElseGet(ArrayList::new);
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return LoginHelper.current().map(i -> new ArrayList<>(i.roleKeys())).orElseGet(ArrayList::new);
    }
}
