package com.github.xingjianluo.zerotrustratelimiterjava.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 受限流保护的示例业务端点。具体请求标识见响应头 X-Request-Id。 */
@RestController
@RequestMapping("/api")
public class DemoController {

    @GetMapping("/hello")
    public Map<String, Object> hello() {
        return Map.of("message", "ok");
    }
}
