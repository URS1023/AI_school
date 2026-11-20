# jinbang-platform

世纪金榜 AI 教学平台的 Java 后端实现。

依据仓库 `需求文档/` 下的两份文档：

- 《功能逻辑需求文档》v1.2（下称 PRD）
- 《Java 技术路线》

## 1. 架构

按技术路线第 6 章「小团队可先做模块化单体」落地：

- 模块划分与技术路线 1.1 的服务一一对应。
- 模块之间只通过 `jb-api` 里的接口和 Spring 事件通信，不直接引用对方实现。

以后拆成微服务时，有两处替换：

- `jb-api` 的接口改成 Dubbo / Feign；
- Spring 事件改成 RocketMQ。

| 模块 | 对应服务 | 主要内容 |
|---|---|---|
| `jb-common` | 公共底座 | 统一返回与错误码、Sa-Token 身份上下文、多租户（按实体类型自动拼 `tenant_id`）、7 级数据范围、状态机、操作日志、防重复提交、文件存储 |
| `jb-api` | 模块契约 | `AccountApi`、`SchoolApi`、`AiApi`、`QuestionApi`、`ExamDataApi`、`NotifyApi`、`AuditApi` 等接口与领域事件 |
| `jb-system` | jb-auth + jb-system | 登录注册、锁定、首登改密、SSO 一次性 ticket、多身份切换、角色菜单、字典、租户开通；学年、年级、班级、教师（5 种角色）、学生、家长绑定、游客入校、学年升级；导出链接撤销 |
| `jb-account` | jb-account | 金币账户、冻结 → 结算（taskId 幂等）、流水、经验等级、签到、充值订单、人工调账（双人审批）、对账 |
| `jb-resource` | jb-resource | 资源生命周期与审核、付费下载（买断）、收藏、资源篮、评论纠错、资源征集；统一题库、标记式批量录题、试题篮、手动与智能组卷、试卷分析、Word 导出 |
| `jb-teaching` | jb-teaching | 备课目录与资料、版本、回收站、智能备课（大纲可编辑、成长奖励）、AI 教案、集体备课、金榜杏坛、学海共渡 |
| `jb-exam` | jb-exam | 答题卡版本与锁定、作业 / 考试状态机、识别回调入库、异常卷、重复扫描、整批重识别、阅卷、问题卷、作文 AI 批阅、成绩审核、分批发布、撤回与更正通知、学生端和家长端、教辅页面模板库、数据中心 |
| `jb-learning` | jb-learning | 统一学情指标口径、作业 / 考试诊断、个人详情、班级趋势与需关注学生、讲评、大屏、错题本、AI 变式题、AI 学情工具 |
| `jb-ai` | jb-ai | 智能体运行时（冻结 → 执行 → 结算）、模型主备路由、Prompt 版本灰度与回滚、会话、SSE 流式输出、知识库、出题、PPT、作文和试卷批改、AI 中台统计 |
| `jb-operation` | jb-operation | 站内消息中心、统一审核中心（机审 + 人审）、敏感词、反馈工单、举报处理、CMS |
| `jb-app` | 启动装配 | 配置、Flyway 迁移聚合、演示数据、接口级集成测试 |
| `jb-recognize` | 识别服务（Python） | 识别接口契约与编排骨架。OMR / 手写 / OCR 模型尚未接入，见第 6 节 |

## 2. 运行

本地开发需要 JDK 21 和 Maven 3.9，不需要安装任何中间件：

```bash
mvn -DskipTests package
java -jar jb-app/target/jinbang-platform.jar        # dev：H2 内存库 + 演示数据 + 模拟大模型
```

- 接口文档：http://localhost:8080/swagger-ui.html
- 健康检查：http://localhost:8080/actuator/health

### 演示账号（仅 dev）

所有演示账号的密码都是 `Demo1234`。超管账号是 `admin`，密码 `Admin@12345`。

| 账号 | 身份 |
|---|---|
| 13900000001 | 演示中学 · 学校管理员 |
| 13900000002 | 班主任（高一1班）+ 语文任课 |
| 13900000003 | 数学任课（高一1班、2班） |
| 13900000004 | 年级主任（高一） |
| s{学校ID}260101 | 学生张三（学校 ID 见启动日志） |

### 生产部署

生产用 MySQL 8：

```bash
cp .env.example .env   # 填写 MYSQL_ROOT_PASSWORD、JB_RECOGNITION_TOKEN、JB_ADMIN_PASSWORD、大模型配置
docker compose up -d
```

生产环境的安全默认值：

- 图形验证码开启；
- 识别回调 token 为空时拒绝所有回调；
- 支付通道默认关闭（不会启用模拟支付）；
- 超管初始密码首次登录必须修改。

### 测试

```bash
mvn test        # 4 个测试类，30 个接口级集成测试，真实启动全部模块（H2）
```

## 3. 关键设计

