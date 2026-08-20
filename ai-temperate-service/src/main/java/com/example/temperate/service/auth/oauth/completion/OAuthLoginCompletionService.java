package com.example.temperate.service.auth.oauth.completion;

import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowAccess;

/**
 * 定义 OAuth 身份与手机号证明齐备后创建正式会话或 TOTP 挑战的唯一完成入口。
 */
public interface OAuthLoginCompletionService {

    LoginResult complete(OAuthFlowAccess access);
}
