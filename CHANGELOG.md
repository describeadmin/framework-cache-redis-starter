# 更新日志

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的组织方式，
版本号遵循 [SemVer](https://semver.org/lang/zh-CN/)。

每个版本固定分 **Breaking Changes / New Features / Bug Fixes** 三类
（见组织编码规范第 5 节）。没有内容的类别保留标题并写「无」，
这样使用者不必怀疑是遗漏还是确实没有。

> **本插件的版本线与框架独立**。插件发 1.3.0 完全可能仍然只要求框架 1.0.0，
> 因此每个版本都必须写明适配的框架版本，见下方各条目的「框架要求」。

## 0.2.0-SNAPSHOT (开发中)

describeadmin 的第一个可选插件，也是"能力可插拔"这条设计的第一个真实证据。

**框架要求：0.2.0 及以上**（用到 0.2.0 引入的 `TokenStore.listActive()` 与 `ActiveSession`）

### Breaking Changes

无（首个版本）。

### New Features

- 把 `CacheProvider` 与 `TokenStore` 切换到 Redis，解除内存实现的两条局限：
  **重启掉线**、**不支持多实例**。框架核心与业务代码一行都不用改。
- 不引入本插件时行为与从前完全一致；引入后可用
  `describeadmin.cache.redis.enabled=false` 在运行时退回内存实现——
  排查"是不是 Redis 的问题"不必改 pom 重新打包。
- `describeadmin.cache.redis.key-prefix`（默认 `describeadmin:`）：
  多应用共用一个 Redis 实例时必须区分，否则 A 应用的强制下线会波及 B 应用。
- `describeadmin.cache.redis.token-store-enabled`（默认 `true`）：
  令牌由外部统一认证中心签发的项目可只接管缓存。
- 令牌有效期直接取 `describeadmin.security.token-ttl`，不另立配置项也不自己抄默认值。
- 自增用 Lua 脚本完成"INCRBY + 仅在无存活时间时设置存活时间"：拆成两次调用时，
  两步之间崩溃会留下**永不过期**的计数键，对登录失败计数就意味着账号被永久锁定。
- 在线会话枚举用 `SCAN` 而非 `KEYS`——后者会阻塞整个 Redis 实例，
  在共享 Redis 的部署里一次管理员点击就能拖垮所有业务。
- 值一律以 JSON 存储，不用 JDK 序列化、不开 Jackson 默认类型信息
  （后者是已知的反序列化攻击入口，而缓存内容并不总是可信）。
- 启动期自检框架版本（`FrameworkVersion.requireCompatible`）：装到比
  `REQUIRED_FRAMEWORK_VERSION` 更旧的框架上会**启动失败**并给出可操作的提示，
  而不是等到管理员点开"在线用户"那一刻才 `NoClassDefFoundError`。

### Bug Fixes

无（首个版本）。

### 仓库

- 自 framework 仓拆出，独立成仓、独立版本线、独立发布（原方案 3.1.1 的既定拓扑）。
  同仓时期的两个代价由此消除：框架发版时一行没改的插件也得跟着发；
  `mvn deploy` 会把插件与核心一起推上 Central。
- POM **不继承 `framework-parent`**，改为 `import framework-bom`——
  这正是业务方消费框架的姿势，插件用同一套姿势才能提前暴露业务方会遇到的问题。
- CI 按**框架版本矩阵**跑完整测试。只在一个框架版本上跑绿，
  等于对"支持 0.2.0+"这句话没有任何证据。
