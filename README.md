# framework-cache-redis-starter

describeadmin 的**可选插件**：把 `CacheProvider` 与 `TokenStore` 从内存实现切到 Redis。

不引它，框架用内存实现，行为与从前完全一致。引了它，两条内存实现的硬伤消失：

| | 内存实现（框架自带） | 本插件 |
|---|---|---|
| 应用重启 | **全员掉线** | 会话保持 |
| 多实例部署 | **不可用**（令牌只在签发它的那个实例上认识） | 共享会话 |
| 强制下线 | 只踢得掉本实例的会话 | 全实例立即生效 |
| 外部依赖 | 无 | Redis |

框架核心与业务代码**一行都不用改**——这正是"能力可插拔"这条设计要证明的东西。

---

## 兼容性

| 插件版本 | 最低框架版本 | 说明 |
|---|---|---|
| 0.2.0 | **0.2.0** | 用到 0.2.0 引入的 `TokenStore.listActive()` 与 `ActiveSession` |

这张表不是文档承诺，是**可执行的**：

- 插件在启动时自检框架版本，装到更旧的框架上会**启动失败**并给出一句能照着做的提示，
  而不是等到管理员点开"在线用户"那一刻才 `NoClassDefFoundError`
- CI 对表中每个框架版本各跑一遍完整测试（见 `.github/workflows/ci.yml`）

> ⚠️ **0.x 期间请逐版本核对**。SemVer 对 `0.x` 不作任何保证，本项目的 0.2.0 就带过
> Breaking Change。框架比插件构建时更新且主版本相同时，插件只记一条 WARN 而不阻断——
> 那条 WARN 要当回事。

## 接入

```xml
<dependency>
  <groupId>io.github.describeadmin</groupId>
  <artifactId>framework-cache-redis-starter</artifactId>
  <version>0.2.0</version>
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

> ⚠️ 引入本插件即意味着 **Redis 必须真实存在**。Spring Boot 无条件注册
> `StringRedisTemplate`（默认指向 `localhost:6379`）且惰性连接，
> 所以"没配连接信息"不会让插件退让，只会在第一次真正用到时才失败。

## 开发

```bash
# 框架尚未发布 0.2.0 时，先从框架仓装一份到本地仓库
mvn -f ../framework/pom.xml clean install -DskipTests

mvn clean test                              # 需要 Docker：测试用真实 Redis
mvn clean test -Ddescribeadmin.version=0.3.0  # 针对另一个框架版本跑，即 CI 矩阵做的事
```

构建 JDK 由 Maven Toolchains 选定（JDK 21，产物 `release=17`），**与 `PATH` 上是哪个
`java` 无关**。首次配置见 docs 仓的 `scripts/toolchains.xml.sample`。

测试用 Testcontainers 起真实 Redis，不用 mock：本模块的价值全在"与真实 Redis 的语义是否一致"——
Lua 脚本的原子性、`PTTL` 的取值、`SCAN` 的游标行为，恰恰是 mock 掉之后就再也验证不到的东西。

## 相关文档

- 插件准入规范与目录：docs 仓 `registry.md`
- 编码规范：`CLAUDE.md`（组织级母本在 docs 仓，本仓为副本，**不要单独修改**）
- 发布步骤：docs 仓 `RELEASE.md`——发到 Maven Central 的版本不可撤回、不可覆盖

## License

Apache License 2.0
