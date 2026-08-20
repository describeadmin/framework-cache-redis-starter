package io.github.describeadmin.cache.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 起一个真实 Redis 供本模块测试使用。
 *
 * <p><b>为什么不用 mock 或嵌入式实现</b>：本模块的全部价值就在于"与真实 Redis 的语义一致"——
 * Lua 脚本的原子性、{@code PTTL} 的取值、{@code SCAN} 的游标行为，这些恰恰是 mock 掉之后
 * 就再也验证不到的东西。用假的 Redis 测 Redis 实现，等于把要验证的部分假设成立。
 *
 * <p>镜像版本可参数化（{@code -Dredis.image=...}），默认钉在具体版本而非 {@code latest}——
 * {@code latest} 意味着同一份代码在不同时间跑的不是同一个东西。
 *
 * <p>整个 JVM 共用一个容器：容器启动是这组测试里最慢的一步，
 * 每个测试类各起一个会让本模块的构建时间翻几倍。
 */
public abstract class AbstractRedisTest {

    private static final String IMAGE = System.getProperty("redis.image", "redis:7.4-alpine");

    private static final GenericContainer<?> REDIS;

    static {
        REDIS = new GenericContainer<>(DockerImageName.parse(IMAGE)).withExposedPorts(6379);
        REDIS.start();
    }

    protected static String redisHost() {
        return REDIS.getHost();
    }

    protected static int redisPort() {
        return REDIS.getMappedPort(6379);
    }

    protected static StringRedisTemplate newTemplate() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        return new StringRedisTemplate(factory);
    }

    protected static ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * 清空当前库。
     *
     * <p>共用容器意味着用例之间会互相看到对方写的键，必须在每个用例前清干净——
     * 否则会出现"单独跑能过、一起跑就挂"这类最难查的测试问题。
     */
    protected static void flush(StringRedisTemplate template) {
        template.getConnectionFactory().getConnection().serverCommands().flushDb();
    }
}
