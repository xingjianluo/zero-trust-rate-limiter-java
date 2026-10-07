package com.github.xingjianluo.zerotrustratelimiterjava.tests;

import com.github.xingjianluo.zerotrustratelimiterjava.audit.DefaultDecisionAuditLog;
import com.github.xingjianluo.zerotrustratelimiterjava.config.RateLimitProperties;
import com.github.xingjianluo.zerotrustratelimiterjava.core.BanService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.DecisionCache;
import com.github.xingjianluo.zerotrustratelimiterjava.core.KeyExtractor;
import com.github.xingjianluo.zerotrustratelimiterjava.core.PolicyHolder;
import com.github.xingjianluo.zerotrustratelimiterjava.core.QuotaViewService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.WeightResolver;
import com.github.xingjianluo.zerotrustratelimiterjava.model.Decision;
import com.github.xingjianluo.zerotrustratelimiterjava.model.DecisionReason;
import com.github.xingjianluo.zerotrustratelimiterjava.model.FailMode;
import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;
import com.github.xingjianluo.zerotrustratelimiterjava.model.QuotaView;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestContext;
import com.github.xingjianluo.zerotrustratelimiterjava.store.FileBanStateStore;
import com.github.xingjianluo.zerotrustratelimiterjava.store.InMemoryRateLimitStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重启后状态延续：配额计数（加权/分层）持久化到文件，进程“重启”（用同一文件路径重建存储与
 * 服务，并以相同版本号发布策略）后当前窗口用量延续，不被清零；封禁状态与人工解除状态同样延续。
 */
class RestartPersistenceTest {

    private static final Logger log = LoggerFactory.getLogger(RestartPersistenceTest.class);

    /** 登记每个用例创建的 Harness，结束后统一等待异步写盘，避免与 TempDir 清理竞态。 */
    private static final java.util.List<Harness> HARNESSES = new java.util.ArrayList<>();

    @AfterEach
    void drainAllHarnesses() {
        for (Harness h : HARNESSES) {
            h.drainAll();
        }
        HARNESSES.clear();
    }

    /** 一套可重启的组件：每次 restart() 用相同文件路径重建存储，模拟新进程加载已有数据。 */
    private static final class Harness {
        ManualClock clock;
        InMemoryRateLimitStateStore quotaStore;
        FileBanStateStore banStore;
        RateLimitService service;
        QuotaViewService viewService;
        BanService banService;
        final Path quotaFile;
        final Path banFile;
        final java.util.List<InMemoryRateLimitStateStore> opened = new java.util.ArrayList<>();
        long versionSeq = 0;

        Harness(ManualClock clock, Path dir) {
            this.clock = clock;
            this.quotaFile = dir.resolve("quota.json");
            this.banFile = dir.resolve("ban.json");
            wire();
            HARNESSES.add(this);
        }

        private void wire() {
            quotaStore = new InMemoryRateLimitStateStore(clock, quotaFile.toString());
            opened.add(quotaStore);
            banStore = new FileBanStateStore(clock, banFile.toString());
            banService = new BanService(banStore, clock);
            PolicyHolder holder = new PolicyHolder();
            DecisionCache cache = new DecisionCache(clock);
            var audit = new DefaultDecisionAuditLog(clock);
            // 传入配额文件路径以恢复持久化的策略版本号
            service = new RateLimitService(quotaStore, banService, new KeyExtractor(),
                    new WeightResolver(), holder, cache, clock, audit, quotaFile.toString());
            viewService = new QuotaViewService(quotaStore, banService, holder, clock);
        }

        long publish(long windowMs, long limit) {
            return publish(TestProps.create(windowMs, limit, FailMode.FAIL_CLOSED, 100));
        }

        long publish(RateLimitProperties props) {
            return service.publishInitial(props);
        }

        void drainAll() {
            for (InMemoryRateLimitStateStore s : opened) {
                s.flush();
            }
        }

        Decision req(String id, String ip) {
            return service.decide(new RequestContext(id, ip, null, "/api/hello", "GET"));
        }
    }

