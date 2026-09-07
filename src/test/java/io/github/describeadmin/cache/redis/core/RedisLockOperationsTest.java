package io.github.describeadmin.cache.redis.core;

import io.github.describeadmin.cache.api.LockHandle;
import io.github.describeadmin.cache.redis.AbstractRedisTest;
import io.github.describeadmin.common.api.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RedisLockOperations} 的测试——跑在真实 Redis 上（理由见
 * {@link AbstractRedisTest}：SET NX 的原子性、Lua 解锁的 compare-and-delete
 * 语义，恰恰是 mock 掉之后就再也验证不到的东西）。
 *
 * <p>语义用例与 {@code InMemoryLockOperationsTest} 一一对应：
 * 两个实现必须表现一致，替换实现才不改变锁语义。
 */
@DisplayName("Redis 分布式锁")
class RedisLockOperationsTest extends AbstractRedisTest {

    private static final String PREFIX = "describeadmin:lock:";

    private StringRedisTemplate redis;
    private RedisLockOperations locks;

    @BeforeEach
    void setUp() {
        redis = newTemplate();
        flush(redis);
        locks = new RedisLockOperations(redis, PREFIX);
    }

    @Nested
    @DisplayName("tryLock")
    class TryLock {

        @Test
        @DisplayName("先到的拿到，后到的空手而归")
        void secondAcquirerGetsEmpty() {
            assertThat(locks.tryLock("user:1", Duration.ofSeconds(10))).isPresent();
            assertThat(locks.tryLock("user:1", Duration.ofSeconds(10))).isEmpty();
        }

        @Test
        @DisplayName("锁确实落在 Redis 上：另一个实例（同前缀）看得到")
        void lockVisibleAcrossInstances() {
            assertThat(locks.tryLock("user:1", Duration.ofSeconds(10))).isPresent();

            // 多实例互斥的根基：第二个实例用同一前缀构造，看到同一把锁
            RedisLockOperations anotherInstance = new RedisLockOperations(redis, PREFIX);
            assertThat(anotherInstance.tryLock("user:1", Duration.ofSeconds(10))).isEmpty();
        }

        @Test
        @DisplayName("键带 TTL：宕机自愈不依赖显式释放")
        void lockExpiresInRedis() throws Exception {
            locks.tryLock("user:1", Duration.ofMillis(80)).orElseThrow();
            Thread.sleep(150);

            assertThat(redis.hasKey(PREFIX + "user:1")).isFalse();
            assertThat(locks.tryLock("user:1", Duration.ofSeconds(10))).isPresent();
        }

        @Test
        @DisplayName("释放后可重新获取，close 幂等")
        void releaseAndIdempotentClose() {
            LockHandle handle = locks.tryLock("user:1", Duration.ofSeconds(10)).orElseThrow();
            handle.close();
            handle.close();

            assertThat(locks.tryLock("user:1", Duration.ofSeconds(10))).isPresent();
        }
    }

    @Nested
    @DisplayName("token 安全（Lua compare-and-delete）")
    class TokenSafety {

        @Test
        @DisplayName("TTL 过期、锁被他人抢走后，旧句柄的 close 不误删新锁")
        void staleHandleCannotReleaseOthersLock() throws Exception {
            LockHandle stale = locks.tryLock("user:1", Duration.ofMillis(50)).orElseThrow();
            Thread.sleep(100);
            assertThat(locks.tryLock("user:1", Duration.ofSeconds(30))).isPresent();

            // 场景：长任务的 finally 比业务本身跑得晚，锁已易主
            stale.close();

            assertThat(locks.tryLock("user:1", Duration.ofSeconds(10)))
                    .as("若 close 误删了新锁，这里应该能获取到")
                    .isEmpty();
        }

        @Test
        @DisplayName("直接删键模拟误删防护缺失的对照组：锁语义立即破坏（防回归的自证用例）")
        void delWithoutTokenWouldBreakMutualExclusion() throws Exception {
            // 本用例刻意展示"为什么不许直接 DEL"：绕过 token 比对删除后，
            // 第三个请求可以立即加锁，互斥被破坏
            assertThat(locks.tryLock("user:1", Duration.ofSeconds(30))).isPresent();
            redis.delete(PREFIX + "user:1");
            assertThat(locks.tryLock("user:1", Duration.ofSeconds(10)))
                    .as("绕过 Lua 直接 DEL 确实会破坏互斥——这就是必须比对 token 的原因")
                    .isPresent();
        }
    }

    @Nested
    @DisplayName("lock（限时等待）")
    class WaitLock {

        @Test
        @DisplayName("锁被占时等待，释放后获取成功")
        void waitsUntilReleased() throws Exception {
            LockHandle held = locks.tryLock("user:1", Duration.ofSeconds(30)).orElseThrow();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> {
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    held.close();
                });
                Optional<LockHandle> acquired = locks.lock("user:1", Duration.ofSeconds(10), Duration.ofSeconds(5));
                assertThat(acquired).isPresent();
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("等待超时返回空")
        void timeoutReturnsEmpty() {
            assertThat(locks.tryLock("user:1", Duration.ofSeconds(30))).isPresent();

            assertThat(locks.lock("user:1", Duration.ofSeconds(30), Duration.ofMillis(300)))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("并发与故障")
    class ConcurrencyAndFailure {

        @Test
        @DisplayName("N 个线程抢同一把锁，恰好一个成功")
        void exactlyOneWinner() throws Exception {
            int threads = 16;
            AtomicInteger winners = new AtomicInteger();
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            try {
                var futures = IntStream.range(0, threads)
                        .mapToObj(i -> executor.submit(() -> {
                            if (locks.tryLock("user:hot", Duration.ofSeconds(30)).isPresent()) {
                                winners.incrementAndGet();
                            }
                        }))
                        .toList();
                for (var future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
            }

            assertThat(winners.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("fail-close：存储不可用时加锁抛异常而不是放行")
        void storageFailureFailsClosed() {
            // 指向一个不存在的端口：连接失败的等价物，无需真的拔 Redis
            org.springframework.data.redis.connection.RedisStandaloneConfiguration config =
                    new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
                            "127.0.0.1", redisPort() + 1);
            org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory =
                    new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(config);
            factory.afterPropertiesSet();
            StringRedisTemplate broken = new StringRedisTemplate(factory);
            RedisLockOperations brokenLocks = new RedisLockOperations(broken, PREFIX);

            // Lettuce 的连接是惰性建立的，第一次命令才尝试连接——正是要测的时机
            assertThatThrownBy(() -> brokenLocks.tryLock("user:1", Duration.ofSeconds(10)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("fail-close");
        }
    }
}
