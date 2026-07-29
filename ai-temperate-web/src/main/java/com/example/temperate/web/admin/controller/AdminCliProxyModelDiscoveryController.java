package com.example.temperate.web.admin.controller;

import com.example.temperate.service.admin.aimodel.discovery.dto.CliProxyModelDiscoveryResult;
import com.example.temperate.service.admin.aimodel.discovery.service.CliProxyModelDiscoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供受管理员会话和既有风险链保护的 CLIProxyAPI 当前模型只读发现接口。
 *
 * <p>该 Controller 不接受上游地址、密钥或请求体，也不执行模型创建、缓存或数据库写入。</p>
 */
@RestController
@RequestMapping("/api/admin/ai-model-sources/cli-proxy")
@Tag(
        name = "管理员-AI 模型发现",
        description = "供已登录管理员通过后端读取 CLIProxyAPI 当前模型并匹配本地目录；接口沿用管理员会话、设备和风险校验，不暴露上游地址或密钥，也不自动创建模型。")
public class AdminCliProxyModelDiscoveryController {

    private final CliProxyModelDiscoveryService discoveryService;

    public AdminCliProxyModelDiscoveryController(
            CliProxyModelDiscoveryService discoveryService) {
        this.discoveryService = Objects.requireNonNull(discoveryService);
    }

    @GetMapping("/models")
    @Operation(
            summary = "读取 CLIProxyAPI 当前模型并匹配本地目录",
            description = "请求不包含正文；输入和输出倍率仅来自本地 AI 模型配置，未登记模型的倍率保持为空。")
    public ResponseEntity<CliProxyModelDiscoveryResult> models() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(discoveryService.discoverModels());
    }
}
