package io.github.describeadmin.cache.redis.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * framework-cache-redis-starter 的配置项，前缀 {@code describeadmin.cache.redis}。
 */
@ConfigurationProperties(prefix = "describeadmin.cache.redis")
public class FrameworkCacheRedisProperties {

    /**
     * 是否启用本插件。
     *
     * <p>关闭后框架退回内存实现，等同于没有引入本插件——这是"两层开关"里的运行时那层：
     * 编译期由是否引入 starter 决定能力存在与否，运行时由本项决定是否激活。
     * 排查"是不是 Redis 的问题"时不必改 pom.xml 重新打包。
     */
    private boolean enabled = true;

    /**
     * 键前缀。
     *
     * <p>多个应用共用一个 Redis 实例时必须区分，否则 A 应用的强制下线会波及 B 应用。
     * 同理，多个 worktree 共用本地 Redis 时也应各自设置（方案 6.1 的隔离策略）。
     */
    private String keyPrefix = "describeadmin:";

    /**
     * 是否接管令牌存储。
     *
     * <p>默认接管——只把缓存放 Redis 而令牌仍在内存里，重启照样全员掉线，
     * 那样引入本插件的主要收益就没了。
     *
     * <p>置为 {@code false} 的合理场景：令牌由外部统一认证中心签发，
     * 业务方有自己的 {@code TokenStore} 实现。
     */
    private boolean tokenStoreEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
    }

    public boolean isTokenStoreEnabled() {
        return tokenStoreEnabled;
    }

    public void setTokenStoreEnabled(boolean tokenStoreEnabled) {
        this.tokenStoreEnabled = tokenStoreEnabled;
    }
}
