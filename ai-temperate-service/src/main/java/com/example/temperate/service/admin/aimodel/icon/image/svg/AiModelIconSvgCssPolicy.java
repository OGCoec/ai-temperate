package com.example.temperate.service.admin.aimodel.icon.image.svg;

/**
 * 定义可信官方 SVG 中受限 CSS 的结构化安全验证边界。
 *
 * <p>实现必须先把样式解析为 CSS AST，再对白名单节点、选择器、媒体查询、属性和值逐项验证；
 * 禁止仅用正则判断整段 CSS 或把“语法可解析”直接视为安全。</p>
 */
public interface AiModelIconSvgCssPolicy {

    /**
     * 解析并验证完整的 SVG {@code <style>} 内容。
     */
    void validateStyleSheet(String css);

    /**
     * 解析并验证单个 SVG {@code style} 属性中的声明列表。
     */
    void validateDeclarationList(String css);
}
