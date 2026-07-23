package com.example.temperate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ai-temperate Web 应用的 Spring Boot 启动入口。
 *
 * <p>用途：启动 Web 层并装配下层服务、持久化、缓存和安全配置。</p>
 */
@SpringBootApplication
public class AiTemperateApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiTemperateApplication.class, args);
    }
}
