# framework-cache-redis-starter

describeadmin 的**可选插件**：把 `CacheProvider`、`TokenStore` 与 `LockOperations`
从内存实现切到 Redis。

不引它，框架用内存实现，行为与从前完全一致。引了它，三条内存实现的硬伤消失：

| | 内存实现（框架自带） | 本插件 |
|---|---|---|
| 应用重启 | **全员掉线** | 会话保持 |
| 多实例部署 | **不可用**（令牌只在签发它的那个实例上认识） | 共享会话 |
| 强制下线 | 只踢得掉本实例的会话 | 全实例立即生效 |
| 分布式锁 | **不可用**（`@DistributedLock`/`UniqueGuard` 只在单 JVM 内互斥） | 跨实例互斥 |
| 外部依赖 | 无 | Redis |

框架核心与业务代码**一行都不用改**——这正是"能力可插拔"这条设计要证明的东西。

---

## 兼容性

| 插件版本 | 最低框架版本 | 说明 |
|---|---|---|
| 0.2.0 | **0.2.0** | 用到 0.2.0 引入的 `TokenStore.listActive()` 与 `ActiveSession` |
| 1.0.0 | **1.0.0** | 第一个正式版。新增 `RedisLockOperations`（`LockOperations` 的 Redis 实现，锁 SPI 随框架 1.0.0 引入）；含原 0.2.1 的越权修复（0.2.1 未发布） |

这张表不是文档承诺，是**可执行的**：

- 插件在启动时自检框架版本，装到更旧的框架上会**启动失败**并给出一句能照着做的提示，
  而不是等到管理员点开"在线用户"那一刻才 `NoClassDefFoundError`
- CI 对表中每个框架版本各跑一遍完整测试（见 `.github/workflows/ci.yml`）

> ⚠️ **0.x 与 1.x 不互通**。1.0.0 是第一个正式版，0.x 按测试版对待、不做兼容；
> 框架与插件主版本不同时，插件在启动时直接拒绝（`FrameworkVersion.requireCompatible`）。
> 1.x 内框架比插件构建时更新时只记一条 WARN、不阻断。

## 接入

```xml
<dependency>
  <groupId>io.github.describeadmin</groupId>
  <artifactId>framework-cache-redis-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

版本号需要显式写。**框架的 `framework-bom` 不仲裁插件版本**——插件有自己的版本线，
让 BOM 按框架版本去解析插件，会解析到一个根本不存在的制品。

Redis 连接信息用 Spring Boot 的标准配置项，插件不另立一套：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379

describeadmin:
  cache:
    redis:
      enabled: true              # 运行时开关，关掉后完全等同于没引这个 jar
      key-prefix: describeadmin: # 多个应用共用一个 Redis 时必须区分
      token-store-enabled: true  # 只想换缓存、令牌另有来源时置 false
```

令牌有效期取 `describeadmin.security.token-ttl`，插件不另立配置项——
两处配置各说各话时，"改了没生效"是最难查的一类问题。

锁键前缀同理取框架的 `describeadmin.lock.key-prefix`（默认 `describeadmin:lock:`），
不用本插件的 `key-prefix`：内存/Redis 两个锁实现必须在同一配置下产出完全相同的
键名，替换实现才不改变锁语义。多应用共用一个 Redis 时用锁前缀区分彼此。

> ⚠️ 引入本插件即意味着 **Redis 必须真实存在**。Spring Boot 无条件注册
> `StringRedisTemplate`（默认指向 `localhost:6379`）且惰性连接，
> 所以"没配连接信息"不会让插件退让，只会在第一次真正用到时才失败。
> 锁的操作在存储故障时 fail-close（直接报错拒绝执行，而不是放行失去并发保护），
> Redis 整体不可用时业务方的加锁请求会失败——这是刻意的。

## 开发

```bash
# 框架尚未发布到 Central 的版本，先从框架仓装一份到本地仓库
mvn -f ../framework/pom.xml clean install -DskipTests

mvn clean test                                # 需要 Docker：测试用真实 Redis
mvn clean test -Ddescribeadmin.version=1.0.0  # 指定框架版本跑，即 CI 矩阵做的事
```

构建**不要求特定的 JDK 版本**，唯一前提是 **JDK >= 17**（由 enforcer 的 `requireJavaVersion`
把关）。17 / 21 / 25 都可以，产物一律是 Java 17 字节码（`maven.compiler.release=17`）。

用 `mvn -v` 看 Maven 实际使用的 JDK，**不要用 `java -version`**——后者看的是 `PATH`，
与 Maven 用哪个 JDK 无关。

测试用 Testcontainers 起真实 Redis，不用 mock：本模块的价值全在"与真实 Redis 的语义是否一致"——
Lua 脚本的原子性、`PTTL` 的取值、`SCAN` 的游标行为，恰恰是 mock 掉之后就再也验证不到的东西。

## 相关文档

- 插件准入规范与目录：docs 仓 `registry.md`
- 编码规范：docs 仓 `CLAUDE.md`（各子仓不再放副本，开发时把本仓与 `docs` 仓并列检出）
- 发布步骤：docs 仓 `RELEASE.md`——发到 Maven Central 的版本不可撤回、不可覆盖

## License

Apache License 2.0
