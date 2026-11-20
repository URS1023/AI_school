package com.jinbang.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jinbang.common.security.CurrentIdentity;
import com.jinbang.system.domain.SysDictData;
import com.jinbang.system.domain.SysMenu;
import com.jinbang.system.mapper.SysDictDataMapper;
import com.jinbang.system.mapper.SysMenuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 菜单下发（R1）与字典（PRD 2.3，带缓存，后台维护后清缓存）。 */
@Service
@RequiredArgsConstructor
public class MenuDictService {
    private final SysMenuMapper menuMapper;
    private final SysDictDataMapper dictMapper;

    public record MenuVO(Long id, String name, String path, String status, boolean comingSoon, List<MenuVO> children) {
    }

    /** 按当前身份的权限码过滤；「即将上线」的入口照常下发，但标记 comingSoon，前端灰显（AC-17）。 */
    public List<MenuVO> routers(CurrentIdentity ci) {
        List<SysMenu> all = menuMapper.selectList(Wrappers.<SysMenu>lambdaQuery().orderByAsc(SysMenu::getSortNo));
        List<SysMenu> visible = all.stream().filter(m -> allowed(ci, m.getPermRequired())).toList();
        Map<Long, List<SysMenu>> byParent = visible.stream().collect(Collectors.groupingBy(SysMenu::getParentId));
        return build(0L, byParent);
    }

    private List<MenuVO> build(Long parent, Map<Long, List<SysMenu>> byParent) {
        List<MenuVO> out = new ArrayList<>();
        for (SysMenu m : byParent.getOrDefault(parent, List.of())) {
            List<MenuVO> children = build(m.getId(), byParent);
            // 目录型菜单没有任何可见子菜单时不下发（例如游客看不到「教务管理」）
            if ("M".equals(m.getMenuType()) && children.isEmpty()) {
                continue;
            }
            out.add(new MenuVO(m.getId(), m.getMenuName(), m.getPath(), m.getMenuStatus(),
                    "COMING_SOON".equals(m.getMenuStatus()), children));
        }
        return out;
    }

    static boolean allowed(CurrentIdentity ci, String perm) {
        if (perm == null || perm.isBlank()) {
            return true;
        }
        if (ci.perms().contains("*")) {
            return true;
        }
        if (perm.endsWith(":")) {
            return ci.perms().stream().anyMatch(p -> p.startsWith(perm));
        }
        return ci.perms().contains(perm);
    }

    @Cacheable(cacheNames = "dict", key = "#dictType")
    public List<SysDictData> dict(String dictType) {
        return dictMapper.selectList(Wrappers.<SysDictData>lambdaQuery().eq(SysDictData::getDictType, dictType)
                .eq(SysDictData::getDictStatus, "NORMAL").orderByAsc(SysDictData::getSortNo));
    }

    @CacheEvict(cacheNames = "dict", key = "#data.dictType")
    public SysDictData saveDict(SysDictData data) {
        if (data.getDictStatus() == null) {
            data.setDictStatus("NORMAL");
        }
        if (data.getId() == null) {
            dictMapper.insert(data);
        } else {
            dictMapper.updateById(data);
        }
        return data;
    }
}
