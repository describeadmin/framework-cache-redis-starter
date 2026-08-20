package io.github.describeadmin.cache.redis.autoconfigure;

import io.github.describeadmin.cache.api.CacheProvider;
import io.github.describeadmin.cache.autoconfigure.FrameworkCacheAutoConfiguration;
import io.github.describeadmin.cache.core.InMemoryCacheProvider;
import io.github.describeadmin.cache.redis.AbstractRedisTest;
import io.github.describeadmin.cache.redis.core.RedisCacheProvider;
import io.github.describeadmin.cache.redis.core.RedisTokenStore;
import io.github.describeadmin.common.api.FrameworkVersion;
import io.github.describeadmin.security.api.TokenStore;
import io.github.describeadmin.security.autoconfigure.FrameworkSecurityAutoConfiguration;
import io.github.describeadmin.security.core.InMemoryTokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 插件装配机制的验证——本模块最重要的一组测试。
 *
 * <p>它要回答的不是"Redis 实现写得对不对"（那由另外两个测试类负责），而是
 * <b>"把这个 jar 放进 classpath，它到底有没有接管"</b>。
 *
 * <p>这个问题值得单独测，是因为它的失败方式极其隐蔽：框架核心用
 * {@code @ConditionalOnMissingBean} 提供内存兜底，而该条件只看当前已注册的 Bean 定义。
 * 插件若晚于核心被评估，插件的 Bean 会被自己的条件挡掉——<b>启动完全正常、日志毫无异常</b>，
 * 直到某次重启后发现用户还是全掉线了，才会有人怀疑到装配顺序。
 * VERSION_BASELINE.md 发现 ⑦ 记录过同一类问题，当时是在 core 内部两个 starter 之间发生的。
 *
 * <p>{@link AutoConfigurations} 会按 {@code @AutoConfiguration} 的 before/after 真实排序，
 * 因此本测试验证的确实是那几个注解，而不只是 Bean 方法本身。
 */
@DisplayName("插件装配")
class FrameworkCacheRedisAutoConfigurationTest extends AbstractRedisTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RedisAutoConfiguration.class,
                    FrameworkCacheRedisAutoConfiguration.class,
                    FrameworkCacheAutoConfiguration.class,
                    FrameworkSecurityAutoConfiguration.class))
            .withPropertyValues(
                    "spring.data.redis.host=" + redisHost(),
                    "spring.data.redis.port=" + redisPort());

    @Test
    @DisplayName("插件声明的最低框架版本，与它实际构建所依赖的框架是自洽的")
    void declaredRequirementIsSatisfiedByTheFrameworkItBuildsAgainst() {
        // 守住一种很容易发生的失误：有人给 REQUIRED_FRAMEWORK_VERSION 填了个还没发布的版本，
        // 于是插件一装上去就启动失败——而这种错误在插件自己的构建里本来是看不出来的
        assertThat(FrameworkVersion.current()).isNotEqualTo(FrameworkVersion.UNKNOWN);
        assertThatNoException().isThrownBy(() -> FrameworkVersion.requireCompatible(
                "framework-cache-redis-starter",
                FrameworkCacheRedisAutoConfiguration.REQUIRED_FRAMEWORK_VERSION));
    }

    @Test
    @DisplayName("框架比插件要求的旧时，启动即失败而不是等到运行期报 NoSuchMethodError")
    void incompatibleFrameworkFailsFast() {
        // 本插件用到了 0.2.0 才有的 TokenStore.listActive() 与 ActiveSession。
        // 装到 0.1.x 上时，真实症状是管理员点开"在线用户"那一刻 NoClassDefFoundError
        assertThatThrownBy(() -> FrameworkVersion.requireCompatible(
                "framework-cache-redis-starter", "99.0.0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("framework-cache-redis-starter");
    }

    @Test
    @DisplayName("插件在 classpath 上时接管 CacheProvider，核心的内存实现让位")
    void pluginTakesOverCacheProvider() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CacheProvider.class);
            assertThat(context.getBean(CacheProvider.class))
                    .as("装配顺序不对时这里会是 InMemoryCacheProvider，且不会有任何报错")
                    .isInstanceOf(RedisCacheProvider.class);
        });
    }

    @Test
    @DisplayName("插件在 classpath 上时接管 TokenStore，重启不掉线由此成立")
    void pluginTakesOverTokenStore() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(TokenStore.class);
            assertThat(context.getBean(TokenStore.class)).isInstanceOf(RedisTokenStore.class);
        });
    }

    @Test
    @DisplayName("运行时开关关掉后完全退回内存实现，等同于没引这个 jar")
    void disabledFallsBackToCore() {
        runner.withPropertyValues("describeadmin.cache.redis.enabled=false").run(context -> {
            // 两层开关里的运行时那层：排查"是不是 Redis 的问题"时不必改 pom 重新打包
            assertThat(context.getBean(CacheProvider.class)).isInstanceOf(InMemoryCacheProvider.class);
            assertThat(context.getBean(TokenStore.class)).isInstanceOf(InMemoryTokenStore.class);
        });
    }

    @Test
    @DisplayName("可以只接管缓存而把令牌留给别人")
    void tokenStoreCanBeOptedOut() {
        runner.withPropertyValues("describeadmin.cache.redis.token-store-enabled=false")
                .run(context -> {
                    assertThat(context.getBean(CacheProvider.class))
                            .isInstanceOf(RedisCacheProvider.class);
                    // 令牌由外部统一认证中心签发的项目会用到这条
                    assertThat(context.getBean(TokenStore.class))
                            .isInstanceOf(InMemoryTokenStore.class);
                });
    }

    @Test
    @DisplayName("业务方自定义的实现优先于插件——插件不能抢业务方的位置")
    void businessBeanWins() {
        runner.withBean(CacheProvider.class, () -> new InMemoryCacheProvider(10)).run(context -> {
            assertThat(context.getBean(CacheProvider.class))
                    .as("业务方显式注册的 Bean 必须赢过插件，否则无法覆盖框架行为")
                    .isInstanceOf(InMemoryCacheProvider.class);
        });
    }

    @Test
    @DisplayName("令牌有效期取自 security 的配置项，插件不另立一套")
    void tokenTtlComesFromSecurityProperties() {
        runner.withPropertyValues("describeadmin.security.token-ttl=2h").run(context -> {
            TokenStore store = context.getBean(TokenStore.class);
            assertThat(store).isInstanceOf(RedisTokenStore.class);
            // 两处配置各说各话时，"改了没生效"是最难查的一类问题。
            // 这里通过实际签发一次来核对有效期确实落到了 Redis 上
            String token = store.issue(new io.github.describeadmin.security.api.LoginUser(
                    1L, "ttl-check", "有效期核对", "password", java.util.Set.of(), java.util.Set.of()));
            assertThat(store.resolve(token)).isNotEmpty();
            assertThat(store.listActive().get(0).getExpiresAt())
                    .isAfter(store.listActive().get(0).getIssuedAt().plusSeconds(3600))
                    .isBefore(store.listActive().get(0).getIssuedAt().plusSeconds(7300));
        });
    }
}
