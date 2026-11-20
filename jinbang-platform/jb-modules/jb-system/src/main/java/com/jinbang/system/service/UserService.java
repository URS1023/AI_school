package com.jinbang.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.common.core.BizException;
import com.jinbang.common.core.Constants;
import com.jinbang.common.core.ErrorCode;
import com.jinbang.system.domain.SysUser;
import com.jinbang.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UserService {
    public static final String ACTIVE = "ACTIVE";
    private static final Pattern STRONG = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)[\\x21-\\x7e]{8,20}$");

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public SysUser get(Long id) {
        return BizException.notNull(userMapper.selectById(id), "用户");
    }

    public Optional<SysUser> findByAccount(String account) {
        if (account == null || account.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getPhone, account).or().eq(SysUser::getUsername, account)));
    }

    public Optional<SysUser> findByPhone(String phone) {
        return Optional.ofNullable(userMapper.selectOne(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getPhone, phone)));
    }

    /** 新建账号。学校导入的账号用初始密码并强制首次登录改密（PRD 2.1 ➕P0）。 */
    public SysUser create(String phone, String username, String nickname, String rawPassword, boolean mustChange) {
        if (phone != null && findByPhone(phone).isPresent()) {
            throw new BizException(ErrorCode.PHONE_EXISTS);
        }
        SysUser u = new SysUser();
        u.setPhone(phone);
        u.setUsername(username);
        u.setNickname(nickname);
        u.setPassword(passwordEncoder.encode(rawPassword));
        u.setMustChangePwd(mustChange);
        u.setUserStatus(ACTIVE);
        u.setLoginFailCount(0);
        userMapper.insert(u);
        return u;
    }

    /** 按手机号找账号，没有就用初始密码新建（学校添加教师、开通家长账号时用）。 */
    public SysUser findOrCreateByPhone(String phone, String nickname) {
        return findByPhone(phone).orElseGet(() -> create(phone, null, nickname, Constants.DEFAULT_PASSWORD, true));
    }

    public boolean matches(SysUser user, String raw) {
        return raw != null && passwordEncoder.matches(raw, user.getPassword());
    }

    public void checkStrength(String raw) {
        if (raw == null || !STRONG.matcher(raw).matches()) {
            throw new BizException(ErrorCode.PASSWORD_WEAK);
        }
    }

    public void setPassword(SysUser user, String raw) {
        checkStrength(raw);
        user.setPassword(passwordEncoder.encode(raw));
        user.setMustChangePwd(false);
        userMapper.updateById(user);
        clearLock(user.getId());
    }

    /** updateById 会跳过 null 字段，清空锁定时间必须显式更新。 */
    public void clearLock(Long userId) {
        userMapper.update(Wrappers.<SysUser>lambdaUpdate().set(SysUser::getLockedUntil, null)
                .set(SysUser::getLoginFailCount, 0).eq(SysUser::getId, userId));
    }

    public void update(SysUser user) {
        userMapper.updateById(user);
    }
}
