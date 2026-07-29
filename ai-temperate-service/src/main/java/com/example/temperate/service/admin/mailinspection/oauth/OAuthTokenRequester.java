package com.example.temperate.service.admin.mailinspection.oauth;

import reactor.core.publisher.Mono;

/**
 * 隔离实际 WebClient OAuth 请求，使有限重试和错误分类可以在无网络测试中验证。
 */
@FunctionalInterface
interface OAuthTokenRequester {

    Mono<OAuthTokenHttpResponse> request(OAuthTokenRequest request);
}