### 计费（PRD 11.1、D4）

- **流程：** 先校验输入内容，再冻结预估金额，然后逐个子项执行，执行结果也要过内容审核，最后结算。
- **收费规则：**
  - 只收成功子项的费用；
  - 失败、空结果、被内容审核拦截的不收费；
  - 用户主动停止时，生成不足 10% 的不收费。
- **幂等：** 流水上 `(biz_type, biz_id, direction)` 唯一，重复冻结、重复结算都只生效一次。
- **重试：** 按子项用 CAS 抢占，业务 ID 形如 `taskId#子项#次数`，同一任务 ID 下可以查到完整流水。

### 数据权限（R2、R7、R8）

- **租户隔离：** 由 MyBatis-Plus 插件按「实体是否继承 `TenantEntity`」自动拼接条件，不依赖手写白名单。没有身份时一律查不到数据。
- **数据范围：** 按「当前学年、有效」的任教关系计算：
  - 任课教师：班级 × 学科；
  - 班主任：本班；
  - 年级主任：本年级；
  - 学科组长：本学科；
  - 学校管理员和校领导：全校；
  - 学生：本人；
  - 家长：已绑定的孩子。
- **接口层二次校验：** 看不到的数据直接返回 403，而不是空列表。

### 身份撤销（PRD 8.3）

- **教师离职：** 撤销身份 → 持有该身份的会话立即下线 → 发出事件，让导出链接失效。账号保留，身份退回游客，个人备课资料保留。
- **家长解绑：** 家长身份立即失效。

### 学情口径（PRD 6.6、D3）

所有报表只用 `Metrics` 一套实现：

- 得分率按分值加权；
- 缺考、免考、作废、未阅完、未交的不计入均分和得分率，单独列人数；
- 分档默认用 C 方案 85 / 75 / 60，学校可以自定义；
- 所有比率夹在 0–1 之间，失分不为负。

## 4. PRD 第 17 章验收用例

| 编号 | 内容 | 覆盖测试 |
|---|---|---|
| AC-01 | 作业取当前学年 | `ExamLearningIT.homeworkScanMarkingAndLearning` |
| AC-02 | 重复扫描保留新卷、人工分不被覆盖、可换回 | 同上 |
| AC-03 | 整批重新识别只更新客观题，并提示冲突 | 同上 |
| AC-04 | 答题卡 v1 下载后修改生成 v2，旧纸卷按 v1 判分 | 同上 |
| AC-05 | 发布后改分：重算 + 通知学生和家长（原分 → 新分） | `ExamLearningIT.examAuditPublishCorrectionAndWithdraw` |
| AC-06 | 撤回后学生端和家长端立即看不到，补发撤回通知 | 同上 |
| AC-07 | 平均得分率 = 实得总分 ÷ 应得满分 | `homeworkScanMarkingAndLearning` |
| AC-08 | 缺考不计入均分，单独计数 | `examAuditPublishCorrectionAndWithdraw` |
| AC-09 | AI 失败不扣费，提示不暴露内部信息 | `AiResourceIT.failedAskIsFree` |
| AC-10 | 批量批改部分成功，只收成功部分 | `AiResourceIT.essayBatchPartialAndIdempotentRetry` |
| AC-11 | 连续两次重试最多扣一次 | 同上 |
| AC-12 | 用任务 ID 对账，个人与运营金额一致 | `AiResourceIT.successfulAskChargesActualAndReconciles` |
| AC-13 | 游客直接调用学校接口返回 403 | `SystemAccountIT.visitorGetsForbiddenOnSchoolApis` |
| AC-14 | 离职后看不到学校数据，导出链接失效 | `SystemAccountIT.resignRevokesAccessAndExportLinks` |
| AC-15 | 家长解绑后立即失效 | `SystemAccountIT.parentBindAndUnbind` |
| AC-16 | 无权限时统一 403 并给出申请指引 | `visitorGetsForbiddenOnSchoolApis` |
| AC-17 | 占位入口带「即将上线」标记 | 同上（菜单里的 `comingSoon`） |
| AC-18 | 得分超出满分时拒绝或标为待复核；比率 ≤ 100%、失分 ≥ 0 | `homeworkScanMarkingAndLearning` |
| AC-19 | 异常卷分配给学生后人数重算 | 同上 |
| AC-20 | 不改 Chrome flag 也能扫描 | **后端无法覆盖**：属于扫描客户端（见第 6 节） |

## 5. PRD 主要 P0 需求的落地位置

