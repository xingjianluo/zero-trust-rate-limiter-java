package com.github.xingjianluo.zerotrustratelimiterjava.web;

import com.github.xingjianluo.zerotrustratelimiterjava.config.RateLimitProperties;
import com.github.xingjianluo.zerotrustratelimiterjava.core.BanService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitService;
import com.github.xingjianluo.zerotrustratelimiterjava.model.BanView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端：策略热更新、封禁查询与人工解除。路径位于 /admin/**，
 * 不经过限流过滤器，因此即使在降级/封禁状态下人工操作仍可立即执行。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final RateLimitService rateLimitService;
    private final BanService banService;

    public AdminController(RateLimitService rateLimitService, BanService banService) {
        this.rateLimitService = rateLimitService;
        this.banService = banService;
    }

    /** 查询当前策略版本（热更新边界可由此观察）。 */
    @GetMapping("/policy/version")
    public Map<String, Object> version() {
        return Map.of("policyVersion", rateLimitService.currentVersion());
    }

    /**
     * 热更新整份策略配置，原子生效于之后的新窗口/新请求。
     *
     * @return 新版本号
     */
    @PostMapping("/policy/reload")
    public Map<String, Object> reload(@RequestBody RateLimitProperties properties) {
        long newVersion = rateLimitService.reload(properties);
        return Map.of("policyVersion", newVersion, "status", "applied");
    }

    /** 列出全部来源的封禁状态。 */
    @GetMapping("/bans")
    public List<BanView> bans() {
        return banService.listBans();
    }

    /** 人工解除指定来源封禁：立即清零并持久化。 */
    @PostMapping("/bans/{key}/unban")
    public Map<String, Object> unban(@PathVariable String key) {
        banService.unban(key);
        return Map.of("key", key, "status", "unbanned");
    }
}
