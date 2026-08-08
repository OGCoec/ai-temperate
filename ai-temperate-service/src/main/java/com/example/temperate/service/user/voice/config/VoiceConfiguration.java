package com.example.temperate.service.user.voice.config;

import java.security.SecureRandom;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配语音转写的强随机票据生成器和类型安全配置。
 */
@Configuration
@EnableConfigurationProperties(VoiceProperties.class)
public class VoiceConfiguration {

    @Bean
    @Qualifier("voiceTicketSecureRandom")
    SecureRandom voiceTicketSecureRandom() {
        return new SecureRandom();
    }
}
