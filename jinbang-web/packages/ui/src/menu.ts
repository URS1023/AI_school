import type { MenuItem } from '@jb/auth'

export function flattenMenus(menus: MenuItem[]): MenuItem[] {
  const out: MenuItem[] = []
  const walk = (list: MenuItem[]) =>
    list.forEach((m) => {
      out.push(m)
      if (m.children?.length) walk(m.children)
    })
  walk(menus)
  return out
}

/** 按路径找菜单：精确匹配优先，其次是最长前缀（详情页等子路径归属到所在菜单）。 */
export function findMenu(menus: MenuItem[], path: string): MenuItem | undefined {
  const all = flattenMenus(menus).filter((m) => m.path)
  return (
    all.find((m) => m.path === path) ??
    all.filter((m) => path.startsWith(`${m.path}/`)).sort((a, b) => (b.path?.length ?? 0) - (a.path?.length ?? 0))[0]
  )
}

/** 当前身份能否进入这个路径：菜单里有（且不是「即将上线」）才行。用于接住菜单以外的直达链接（R8 前端兜底）。 */
export function canEnter(menus: MenuItem[], path: string): boolean {
  const m = findMenu(menus, path)
  return !!m && !m.comingSoon
}