    @Test
    void quotaUsageSurvivesRestartWithinSameWindow() {
        ManualClock clock = new ManualClock(60_000L); // 60_000 对齐 1000ms 窗口
        Path dir = newTempDir();
        Harness h = new Harness(clock, dir);
        h.publish(1000, 10);

        // 同窗口消耗 6 个单位（6 个普通请求）
        for (int i = 0; i < 6; i++) {
            assertTrue(h.req("a-" + i, "10.10.10.10").allowed());
        }
        QuotaView before = h.viewService.view(LimitDimension.IP, "10.10.10.10");
        assertEquals(6, before.tiers().get(0).used());
        assertEquals(4, before.tiers().get(0).remaining());
        h.quotaStore.flush();

        // 模拟重启：用同一文件路径新建存储，加载已有计数
        InMemoryRateLimitStateStore rebuilt =
                new InMemoryRateLimitStateStore(clock, dir.resolve("quota.json").toString());
        h.opened.add(rebuilt);

        // 用重建后的存储装配服务与只读视图；版本号从持久化状态恢复（仍为版本 1），
        // 因此重启后当前窗口用量延续，视图与判定同源
        PolicyHolder holder = new PolicyHolder();
        RateLimitService rebuiltService = new RateLimitService(rebuilt, h.banService,
                new KeyExtractor(), new WeightResolver(), holder, new DecisionCache(clock),
                clock, new DefaultDecisionAuditLog(clock), dir.resolve("quota.json").toString());
        RateLimitProperties props = TestProps.create(1000, 10, FailMode.FAIL_CLOSED, 100);
        long restoredVersion = rebuiltService.publishInitial(props);
        assertEquals(1, restoredVersion, "重启后策略版本号应从持久化状态延续，而非重新递增");
        QuotaViewService rebuiltView = new QuotaViewService(rebuilt, h.banService, holder, clock);
        QuotaView after = rebuiltView.view(LimitDimension.IP, "10.10.10.10");
        assertEquals(6, after.tiers().get(0).used(), "重启后同版本当前窗口用量必须延续");
        assertEquals(4, after.tiers().get(0).remaining());
        // 再来一个请求：用量 7，证明判定与视图读同一份延续状态
        rebuiltService.decide(new RequestContext("after-1", "10.10.10.10",
                null, "/api/hello", "GET"));
        assertEquals(7, rebuiltView.view(LimitDimension.IP, "10.10.10.10")
                .tiers().get(0).used());
        log.info("quota usage after restart preserved: used=6 then 7, version=1");
    }

    @Test
    void weightedAndTieredUsageSurvivesRestart() {
        ManualClock clock = new ManualClock(60_000L);
        Path dir = newTempDir();
        Harness h = new Harness(clock, dir);
        var p = TestProps.create(1000, 100, FailMode.FAIL_CLOSED, 100);
        List<RateLimitProperties.Tier> tiers = new ArrayList<>();
        tiers.add(new RateLimitProperties.Tier(LimitDimension.IP, Duration.ofSeconds(1), 10));
        tiers.add(new RateLimitProperties.Tier(LimitDimension.GLOBAL, Duration.ofSeconds(1), 100));
        p.getPolicy().setTiers(tiers);
        p.getPolicy().setWeights(java.util.Map.of("/api/export", 5L));
        h.publish(p);

        // 一个权重 5 的导出请求
        Decision heavy = h.service.decide(
                new RequestContext("exp-1", "10.20.30.40", null, "/api/export/big", "GET"));
        assertTrue(heavy.allowed());
        assertEquals(5, heavy.requestWeight());
        h.quotaStore.flush();

        // 重启加载：IP 短窗口用量应为 5（按权重累计），GLOBAL 层也为 5
        InMemoryRateLimitStateStore rebuilt =
                new InMemoryRateLimitStateStore(clock, dir.resolve("quota.json").toString());
        h.opened.add(rebuilt);
        assertEquals(5, rebuilt.snapshot(1L, LimitDimension.IP, "10.20.30.40",
                1000L, List.of(60_000L)).get(0).used());
        assertEquals(5, rebuilt.snapshot(1L, LimitDimension.GLOBAL, "global",
                1000L, List.of(60_000L)).get(0).used());
    }

