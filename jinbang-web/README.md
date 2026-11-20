# jinbang-web

世纪金榜 AI 教学平台前端（pnpm monorepo）。方案见 `../需求文档/世纪金榜AI平台-前端方案.md`。

## 当前进度：F0 底座（已完成）

| 部分 | 内容 |
|---|---|
| `packages/api` | 从后端 OpenAPI 自动生成 TypeScript 类型（`schema.d.ts`）。`unwrap()` 统一解包 `R<T>`，并处理 401、403、1004（强制改密）。ID 一律是 `string`，避免雪花 ID 丢精度 |
| `packages/auth` | 会话 store：登录（密码 / 短信 / 注册 / SSO ticket）、身份切换、改密；路由守卫（未登录、强制改密、`meta.perm`）；`v-perm` 按钮指令 |
| `packages/ui` | 设计令牌（品牌色、暗色、教室大字号模式）；登录页、改密页、403 页（带申请指引）；通用布局（按身份下发的菜单、「即将上线」角标、消息铃铛、身份切换）；建设中占位页；菜单守卫（不在当前身份菜单里的地址一律 403） |
| `apps/teacher-web` | 教师 Web（主站与 AI 工作台合并）：首页（金币、等级、签到、快捷入口）、消息中心、SSO 落地页；其余菜单暂时显示占位页 |
| `apps/admin-web` | 管理后台：学校后台和运营后台两套菜单；首页按权限显示学校概况和待办 |
| `e2e` | Playwright 端到端测试，共 9 条：AC-13、AC-16、AC-17、首登改密、SSO 一次性 ticket、身份切换、管理后台权限 |

后续阶段按方案第 6 章排期：

- F1：资源、题库、备课、AI 工作台；
- F2：考试阅卷与学情；
- F3：管理后台；
- F4：uni-app 学生端和家长端；
- F5：大屏与 Tauri 扫描客户端。

## 开发

需要 Node 20+ 和 pnpm 11。

先启动后端（dev 模式自带演示数据，见 `../jinbang-platform/README.md`）：

```bash
cd ../jinbang-platform && mvn -DskipTests package && java -jar jb-app/target/jinbang-platform.jar
```

然后启动前端：

```bash
pnpm install
pnpm dev:teacher      # http://localhost:5173  教师 Web
pnpm dev:admin        # http://localhost:5174  管理后台
```

演示账号的密码都是 `Demo1234`：

| 账号 | 身份 |
|---|---|
| 13900000001 | 学校管理员 |
| 13900000002 | 班主任 |
| 13900000003 | 数学老师 |
| 13900000009 | 游客教师 |

## 常用命令

```bash
pnpm gen:api          # 后端接口变更后：拉取 /v3/api-docs 并重新生成类型
pnpm typecheck        # 全部包做类型检查
pnpm build            # 构建两个应用
pnpm e2e              # 端到端测试（需要后端在 8080 运行；默认用本机 Chrome，可用 JB_E2E_CHANNEL=msedge 改成 Edge）
```

## 约定

- 接口只通过 `http`（openapi-fetch）+ `unwrap` 调用，不手写 URL 字符串以外的类型。
- 权限的唯一来源是后端：
  - 页面用 `meta.perm` 或 `meta.menu`；
  - 按钮用 `v-perm`；
  - 前端只负责隐藏，真正的拦截在后端。
- 学情口径、计费金额一律以后端返回为准，前端不做二次计算。
