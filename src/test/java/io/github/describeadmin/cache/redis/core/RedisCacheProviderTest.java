package io.github.describeadmin.cache.redis.core;

import io.github.describeadmin.cache.api.CacheProvider;
import io.github.describeadmin.cache.redis.AbstractRedisTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RedisCacheProvider} 的契约验证。
 *
 * <p>用例刻意与 {@code InMemoryCacheProviderTest} 一一对应：两种实现对同一份契约的
 * 行为必须一致，否则"换实现不影响上层代码"这句话就不成立。差异如果存在，
 * 应当出现在这里而不是出现在生产环境。
 */
@DisplayName("Redis 缓存")
class RedisCacheProviderTest extends AbstractRedisTest {

    private StringRedisTemplate template;
    private CacheProvider cache;

    @BeforeEach
    void setUp() {
        template = newTemplate();
        flush(template);
        cache = new RedisCacheProvider(template, objectMapper(), "test:");
    }

    @Test
    @DisplayName("写入后可按原类型读出，中文不乱码")
    void putThenGet() {
        cache.put("k", "中文值", Duration.ofMinutes(1));

        assertThat(cache.get("k", String.class)).contains("中文值");
    }

    @Test
    @DisplayName("键前缀真的写进了 Redis，多应用共用实例时才不会互相踩")
    void appliesKeyPrefix() {
        cache.put("k", "v", Duration.ofMinutes(1));

        assertThat(template.hasKey("test:k")).isTrue();
        assertThat(template.hasKey("k")).isFalse();
    }

    @Test
    @DisplayName("未命中返回空")
    void missReturnsEmpty() {
        assertThat(cache.get("never-written", String.class)).isEmpty();
    }

    @Test
    @DisplayName("类型不匹配按未命中处理，不抛异常")
    void typeMismatchIsMiss() {
        cache.put("k", "不是数字", Duration.ofMinutes(1));

        assertThat(cache.get("k", Long.class)).isEmpty();
    }

    @Test
    @DisplayName("存活时间真的设到了 Redis 上，不是只存不过期")
    void ttlIsApplied() {
        cache.put("k", "v", Duration.ofMinutes(5));

        Long ttl = template.getExpire("test:k", TimeUnit.SECONDS);
        // 只断言"设了且在合理范围"，不断言精确值——那会是一条随机失败的用例
        assertThat(ttl).isNotNull().isGreaterThan(0L).isLessThanOrEqualTo(300L);
    }

    @Test
    @DisplayName("过期后读不出")
    void expiredIsMiss() throws Exception {
        cache.put("k", "v", Duration.ofMillis(300));
        Thread.sleep(600);

        assertThat(cache.get("k", String.class)).isEmpty();
    }

    @Test
    @DisplayName("写入 null 等同于删除")
    void putNullEvicts() {
        cache.put("k", "v", Duration.ofMinutes(1));
        cache.put("k", null, Duration.ofMinutes(1));

        assertThat(cache.get("k", String.class)).isEmpty();
    }

    @Test
    @DisplayName("自增结果可被 get 按 Long 读出——INCRBY 写的裸整数本身是合法 JSON")
    void incrementIsReadableAsLong() {
        cache.increment("c", 3, Duration.ofMinutes(1));

        // 这条不成立的话，LoginAttemptGuard 的"记失败用 increment、判锁定用 get"在 Redis 下就是错的
        assertThat(cache.get("c", Long.class)).contains(3L);
    }

    @Test
    @DisplayName("自增给新键设置存活时间，避免留下永不过期的计数键")
    void incrementSetsTtlOnCreate() {
        cache.increment("c", 1, Duration.ofMinutes(5));

        Long ttl = template.getExpire("test:c", TimeUnit.SECONDS);
        // 若这里是 -1（无存活时间），对登录失败计数就意味着账号被永久锁定
        assertThat(ttl).isNotNull().isGreaterThan(0L);
    }

    @Test
    @DisplayName("已存在的键只自增、不续期")
    void incrementDoesNotRenewTtl() throws Exception {
        cache.increment("c", 1, Duration.ofSeconds(2));
        Thread.sleep(1000);
        cache.increment("c", 1, Duration.ofSeconds(2));

        Long ttl = template.getExpire("test:c", TimeUnit.MILLISECONDS);
        assertThat(ttl)
                .as("第二次自增若重置了存活时间，锁定窗口会被持续尝试无限延长")
                .isNotNull()
                .isLessThan(1500L);
    }

    @Test
    @DisplayName("并发自增不丢计数")
    void incrementIsAtomic() throws Exception {
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        cache.increment("hot", 1, Duration.ofMinutes(1));
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(failures).hasValue(0);
        // Lua 脚本在 Redis 上单线程执行，INCRBY 与设置存活时间之间不会被插入其他命令
        assertThat(cache.get("hot", Long.class)).contains((long) threads * perThread);
    }

    @Test
    @DisplayName("按前缀枚举时返回逻辑 key，不带本实例的物理前缀")
    void keysWithPrefixStripsPhysicalPrefix() {
        cache.put("describeadmin:login:fail:alice", 1L, Duration.ofMinutes(1));
        cache.put("describeadmin:login:fail:bob", 1L, Duration.ofMinutes(1));
        cache.put("describeadmin:other:thing", 1L, Duration.ofMinutes(1));

        assertThat(cache.keysWithPrefix("describeadmin:login:fail:"))
                .containsExactlyInAnyOrder(
                        "describeadmin:login:fail:alice", "describeadmin:login:fail:bob");
        // Redis 里实际存的是带 "test:" 物理前缀的 key，调用方看到的必须是去掉它之后的逻辑 key，
        // 否则同一个前缀在 InMemoryCacheProvider 和 RedisCacheProvider 下会得到不同形状的结果
        assertThat(template.hasKey("test:describeadmin:login:fail:alice")).isTrue();
    }

    @Test
    @DisplayName("没有匹配项时返回空集合")
    void keysWithPrefixEmptyWhenNoMatch() {
        assertThat(cache.keysWithPrefix("no-such-prefix:")).isEmpty();
    }

    @Test
    @DisplayName("已过期的 key 不出现在按前缀枚举的结果里")
    void keysWithPrefixExcludesExpired() throws Exception {
        cache.put("describeadmin:login:fail:alice", 1L, Duration.ofMillis(300));
        Thread.sleep(600);

        assertThat(cache.keysWithPrefix("describeadmin:login:fail:")).isEmpty();
    }
}
