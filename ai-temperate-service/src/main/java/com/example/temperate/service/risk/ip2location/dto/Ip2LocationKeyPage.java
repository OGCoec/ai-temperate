package com.example.temperate.service.risk.ip2location.dto;

import java.util.List;

/**
 * 返回有界管理员 Key 列表和下一页游标，避免一次读取整个 Redis Hash。
 */
public record Ip2LocationKeyPage(
        long nextCursor,
        List<Ip2LocationKeyView> items) {
}
