package com.example.temperate.service.auth.login.service;

import com.example.temperate.service.auth.login.dto.command.LoginCommand;
import com.example.temperate.service.auth.login.dto.result.LoginResult;

/**
 * 定义密码登录的认证、风控和会话签发业务边界。
 */
public interface LoginService {

    LoginResult login(LoginCommand command);
}