| PRD | 需求 | 接口 / 实现 |
|---|---|---|
| 2.1 | 忘记密码、首登强制改密、登录失败锁定、ticket 一次性且 60 秒失效、账号注销 | `/api/auth/**` |
| 2.2 / R9 | 统一 403 提示与申请指引；占位入口灰显 | `GlobalExceptionHandler`、`/api/system/menu/routers` |
| 2.4 | 金币明细、计费公示、失败自动退回 | `/api/account/coins/ledger`、`/api/open/ai/agents`、`AgentRuntime` |
| 3.3 / 7.3 / 15 | AI 出题一键入题库，统一题目模型 | `/api/ai/questions/save`、`q_question` |
| 3.6 | 我的投稿（审核状态、驳回原因） | `/api/collection/mine` |
| 4.1.2 | 智能备课大纲可编辑；AI 教案（Word） | `/api/prep/smart/{id}/outline`、`/api/prep/lesson-plan` |
| 4.3 | 集体备课入口 | `/api/collective/**` |
| 4.4 | 社区发布前内容审核 | `CommunityService` |
| 6.2 | 重复扫描与重识别规则；教辅页面模板库 | `ScanService`、`/api/admin/workbook/pages` |
| 6.3 | 答题卡版本与锁定 | `SheetService` |
| 6.4 | AI 主观题（作文）评分 | `/api/exam/{type}/{id}/marking/ai` |
| 6.6 | 指标口径；AI 学情报告 | `Metrics`、`LearningAiTool` |
| 6.7 | AI 变式题 | `/api/learning/wrong/{id}/variants` |
| 6.8 | 学年升级、导入校验 | `/api/school/years/upgrade`、`/api/school/*/import` |
| 7.1 | 教师端消费记录 | `/api/ai/tasks` |
| 7.4 | 生成的课件同步进独立备课 | `CoursewareGeneratedEvent` → `PrepService` |
| 7.6 | 作文批改按学生建档 | `ai_essay_record`、`/api/ai/essay/history` |
| 8.3 | 身份交接与撤权 | `IdentityService.revoke`、`ExportLinkService` |
| 10.2 | 审核中心、AI 中台（Prompt 灰度和回滚、模型路由、调用日志、统计）、财务调账 | `/api/audit/**`、`/api/admin/ai/**`、`/api/admin/coin-*` |
| 11.5 | 三类状态机及其异常处理 | `FlowService` |
| 11.8–11.10 | 学年升级、游客入校、家长绑定 | `SchoolController` |

## 6. 尚未完成的部分

以下内容需要外部资源、产品决策或单独立项，没有在这次实现里假装完成。

1. **前端：** 教师 Web、AI 平台、学生 H5、家长小程序、管理后台都没有做。仓库里只有后端接口和 Swagger 文档。
2. **识别模型：** `jb-recognize` 只有接口契约和编排骨架。四类识别能力都要另行接入：
   - 定位点和二维码；
   - 手写学号；
   - 手写选项；
   - 红笔分数与 OCR。

   没有接入时，识别结果为空，卷子会进异常卷，不会误判分。
3. **AC-20 扫描环境：** 需要改造扫描客户端（本地服务改 HTTPS 并返回 Private Network Access 响应头，或改为桌面客户端），后端做不了。
4. **第三方真实对接：**
   - 微信支付：需要实现 `PaymentGateway`，生产默认关闭；
   - 短信：需要实现 `SmsSender`，开发环境只写日志；
   - WPS 在线编辑、学科网 OAuth、云内容安全、OSS：目前分别用本地存储、本地敏感词库等替代。
5. **中间件：**
   - Redis：SSO ticket、验证码、防重复提交现在是单机 Caffeine 实现。多实例部署前要换成 Redis，接口不变。
   - 其余中间件（RocketMQ、Elasticsearch、Doris、向量库）都还没接入，现在分别用 Spring 事件、数据库 LIKE 和字词重叠检索替代。
6. **以下功能按 PRD 优先级（P1 / P2）或待决策事项暂未实现：**
   - 资源店铺与提现（D7 待定）；
   - 区域联考与驾驶舱；
   - 智能体广场；
   - 学校套餐与 AI 配额；
   - 推荐系统、BI；
   - 互动授课客户端；
   - 实物兑换物流；
   - 发票、退款流程（订单状态机已预留）。
7. **PRD 第 16 章待决策项：** D1–D4 按文档里的建议方案实现，如果产品负责人改了决定，需要相应调整：
   - D1：学年取学校当前学年；
   - D2：成绩发布需要审核，可以分批发布，排名默认不展示；
   - D3：分档用 C 方案；
   - D4：失败一律自动退回，人工调账需要审批。

## 7. 权限码

角色和权限码见 `jb-system/src/main/resources/db/migration/V1_03__system_seed.sql`。常用权限码：

- **学校侧：**
  - `homework:*`、`exam:*`；
  - `learning:view`、`wrong:view`；
  - `school:*`（教务管理）；
  - `collective:create`；
  - `forum:manage`。
- **平台侧：**
  - `op:tenant`、`op:resource`、`op:audit`、`op:ai`、`op:finance`、`op:feedback`、`op:cms`、`op:dict`。
- **学生和家长：** `student:self`、`parent:self`。