    @Test
    void banAndManualUnbanSurviveRestart() {
        ManualClock clock = new ManualClock(70_000L);
        Path dir = newTempDir();
        Harness h = new Harness(clock, dir);
        var p = TestProps.create(1000, 2, FailMode.FAIL_CLOSED, 100);
        p.getBan().setEnabled(true);
        p.getBan().setThresholdWindows(1); // 一个超限窗口即封
        p.getBan().setLevelDuration(List.of(Duration.ofMinutes(10)));
        h.publish(p);

        // 打超触发封禁：2 放行，第 3 个 RATE_LIMITED 且立即升级封禁
        h.req("b-1", "10.77.77.77");
        h.req("b-2", "10.77.77.77");
        Decision trigger = h.req("b-3", "10.77.77.77");
        assertEquals(DecisionReason.RATE_LIMITED, trigger.reason());
        // 同窗口随后请求应 BANNED
        Decision banned = h.req("b-4", "10.77.77.77");
        assertEquals(DecisionReason.BANNED, banned.reason());

        // 重启：封禁必须延续（不能重启就解除）
        Harness h2 = new Harness(clock, dir);
        var p2 = TestProps.create(1000, 2, FailMode.FAIL_CLOSED, 100);
        p2.getBan().setEnabled(true);
        p2.getBan().setThresholdWindows(1);
        p2.getBan().setLevelDuration(List.of(Duration.ofMinutes(10)));
        h2.publish(p2);
        Decision afterRestart = h2.req("r-1", "10.77.77.77");
        assertEquals(DecisionReason.BANNED, afterRestart.reason(), "重启后封禁状态必须延续");
        assertTrue(afterRestart.banUntil() > clock.nowMillis());
        log.info("ban survived restart: level={} until={}", afterRestart.banLevel(),
                afterRestart.banUntil());

        // 人工解除后再次重启：不能又变回封禁
        h2.banService.unban("10.77.77.77");
        Harness h3 = new Harness(clock, dir);
        var p3 = TestProps.create(1000, 2, FailMode.FAIL_CLOSED, 100);
        p3.getBan().setEnabled(true);
        p3.getBan().setThresholdWindows(1);
        p3.getBan().setLevelDuration(List.of(Duration.ofMinutes(10)));
        h3.publish(p3);
        Decision afterUnbanRestart = h3.req("r-2", "10.77.77.77");
        assertNotEquals(DecisionReason.BANNED, afterUnbanRestart.reason(),
                "人工解除在重启后必须保持，不能重新变为封禁");
        log.info("manual unban survived restart: reason={}", afterUnbanRestart.reason());
    }

    private static Path newTempDir() {
        try {
            return java.nio.file.Files.createTempDirectory("rl-restart-");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Path parentDirOf(Path file) {
        return file.getParent();
    }

    @Test
    void tieredPolicyConfigItselfIsRestoredAfterRestart() {
        ManualClock clock = new ManualClock(60_000L);
        Path dir = newTempDir();
        Harness h = new Harness(clock, dir);
        var p = TestProps.create(1000, 100, FailMode.FAIL_CLOSED, 100);
        List<RateLimitProperties.Tier> tiers = new ArrayList<>();
        tiers.add(new RateLimitProperties.Tier(LimitDimension.IP, Duration.ofSeconds(1), 10));
        tiers.add(new RateLimitProperties.Tier(LimitDimension.IP, Duration.ofSeconds(10), 50));
        tiers.add(new RateLimitProperties.Tier(LimitDimension.GLOBAL, Duration.ofSeconds(1), 200));
        p.getPolicy().setTiers(tiers);
        p.getPolicy().setWeights(java.util.Map.of("/api/export", 7L));
        long v1 = h.publish(p);
        assertEquals(1, v1);

        // 热更新一次 -> 版本 2 并持久化
        p.getPolicy().getTiers().get(0).setLimit(11);
        long v2 = h.service.reload(p);
        assertEquals(2, v2);

        // 重启：不传任何策略，仅通过 startupProperties() 从文件恢复；应得到三层、权重、版本 2
        RateLimitProperties restored = h.service.startupProperties(
                TestProps.create(1000, 1, FailMode.FAIL_CLOSED, 100)); // 假装的默认配置，应被忽略
        assertEquals(3, restored.getPolicy().getTiers().size(), "分层配置必须跨重启恢复");
        assertEquals(11, restored.getPolicy().getTiers().get(0).getLimit());
        assertEquals(10_000L, restored.getPolicy().getTiers().get(1).getWindow().toMillis());
        assertEquals(LimitDimension.GLOBAL, restored.getPolicy().getTiers().get(2).getDimension());
        assertEquals(7L, restored.getPolicy().getWeights().get("/api/export"));

        // 重启后的服务首发沿用版本 2，配额命名空间与持久化计数一致
        Harness h2 = new Harness(clock, dir);
        RateLimitProperties auto = h2.service.startupProperties(
                TestProps.create(1000, 1, FailMode.FAIL_CLOSED, 100));
        long restoredVersion = h2.service.publishInitial(auto);
        assertEquals(2, restoredVersion, "重启后版本号沿用热更新后的 2");
        assertEquals(2, h2.viewService.view(LimitDimension.IP, "10.200.200.200").tiers().size(),
                "重启后 IP 维度应有两层（短/长），证明分层规则已恢复");
        assertEquals(1, h2.viewService.view(LimitDimension.GLOBAL, "global").tiers().size(),
                "重启后 GLOBAL 层规则也应恢复");
        log.info("tiered policy restored after restart: tiers=3 version=2");
    }
}
