package com.example.temperate.service.user.apichat;

import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import reactor.core.publisher.Flux;

/**
 * 该服务是来编排公开 Chat Completions 的验证、并发、预扣、8317 流、Usage 结算、取消补偿与租约释放。
 */
public interface ApiChatCompletionService {

    Flux<String> stream(ApiKeyPrincipal principal, ApiChatRequest request);
}
