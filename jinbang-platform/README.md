# jinbang-platform

世纪金榜 AI 教学平台的后端实现，依据仓库里 `需求文档/` 下的两份文档：

- 《功能逻辑需求文档》v1.2（下称 PRD）
- 《Java 技术路线》

## 架构形态

按技术路线第 6 章「小团队可先做模块化单体」落地：

- 模块划分和技术路线 1.1 的服务划分一一对应。
- 模块之间只通过 `jb-api` 里的接口和事件通信，不直接引用对方的实现类。

以后拆微服务时，有两处要替换：

- `jb-api` 的接口改成 Dubbo / Feign；
- Spring 事件改成 RocketMQ。

```
jinbang-platform/
├── jb-common      统一返回与错误码、身份上下文、多租户、数据权限、状态机、操作日志、防重复提交
├── jb-api         模块间契约（接口、DTO、领域事件）
├── jb-modules/    业务模块
└── jb-app         启动装配、配置、Flyway 迁移
```

## 运行

需要 JDK 21 和 Maven 3.9。

```bash
mvn -DskipTests package
java -jar jb-app/target/jinbang-platform.jar        # 默认 dev：H2 内存库，不依赖任何中间件
JB_PROFILE=prod JB_DB_USER=... JB_DB_PASSWORD=... java -jar ...   # 生产：MySQL 8
```

- 接口文档：http://localhost:8080/swagger-ui.html
- 健康检查：http://localhost:8080/actuator/health
