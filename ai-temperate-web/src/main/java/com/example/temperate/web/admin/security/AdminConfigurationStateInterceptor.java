package com.example.temperate.web.admin.security;

import com.example.temperate.service.admin.config.AdminConfigurationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 在管理员 Controller 之前按隐藏配置状态关闭或开放首次注册、登录和受保护接口。
 *
 * <p>状态接口、PreAuth/WebRTC 闭环、手机号国家建议与公开 hCaptcha 资源始终可访问；任何半配置或损坏
 * 状态都 Fail Closed，不能仅因 YAML 缺失而重新开放初始化。本拦截器只负责配置状态，不负责管理员会话认证。</p>
 */
@Component
public final class AdminConfigurationStateInterceptor implements HandlerInterceptor {

    private static final String ADMIN_PREFIX = "/api/admin";
    private static final String STATE_PATH = "/api/admin/auth/state";
    private static final String PREAUTH_PATH = "/api/admin/_edge/pre-auth";
    private static final String CHALLENGE_PATH = "/api/admin/_edge/risk-challenge";
    private static final String WEBRTC_START_PATH = "/api/admin/_edge/webrtc/start";
    private static final String WEBRTC_REPORT_PATH = "/api/admin/_edge/webrtc/report";
    private static final String PHONE_COUNTRY_PATH = "/api/admin/auth/phone-country";
    private static final String HCAPTCHA_CONFIG_PATH = "/api/admin/auth/hcaptcha/config";
    private static final String HCAPTCHA_PAGE_PATH = "/api/admin/auth/hcaptcha/page";
    private static final String HCAPTCHA_PAGE_STYLE_PATH =
            "/api/admin/auth/hcaptcha/page.css";
    private static final String HCAPTCHA_PAGE_SCRIPT_PATH =
            "/api/admin/auth/hcaptcha/page.js";
    private static final String REGISTER_PREFIX = "/api/admin/auth/register";

    private final AdminConfigurationService configurationService;

    public AdminConfigurationStateInterceptor(
            AdminConfigurationService configurationService) {
        this.configurationService = Objects.requireNonNull(configurationService);
    }

    /**
     * 在进入管理员 Controller 前执行配置状态门，并让受控异常进入统一 MVC 异常处理链。
     *
     * <p>注册路径只能在未初始化状态访问，其余受保护管理员路径只能在激活状态访问；这里保留内部路径判断，
     * 即使未来 MVC 注册范围被误改，也不会把非管理员请求交给管理员配置状态机。</p>
     */
    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        String path = path(request);
        if (!path.startsWith(ADMIN_PREFIX) || isPublicPath(path)) {
            return true;
        }
        if (path.startsWith(REGISTER_PREFIX)) {
            configurationService.requireUninitialized();
        } else {
            configurationService.requireActive();
        }
        return true;
    }

    private static boolean isPublicPath(String path) {
        return STATE_PATH.equals(path)
                || PREAUTH_PATH.equals(path)
                || CHALLENGE_PATH.equals(path)
                || WEBRTC_START_PATH.equals(path)
                || WEBRTC_REPORT_PATH.equals(path)
                || PHONE_COUNTRY_PATH.equals(path)
                || HCAPTCHA_CONFIG_PATH.equals(path)
                || HCAPTCHA_PAGE_PATH.equals(path)
                // 页面子资源必须在管理员配置状态尚未建立时可加载，但只允许两个精确静态路径。
                || HCAPTCHA_PAGE_STYLE_PATH.equals(path)
                || HCAPTCHA_PAGE_SCRIPT_PATH.equals(path);
    }

    private static String path(HttpServletRequest request) {
        String context = request.getContextPath();
        String uri = request.getRequestURI();
        return context == null || context.isEmpty()
                ? uri
                : uri.substring(context.length());
    }
}
