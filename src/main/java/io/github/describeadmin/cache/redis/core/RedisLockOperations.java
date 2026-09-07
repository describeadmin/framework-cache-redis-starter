package io.github.describeadmin.cache.redis.core;

import io.github.describeadmin.cache.api.LockHandle;
import io.github.describeadmin.cache.api.LockOperations;
import io.github.describeadmin.common.api.BizException;
import io.github.describeadmin.common.api.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link LockOperations} 的 Redis 实现：多实例部署下的真正分布式锁。
 *
 * <p>加锁是单命令 {@code SET key token NX PX ttl}（原子），解锁是 Lua
 * compare-and-delete——GET 比对 token 相等才 DEL。两步缺一不可：
 * <ul>
 *   <li>不用 Lua 而直接 DEL：TTL 过期后锁已被下一个请求抢走，原持有者的
 *       finally 会<b>误删别人的锁</b>，连环误删下互斥彻底失效</li>
 *   <li>GET 与 DEL 分开两次调用：两步之间锁过期被抢走，同样误删。
 *       Lua 在 Redis 侧单线程执行，天然原子</li>
 * </ul>
 *
 * <p><b>键前缀取框架的 {@code describeadmin.lock.key-prefix}，不用本插件自己的
 * {@code describeadmin.cache.redis.key-prefix}</b>：内存实现与 Redis 实现必须在
 * 同一配置下产出完全相同的键名，替换实现不改变锁语义；多应用共用一个 Redis
 * 实例时，用锁前缀区分彼此（这与缓存/令牌的前缀策略不同，是有意为之——
 * 锁是全局语义，缓存是实例语义）。
 *
 * <p><b>故障语义（与 {@link RedisCacheProvider} 的取舍相反）</b>：
 * <ul>
 *   <li><b>加锁 fail-close</b>：Redis 不可用时抛 {@link BizException}。
 *       缓存读不到可以回源，锁拿不到就放行等于并发保护静默失效</li>
 *   <li><b>解锁 fail-silent</b>：解锁失败只记 WARN 不抛——解锁发生在
 *       finally 语义下，抛异常会掩盖业务结果；锁由 TTL 兜底自动开放</li>
 * </ul>
 *
 * <p>等待策略（{@link #lock}）是轮询重试而非 pub/sub 通知：
 * 与内存实现行为一致（同一套语义、同一套测试可断言），且 v1 的等待场景
 * 对百毫秒级轮询延迟不敏感。
 */
public class RedisLockOperations implements LockOperations {

    private static final Logger log = LoggerFactory.getLogger(RedisLockOperations.class);

    /** 等待策略的轮询间隔，与内存实现保持一致。 */
    private static final long POLL_INTERVAL_MS = 100;

    /** 裸键超过该长度即截断（拼哈希后缀），与内存实现的规则一致。 */
    private static final int MAX_KEY_LENGTH = 128;

    /**
     * compare-and-delete：token 相等才删。返回 1 表示删除成功，0 表示
     * 锁已不属于本持有者（TTL 过期被抢走，或已重复释放）。
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            else
              return 0
            end
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String keyPrefix;

    public RedisLockOperations(StringRedisTemplate redis, String keyPrefix) {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("锁键前缀不能为空");
        }
        this.redis = redis;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public Optional<LockHandle> tryLock(String key, Duration ttl) {
        requireKey(key);
        requireTtl(ttl, "锁存活时间");
        String token = UUID.randomUUID().toString();
        boolean acquired;
        try {
            acquired = Boolean.TRUE.equals(
                    redis.opsForValue().setIfAbsent(fullKey(key), token, ttl));
        } catch (Exception e) {
            // fail-close：锁获取失败时放行 = 并发保护静默失效
            throw new BizException(ResultCode.INTERNAL_ERROR.getCode(),
                    "锁存储不可用，拒绝执行（fail-close）: " + e.getMessage(), e);
        }
        if (!acquired) {
            return Optional.empty();
        }
        return Optional.of(new RedisLockHandle(fullKey(key), token));
    }

    @Override
    public Optional<LockHandle> lock(String key, Duration ttl, Duration waitTimeout) {
        requireTtl(waitTimeout, "等待时间");
        long deadlineNanos = System.nanoTime() + waitTimeout.toNanos();
        while (true) {
            Optional<LockHandle> acquired = tryLock(key, ttl);
            if (acquired.isPresent()) {
                return acquired;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return Optional.empty();
            }
            try {
                long sleepMs = Math.min(POLL_INTERVAL_MS,
                        Math.max(1, remainingNanos / 1_000_000));
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待获取锁时被中断: " + key, e);
            }
        }
    }

    private final class RedisLockHandle implements LockHandle {
        private final String fullKey;
        private final String token;

        RedisLockHandle(String fullKey, String token) {
            this.fullKey = fullKey;
            this.token = token;
        }

        /**
         * 幂等：第二次起脚本比对不上（键已删），返回 0，空操作。
         * fail-silent：异常只记日志——抛出去会掩盖 try 里业务方法的结果，
         * 而锁反正会按 TTL 自动开放。
         */
        @Override
        public void close() {
            try {
                redis.execute(UNLOCK_SCRIPT, List.of(fullKey), token);
            } catch (Exception e) {
                log.warn("释放锁失败，等待 TTL 自动开放: key={}, 原因: {}", fullKey, e.getMessage());
            }
        }
    }

    private String fullKey(String key) {
        String normalized = key.length() <= MAX_KEY_LENGTH
                ? key
                : key.substring(0, MAX_KEY_LENGTH - 65) + "~" + sha256(key);
        return keyPrefix + normalized;
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("锁键不能为空");
        }
    }

    private static void requireTtl(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + "必须为正数，当前为: " + duration);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
