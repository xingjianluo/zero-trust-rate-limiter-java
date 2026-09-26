package com.github.xingjianluo.zerotrustratelimiterjava.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Protected demo endpoints used for local verification of the rate limiter. */
@RestController
@RequestMapping("/api")
public class DemoController {

    @GetMapping("/hello")
    public Map<String, Object> hello(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "ok");
        body.put("requestId", request.getHeader("X-Request-Id"));
        body.put("path", request.getRequestURI());
        return body;
    }

    @GetMapping("/orders")
    public Map<String, Object> orders(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "orders");
        body.put("requestId", request.getHeader("X-Request-Id"));
        return body;
    }
}
