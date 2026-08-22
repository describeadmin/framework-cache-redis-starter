package io.github.describeadmin.cache.redis.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.describeadmin.cache.api.CacheProvider;
import io.github.describeadmin.cache.autoconfigure.FrameworkCacheAutoConfiguration;
import io.github.describeadmin.cache.redis.core.RedisCacheProvider;
import io.github.describeadmin.cache.redis.core.RedisTokenStore;
import io.github.describeadmin.common.api.FrameworkVersion;
import io.github.describeadmin.security.api.TokenStore;
import io.github.describeadmin.security.autoconfigure.FrameworkSecurityProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;


/**
 * framework-cache-redis-starter 的自动配置。
 *
 * <p><b>顺序是本类最容易出错的地方，也是它必须写清楚的地方。</b>
 * 框架核心用 {@code @ConditionalOnMissingBean} 提供内存兜底，而该条件
 * <b>只检查当前已注册的 Bean 定义</b>：本插件若晚于核心被评估，核心的内存实现已经注册，
 * 插件的 Bean 就会被自己的 {@code @ConditionalOnMissingBean} 挡掉——
 * 结果是<b>引了插件却完全没生效，且启动毫无异常</b>，直到重启后发现用户还是全掉线了
 * 才会有人怀疑到这里。VERSION_BASELINE.md 发现 ⑦ 记录过同一类问题。
 *
 * <p>因此本类声明 {@code before}：
 * <ul>
 *   <li>{@link FrameworkCacheAutoConfiguration} —— 直接引用类，它是本插件的编译期依赖，必然存在</li>
 *   <li>framework-security-starter 的自动配置 —— 用 {@code beforeName} 写<b>字符串</b>，
 *       因为该模块是 optional 的，只用缓存不用鉴权的业务方 classpath 上没有这个类，
 *       写成 {@code before = Xxx.class} 会在类加载时直接失败</li>
 * </ul>
 *
 * <p>另需排在 {@link RedisAutoConfiguration} 之后，否则拿不到 {@code StringRedisTemplate}。
 */
@AutoConfiguration(
        after = RedisAutoConfiguration.class,
        before = FrameworkCacheAutoConfiguration.class,
        beforeName = "io.github.describeadmin.security.autoconfigure.FrameworkSecurityAutoConfiguration")
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnProperty(prefix = "describeadmin.cache.redis", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FrameworkCacheRedisProperties.class)
public class FrameworkCacheRedisAutoConfiguration {

    /**
     * 本插件要求的最低框架版本。
     *
     * <p><b>手工声明而不是从插件自身版本推导</b>：插件独立成仓之后，
     * 插件版本与框架版本不再有对应关系（插件发 1.3.0 可能仍然只要求框架 1.0.0）。
     * 让它成为一个需要有意识修改的常量，比让它跟着别的东西自动变要安全。
     *
     * <p>用到框架新增的 SPI 时必须同步上调，否则装到旧框架上会在<b>运行期</b>
     * 抛 NoSuchMethodError，而不是在启动时被挡住。本插件用到了 0.2.0 引入的
     * {@code TokenStore.listActive()}、{@code ActiveSession}，以及同样在 0.2.0 内新增的
     * {@code TokenStore.issueWithRefresh()/refresh()/revokeRefreshToken()}（access/refresh
     * 双令牌）与 {@code CacheProvider.keysWithPrefix()}（登录锁定可观测性）。
     */
    public static final String REQUIRED_FRAMEWORK_VERSION = "0.2.0";

    public FrameworkCacheRedisAutoConfiguration() {
        // 放在构造函数里：条件都满足、真的要装配本插件时才检查。
        // 若插件根本没被激活（enabled=false），不该因为版本不匹配把应用打死
        FrameworkVersion.requireCompatible("framework-cache-redis-starter", REQUIRED_FRAMEWORK_VERSION);
    }

    /**
     * 用 Redis 承载 {@link CacheProvider}。
     *
     * <p>{@code @ConditionalOnBean(StringRedisTemplate.class)} 覆盖的是业务方排除了
     * {@link RedisAutoConfiguration} 的情况：此时安静地不接管、让核心的内存实现兜底，
     * 比在启动阶段抛一个"找不到 StringRedisTemplate"要好。
     *
     * <p><b>注意它不能替你判断 Redis 是否真的可用</b>：Spring Boot 无条件注册
     * {@code StringRedisTemplate}（默认指向 localhost:6379），并不会因为"没配连接信息"
     * 而缺席，连接也是惰性建立的。也就是说<b>引入本插件即意味着必须真的有 Redis</b>，
     * 否则失败会推迟到第一次读写才暴露。临时没有 Redis 时请显式
     * {@code describeadmin.cache.redis.enabled=false}。
     */
    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean(CacheProvider.class)
    public CacheProvider redisCacheProvider(StringRedisTemplate redisTemplate,
                                            ObjectProvider<ObjectMapper> objectMapper,
                                            FrameworkCacheRedisProperties properties) {
        return new RedisCacheProvider(redisTemplate,
                objectMapper.getIfAvailable(ObjectMapper::new),
                properties.getKeyPrefix());
    }

    /**
     * 用 Redis 承载 {@link TokenStore}。
     *
     * <p>单独放进嵌套类并把 {@code @ConditionalOnClass} 加在类上：
     * framework-security-starter 是 optional 依赖，{@code TokenStore} 可能根本不在 classpath 上。
     * 条件标在配置类上时，Spring 用 ASM 读取注解、<b>不会加载</b>类里方法签名引用的类型；
     * 若把 Bean 方法直接写在外层，方法返回值的类型解析会先一步触发 NoClassDefFoundError。
     */
    @AutoConfiguration
    @ConditionalOnClass(TokenStore.class)
    @ConditionalOnProperty(prefix = "describeadmin.cache.redis", name = "token-store-enabled",
            havingValue = "true", matchIfMissing = true)
    public static class RedisTokenStoreConfiguration {

        @Bean
        @ConditionalOnBean(StringRedisTemplate.class)
        @ConditionalOnMissingBean(TokenStore.class)
        public TokenStore redisTokenStore(StringRedisTemplate redisTemplate,
                                          ObjectProvider<ObjectMapper> objectMapper,
                                          FrameworkCacheRedisProperties properties,
                                          FrameworkSecurityProperties securityProperties) {
            // 有效期直接取 framework-security-starter 的配置对象，不另立一套配置项、
            // 也不自己抄一份默认值——两处默认值各说各话时，"改了没生效"是最难查的一类问题
            return new RedisTokenStore(redisTemplate,
                    objectMapper.getIfAvailable(ObjectMapper::new),
                    properties.getKeyPrefix(), securityProperties.getTokenTtl(),
                    securityProperties.getRefreshToken().getTtl());
        }
    }
}
