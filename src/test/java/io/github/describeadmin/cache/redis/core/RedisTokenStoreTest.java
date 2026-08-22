package io.github.describeadmin.cache.redis.core;

import io.github.describeadmin.cache.redis.AbstractRedisTest;
import io.github.describeadmin.security.api.ActiveSession;
import io.github.describeadmin.security.api.IssuedTokens;
import io.github.describeadmin.security.api.LoginUser;
import io.github.describeadmin.security.api.TokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RedisTokenStore} 的契约验证。
 *
 * <p>与 {@code InMemoryTokenStoreTest} 一一对应，另加两条只有集中式实现才谈得上的：
 * 令牌能被"另一个实例"解析（多实例共享），以及重启后仍然有效（重启不掉线）。
 * 这两条正是引入本插件的全部理由，不验证它们等于没验证。
 */
@DisplayName("Redis 令牌存储")
class RedisTokenStoreTest extends AbstractRedisTest {

    private StringRedisTemplate template;
    private TokenStore store;

    @BeforeEach
    void setUp() {
        template = newTemplate();
        flush(template);
        store = newStore();
    }

    private TokenStore newStore() {
        return new RedisTokenStore(template, objectMapper(), "test:", Duration.ofMinutes(30));
    }

    private static LoginUser user(long id, String username, String nickname) {
        return new LoginUser(id, username, nickname, "password",
                Set.of("ADMIN"), Set.of("system:user:list"));
    }

    // ---------------------------------------------------- 与内存实现一致的契约

    @Test
    @DisplayName("签发的令牌可解析回原用户，中文昵称与权限点都不丢")
    void issueThenResolve() {
        String token = store.issue(user(1L, "admin", "超级管理员"));

        LoginUser resolved = store.resolve(token).orElseThrow();
        assertThat(resolved.getUserId()).isEqualTo(1L);
        assertThat(resolved.getUsername()).isEqualTo("admin");
        assertThat(resolved.getNickname()).isEqualTo("超级管理员");
        assertThat(resolved.getRoles()).containsExactly("ADMIN");
        assertThat(resolved.getPermissions()).containsExactly("system:user:list");
    }

