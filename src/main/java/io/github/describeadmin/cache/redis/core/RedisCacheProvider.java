package io.github.describeadmin.cache.redis.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.describeadmin.cache.api.CacheProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * {@link CacheProvider} 的 Redis 实现。
 *
 * <p>相对内存实现解决两条局限：重启不丢、多实例共享。
 *
 * <p><b>值一律以 JSON 存储</b>，不用 JDK 序列化，也不开 Jackson 的默认类型信息。
 * 前者要求值实现 {@code Serializable} 且跨版本极脆弱，后者是已知的反序列化攻击入口
 * ——缓存里的内容并不总是可信的。代价是读取时必须知道目标类型，
 * 这也正是 {@link CacheProvider#get} 要求传入 {@code Class} 的原因。
 *
 * <p>JSON 编码与 {@code INCRBY} 是相容的：{@code INCRBY} 写入的是裸整数，
 * 它本身就是合法 JSON，因此 {@link #increment} 写入的值可以被 {@link #get} 按
 * {@code Long.class} 读出。{@code LoginAttemptGuard} 正依赖这一点。
 */
public class RedisCacheProvider implements CacheProvider {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheProvider.class);

    /**
     * 自增并"仅在没有存活时间时才设置存活时间"。
     *
     * <p>必须是一段脚本而不是两次调用：分成 INCRBY + EXPIRE 两步，在两步之间崩溃
     * 会留下一个<b>永不过期</b>的计数键，对登录失败计数就意味着该账号被永久锁定。
     *
     * <p>{@code PTTL < 0} 覆盖两种情况：键刚被 INCRBY 创建（-1，无存活时间）
     * 与键不存在（-2，紧接 INCRBY 之后不会出现）。已有存活时间的键不续期——
     * 续期会让持续的失败尝试把锁定窗口无限延长（见 {@link CacheProvider#increment}）。
     */
    private static final RedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local value = redis.call('INCRBY', KEYS[1], ARGV[1])
            if redis.call('PTTL', KEYS[1]) < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return value
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;

    public RedisCacheProvider(StringRedisTemplate redis, ObjectMapper objectMapper, String keyPrefix) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        requireKey(key);
        if (value == null) {
            evict(key);
            return;
        }
        requireTtl(ttl);
        try {
            redis.opsForValue().set(fullKey(key), objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            // 序列化失败是调用方传了不可序列化的对象，属于编程错误，不应被静默吞掉
            throw new IllegalArgumentException("缓存值无法序列化为 JSON: " + value.getClass().getName(), e);
        }
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        if (key == null || key.isBlank() || type == null) {
            return Optional.empty();
        }
        String json = redis.opsForValue().get(fullKey(key));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(json, type));
        } catch (Exception e) {
            // 与内存实现保持一致：类型对不上按未命中处理。
            // 升级期缓存里存着旧结构的数据是常态，应表现为"未命中并回源"而不是把请求打挂
            log.debug("缓存值与期望类型不符，按未命中处理: key={}, type={}", key, type.getName());
            return Optional.empty();
        }
    }

    @Override
    public void evict(String key) {
        if (key != null && !key.isBlank()) {
            redis.delete(fullKey(key));
        }
    }

    @Override
    public long increment(String key, long delta, Duration ttlWhenCreated) {
        requireKey(key);
        requireTtl(ttlWhenCreated);
        Long value = redis.execute(INCREMENT_SCRIPT, List.of(fullKey(key)),
                String.valueOf(delta), String.valueOf(ttlWhenCreated.toMillis()));
        return value == null ? 0L : value;
    }

    private String fullKey(String key) {
        return keyPrefix + key;
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("缓存键不能为空");
        }
    }

    private static void requireTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("缓存存活时间必须为正数，当前为: " + ttl);
        }
    }
}
