package com.example.temperate.service.registration.flow.domain;

import com.example.temperate.common.security.hmac.HmacIdentifier;

/**
 * 表示注册流程用于风控和会话绑定的受保护参与者标识。
 */
public record RegistrationActor(HmacIdentifier actorId, HmacIdentifier globalDeviceHash) {
}