    @Test
    @DisplayName("令牌不携带任何用户信息，且每次签发都不同")
    void tokenIsOpaque() {
        String first = store.issue(user(1L, "admin", "超级管理员"));
        String second = store.issue(user(1L, "admin", "超级管理员"));

        // 换存储不改变"不透明令牌"这一性质：令牌是纯随机量，与用户无任何可推导的关系
        assertThat(first).doesNotContain("admin").doesNotContain("超级管理员");
        assertThat(first).isNotEqualTo(second);
        // 32 字节随机量的 URL-safe Base64（无填充）= 43 个字符
        assertThat(first).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    @DisplayName("未知令牌解析为空，而不是抛异常")
    void unknownTokenResolvesEmpty() {
        assertThat(store.resolve("not-a-real-token")).isEmpty();
        assertThat(store.resolve(null)).isEmpty();
        assertThat(store.resolve("")).isEmpty();
    }

    @Test
    @DisplayName("登出后令牌立即失效")
    void revokeInvalidates() {
        String token = store.issue(user(1L, "admin", "超级管理员"));
        store.revoke(token);

        assertThat(store.resolve(token)).isEmpty();
    }

    @Test
    @DisplayName("强制下线吊销该用户全部会话，其他人不受影响")
    void revokeAllOf() {
        String a1 = store.issue(user(1L, "admin", "超级管理员"));
        String a2 = store.issue(user(1L, "admin", "超级管理员"));
        String b1 = store.issue(user(2L, "bob", "小明"));

        assertThat(store.revokeAllOf(1L)).isEqualTo(2);
        assertThat(store.resolve(a1)).isEmpty();
        assertThat(store.resolve(a2)).isEmpty();
        assertThat(store.resolve(b1)).isNotEmpty();
    }

    @Test
    @DisplayName("踢一个不在线的用户返回 0")
    void revokeAllOfOfflineUser() {
        assertThat(store.revokeAllOf(999L)).isZero();
        assertThat(store.revokeAllOf(null)).isZero();
    }

    @Test
    @DisplayName("在线列表按会话粒度给出，最近登录的在前")
    void listActive() throws Exception {
        store.issue(user(1L, "first", "先登录"));
        Thread.sleep(10);
        store.issue(user(2L, "second", "后登录"));

        assertThat(store.listActive())
                .extracting(ActiveSession::getUsername)
                .containsExactly("second", "first");
    }

    @Test
    @DisplayName("登出的会话从在线列表中消失")
    void revokedSessionNotListed() {
        String token = store.issue(user(1L, "admin", "超级管理员"));
        store.issue(user(2L, "bob", "小明"));

        store.revoke(token);

        assertThat(store.listActive())
                .extracting(ActiveSession::getUsername)
                .containsExactly("bob");
    }

    @Test
    @DisplayName("过期会话不出现在在线列表里")
    void expiredNotListed() throws Exception {
        TokenStore shortLived = new RedisTokenStore(template, objectMapper(), "test:",
                Duration.ofMillis(300));
        shortLived.issue(user(1L, "admin", "超级管理员"));

        Thread.sleep(600);

        assertThat(shortLived.listActive()).isEmpty();
    }

    // ------------------------------------------- 只有集中式实现才成立的两条

    @Test
    @DisplayName("另一个实例能解析出同一个令牌——这就是'多实例共享会话'")
    void tokenIsVisibleToAnotherInstance() {
        String token = store.issue(user(1L, "admin", "超级管理员"));

        // 全新的 StringRedisTemplate + 全新的 TokenStore，模拟另一个应用实例
        TokenStore anotherInstance = new RedisTokenStore(newTemplate(), objectMapper(),
                "test:", Duration.ofMinutes(30));

        assertThat(anotherInstance.resolve(token))
                .as("内存实现在这里必然为空，这正是它不支持多实例的原因")
                .isNotEmpty();
    }

    @Test
    @DisplayName("在一个实例上踢下线，另一个实例上立即生效")
    void revokeIsVisibleToAnotherInstance() {
        String token = store.issue(user(1L, "admin", "超级管理员"));
        TokenStore anotherInstance = new RedisTokenStore(newTemplate(), objectMapper(),
                "test:", Duration.ofMinutes(30));

        anotherInstance.revokeAllOf(1L);

        // 否则"强制下线"在多实例部署下只是踢掉了运气好落在同一实例的那些会话
        assertThat(store.resolve(token)).isEmpty();
    }

    @Test
    @DisplayName("键前缀隔离：不同前缀的两个应用互不可见")
    void keyPrefixIsolatesApplications() {
        String token = store.issue(user(1L, "admin", "超级管理员"));

        TokenStore otherApp = new RedisTokenStore(template, objectMapper(),
                "other-app:", Duration.ofMinutes(30));

        assertThat(otherApp.resolve(token)).isEmpty();
        assertThat(otherApp.listActive()).isEmpty();
        // 共用一个 Redis 实例时，A 应用的强制下线不该波及 B 应用
        assertThat(otherApp.revokeAllOf(1L)).isZero();
        assertThat(store.resolve(token)).isNotEmpty();
    }

    // ------------------------------------------------------- 刷新令牌（与内存实现一一对应）

    @Test
    @DisplayName("签发的一对令牌都能独立解析")
    void issuedPairBothResolve() {
        IssuedTokens tokens = store.issueWithRefresh(user(1L, "admin", "超级管理员"));

        assertThat(tokens.getAccessToken()).isNotBlank();
        assertThat(tokens.getRefreshToken()).isNotBlank();
        assertThat(store.resolve(tokens.getAccessToken())).isPresent();
    }

    @Test
    @DisplayName("刷新成功后旧 refresh token 立即失效（轮换）")
    void refreshRotatesOldRefreshToken() {
        IssuedTokens first = store.issueWithRefresh(user(1L, "admin", "超级管理员"));

        Optional<IssuedTokens> second = store.refresh(first.getRefreshToken());

        assertThat(second).isPresent();
        assertThat(second.get().getRefreshToken()).isNotEqualTo(first.getRefreshToken());
        assertThat(store.refresh(first.getRefreshToken())).isEmpty();
    }

    @Test
    @DisplayName("刷新后新 access token 能正确解析出原用户，中文昵称不丢")
    void refreshedAccessTokenResolvesToSameUser() {
        IssuedTokens first = store.issueWithRefresh(user(1L, "admin", "超级管理员"));

        IssuedTokens second = store.refresh(first.getRefreshToken()).orElseThrow();

        assertThat(store.resolve(second.getAccessToken()))
                .isPresent()
                .get()
                .extracting(LoginUser::getNickname)
                .isEqualTo("超级管理员");
    }

    @Test
    @DisplayName("不存在的 refresh token 返回空")
    void refreshWithUnknownTokenReturnsEmpty() {
        assertThat(store.refresh("not-a-real-token")).isEmpty();
        assertThat(store.refresh(null)).isEmpty();
        assertThat(store.refresh("")).isEmpty();
    }

    @Test
    @DisplayName("已过期的 refresh token 返回空")
    void refreshWithExpiredTokenReturnsEmpty() throws Exception {
        TokenStore shortLived = new RedisTokenStore(template, objectMapper(), "test:",
                Duration.ofMinutes(30), Duration.ofMillis(300));
        IssuedTokens tokens = shortLived.issueWithRefresh(user(1L, "admin", "超级管理员"));

        Thread.sleep(600);

        assertThat(shortLived.refresh(tokens.getRefreshToken())).isEmpty();
    }

    @Test
    @DisplayName("revokeAllOf 必须同时吊销 access 与 refresh 令牌——否则改密码/禁用立即失效会被绕过")
    void revokeAllOfRevokesBothAccessAndRefresh() {
        IssuedTokens tokens = store.issueWithRefresh(user(1L, "admin", "超级管理员"));

        store.revokeAllOf(1L);

        assertThat(store.resolve(tokens.getAccessToken())).isEmpty();
        assertThat(store.refresh(tokens.getRefreshToken())).isEmpty();
    }

    @Test
    @DisplayName("revokeRefreshToken 只吊销 refresh token，不影响同一用户的 access token")
    void revokeRefreshTokenOnlyAffectsRefreshToken() {
        IssuedTokens tokens = store.issueWithRefresh(user(1L, "admin", "超级管理员"));

        store.revokeRefreshToken(tokens.getRefreshToken());

        assertThat(store.resolve(tokens.getAccessToken())).isPresent();
        assertThat(store.refresh(tokens.getRefreshToken())).isEmpty();
    }

    @Test
    @DisplayName("刷新令牌在另一个实例上同样可用——多实例共享是引入本插件的全部理由")
    void refreshTokenIsVisibleToAnotherInstance() {
        IssuedTokens tokens = store.issueWithRefresh(user(1L, "admin", "超级管理员"));

        TokenStore anotherInstance = new RedisTokenStore(newTemplate(), objectMapper(),
                "test:", Duration.ofMinutes(30));

        assertThat(anotherInstance.refresh(tokens.getRefreshToken())).isNotEmpty();
    }
}
