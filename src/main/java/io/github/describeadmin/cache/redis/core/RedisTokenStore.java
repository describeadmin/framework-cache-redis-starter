package io.github.describeadmin.cache.redis.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.describeadmin.security.api.ActiveSession;
import io.github.describeadmin.security.api.LoginUser;
import io.github.describeadmin.security.api.TokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link TokenStore} 的 Redis 实现。
 *
 * <p>解决内存实现的两条局限：<b>重启不掉线</b>、<b>多实例共享会话</b>。
 * 令牌仍然是不透明的随机串，与内存实现同样是 256 位随机量——
 * 换存储不改变"令牌本身不携带信息"这一性质。
 *
 * <p><b>两组键</b>：
 * <ul>
 *   <li>{@code <prefix>session:<token>} —— 会话内容，由 Redis 的存活时间负责过期</li>
 *   <li>{@code <prefix>session-index:<userId>} —— 该用户的令牌集合，用于"强制下线"</li>
 * </ul>
 * 索引是必需的：没有它，按用户吊销就只能全库扫描，而强制下线恰恰是要立刻生效的操作。
 * 索引键自身也设存活时间，避免用户再不登录后留下永久残留。
 *
 * <p><b>索引可能含已过期的令牌</b>：会话键到期自动消失，而集合里的成员不会跟着消失。
 * 这不影响正确性——{@link #revokeAllOf} 只是多删几个不存在的键，
 * {@link #listActive} 不读索引。用一致性换掉一个后台清理任务是划算的。
 */
public class RedisTokenStore implements TokenStore {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenStore.class);

    private static final String SESSION = "session:";
    private static final String INDEX = "session-index:";

    /** SCAN 的单批数量。只影响往返次数，不影响结果。 */
    private static final int SCAN_BATCH = 256;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final Duration ttl;

    public RedisTokenStore(StringRedisTemplate redis, ObjectMapper objectMapper,
                           String keyPrefix, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("令牌有效期必须为正数，当前为: " + ttl);
        }
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
        this.ttl = ttl;
    }

    @Override
    public String issue(LoginUser user) {
        if (user == null) {
            throw new IllegalArgumentException("不能为 null 用户签发令牌");
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = ENCODER.encodeToString(raw);

        Instant now = Instant.now();
        StoredSession session = StoredSession.of(user, now, now.plus(ttl));
        redis.opsForValue().set(sessionKey(token), write(session), ttl);

        if (user.getUserId() != null) {
            String indexKey = indexKey(user.getUserId());
            redis.opsForSet().add(indexKey, token);
            // 索引的存活时间随最后一次签发顺延：只要该用户还有活跃会话，索引就不该先于会话消失
            redis.expire(indexKey, ttl);
        }
        return token;
    }

    @Override
    public Optional<LoginUser> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String json = redis.opsForValue().get(sessionKey(token));
        if (json == null) {
            // 键不存在即已过期或已吊销。未登录是正常流量，不抛异常
            return Optional.empty();
        }
        return read(json).map(StoredSession::toLoginUser);
    }

    @Override
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String key = sessionKey(token);
        // 先读出 userId 才能把它从索引里摘掉；读不到就只删会话键
        String json = redis.opsForValue().get(key);
        redis.delete(key);
        read(json).map(StoredSession::userId)
                .ifPresent(userId -> redis.opsForSet().remove(indexKey(userId), token));
    }

    @Override
    public int revokeAllOf(Long userId) {
        if (userId == null) {
            return 0;
        }
        String indexKey = indexKey(userId);
        Set<String> tokens = redis.opsForSet().members(indexKey);
        if (tokens == null || tokens.isEmpty()) {
            redis.delete(indexKey);
            return 0;
        }
        List<String> keys = tokens.stream().map(this::sessionKey).toList();
        Long deleted = redis.delete(keys);
        redis.delete(indexKey);
        // 以实际删掉的键数为准：索引里可能残留已自然过期的令牌，
        // 报告集合大小会让管理员以为踢掉了比实际更多的会话
        return deleted == null ? 0 : deleted.intValue();
    }

    @Override
    public List<ActiveSession> listActive() {
        List<ActiveSession> sessions = new ArrayList<>();
        // 用 SCAN 而不是 KEYS：KEYS 会阻塞整个 Redis 实例，
        // 在共享 Redis 的部署里，一次管理员点击就能拖垮所有业务
        ScanOptions options = ScanOptions.scanOptions()
                .match(keyPrefix + SESSION + "*")
                .count(SCAN_BATCH)
                .build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                String json = redis.opsForValue().get(cursor.next());
                // 边扫边读，键可能刚好在两步之间过期，读到 null 属正常
                read(json).map(StoredSession::toActiveSession).ifPresent(sessions::add);
            }
        }
        sessions.sort(Comparator.comparing(ActiveSession::getIssuedAt).reversed());
        return sessions;
    }

    private String sessionKey(String token) {
        return keyPrefix + SESSION + token;
    }

    private String indexKey(Long userId) {
        return keyPrefix + INDEX + userId;
    }

    private String write(StoredSession session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (Exception e) {
            throw new IllegalStateException("会话无法序列化为 JSON", e);
        }
    }

    private Optional<StoredSession> read(String json) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(json, StoredSession.class));
        } catch (Exception e) {
            // 升级后存储结构变化时会走到这里。按"未登录"处理让用户重新登录一次，
            // 比抛异常把所有请求打挂要好
            log.warn("会话内容无法解析，按未登录处理。若刚完成升级，这是预期内的一次性现象", e);
            return Optional.empty();
        }
    }

    /**
     * 存进 Redis 的会话结构。
     *
     * <p>刻意不直接序列化 {@link LoginUser}：那样会把 {@code api} 包里的类
     * 与一段持久化格式绑死，之后 {@code LoginUser} 的任何字段调整都要考虑
     * "线上 Redis 里还存着旧格式"。这里的扁平结构由插件自己拥有，可以独立演进。
     */
    record StoredSession(Long userId, String username, String nickname, String authType,
                         Set<String> roles, Set<String> permissions,
                         long issuedAtEpochMilli, long expiresAtEpochMilli) {

        static StoredSession of(LoginUser user, Instant issuedAt, Instant expiresAt) {
            return new StoredSession(user.getUserId(), user.getUsername(), user.getNickname(),
                    user.getAuthType(),
                    new LinkedHashSet<>(user.getRoles()), new LinkedHashSet<>(user.getPermissions()),
                    issuedAt.toEpochMilli(), expiresAt.toEpochMilli());
        }

        LoginUser toLoginUser() {
            return new LoginUser(userId, username, nickname, authType, roles, permissions);
        }

        ActiveSession toActiveSession() {
            return new ActiveSession(userId, username, nickname, authType,
                    Instant.ofEpochMilli(issuedAtEpochMilli),
                    Instant.ofEpochMilli(expiresAtEpochMilli));
        }
    }
}
