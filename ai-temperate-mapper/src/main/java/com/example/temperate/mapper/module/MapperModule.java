package com.example.temperate.mapper.module;

/**
 * 标记 mapper 模块的根类型。
 *
 * <p>该模块只承载数据库访问契约，不包含业务编排、事务决策或 Spring Boot 启动逻辑。</p>
 */
public final class MapperModule {

    private MapperModule() {
    }
}
