# framework-cache-redis-starter

describeadmin 的可选插件。把框架的缓存、令牌存储、锁从内存实现切到 Redis：应用重启不掉线，支持多实例部署和跨实例的分布式锁。

**文档：[https://describeadmin.github.io/docs/how-to/cache-redis](https://describeadmin.github.io/docs/how-to/cache-redis)**，全部配置项见 [插件配置](https://describeadmin.github.io/docs/reference/configuration/plugins)。

## 引入

```xml
<dependency>
  <groupId>io.github.describeadmin</groupId>
  <artifactId>framework-cache-redis-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

版本号要显式写：`framework-bom` 不管插件的版本，插件有自己的版本线。

Redis 连接用 Spring Boot 的标准配置：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379

describeadmin:
  cache:
    redis:
      key-prefix: "myapp:"         # 缓存与令牌的键前缀
  lock:
    key-prefix: "myapp:lock:"      # 锁的键前缀。多个应用共用一个 Redis 时两个前缀都必须区分
```

引入后 Redis 必须真实可用：连接是惰性的，没配好不会在启动时报错，而是在第一次用到时失败。

运行时开关是 `describeadmin.cache.redis.enabled`（默认 `true`），关掉后与没有引入这个 jar 完全一样。

## 兼容性

| 插件版本 | 最低框架版本 |
|---|---|
| 1.0.0 | 1.0.0 |

插件启动时检查框架版本：框架比上表要求的旧、或主版本不同（0.x 与 1.x 不互通），应用直接拒绝启动并说明原因。
CI 对表里的每个框架版本各跑一遍完整测试。版本对应关系的总表见 [插件目录](https://describeadmin.github.io/docs/reference/plugins)。

## 开发

```bash
mvn clean test                                  # 需要 Docker：测试用 Testcontainers 起真实 Redis
mvn clean test -Ddescribeadmin.version=1.0.0    # 针对指定框架版本跑，即 CI 矩阵做的事
```

测试不用 mock：Lua 脚本的原子性、`PTTL`、`SCAN` 游标这些 Redis 语义，mock 掉就验证不到了。

构建只要求 JDK ≥ 17（enforcer 把关，产物固定为 Java 17 字节码）；用 `mvn -v` 看 Maven 实际用的 JDK。
框架有尚未发布到 Central 的版本时，先 `mvn -f ../framework/pom.xml clean install -DskipTests -Dgpg.skip=true` 装到本地仓库。

本插件不继承 `framework-parent`，而是 import `framework-bom`，与业务方的用法相同。插件开发规范见
[编写插件](https://describeadmin.github.io/docs/dev/plugin-dev)，发布照 [发布手册](https://describeadmin.github.io/docs/dev/release) 执行。

## 变更记录与许可证

[CHANGELOG.md](./CHANGELOG.md) · [Apache-2.0](./LICENSE)
