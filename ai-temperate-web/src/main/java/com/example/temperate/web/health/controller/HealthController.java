package com.example.temperate.web.health.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用存活状态的轻量 HTTP 健康检查控制器。
 *
 * <p>用途：确认 Spring Boot 进程能够响应 HTTP 请求；不执行数据库、Redis 或 RabbitMQ 深度连通性检查，
 * 也不返回用户、凭据或基础设施敏感信息。</p>
 */
@RestController
@RequestMapping("/api")
@Tag(
        name = "系统-健康检查",
        description = "提供应用存活状态检查接口，仅用于确认 Spring Boot 进程是否能够正常响应 HTTP 请求；该接口不执行数据库、Redis 或 RabbitMQ 深度连通性检查，也不返回任何用户、凭据或基础设施敏感信息。")
public class HealthController {

    @GetMapping("/health")
    @Operation(summary = "检查应用存活状态")
    public Map<String, String> health() {
        return Map.of(
                "application", "ai-temperate",
                "status", "UP");
    }
}
