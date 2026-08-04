# 第三方许可补充说明

## AVIF ImageIO Native Reader

- 依赖坐标：`io.github.nemanjastokuca:avif-imageio-native-reader:0.1.0`
- 使用目的：在 Java 21 Windows x64 运行环境中完整解码并验证静态 AVIF 模型图标。
- 上游许可：LGPL-3.0。
- 项目边界：应用只通过标准 ImageIO SPI 调用该依赖，不修改其源码；发布应用及其依赖时必须同时保留上游版权、许可证文本和重新链接所需条件。

该依赖包含平台原生组件。部署前必须由发布流程复核实际分发包内的上游许可证文件、Windows x64 原生库和 Java 21 兼容性；应用启动阶段会检查 AVIF Reader 是否成功注册，缺失时拒绝启动。

## ph-css

- 依赖坐标：`com.helger:ph-css:8.2.1`
- 使用目的：把可信官方 SVG 中的 `<style>` 和行内 `style` 解析为 CSS AST，再执行选择器、媒体查询、属性和值的显式白名单校验。
- 上游许可：Apache License 2.0。
- 项目边界：该依赖只负责语法解析；是否允许某个 CSS 节点、属性或资源引用仍由项目安全策略决定，禁止把“解析成功”当作“内容安全”。

## qrcode-generator

- 依赖坐标：`qrcode-generator@1.4.4`
- 使用目的：在普通用户 TOTP 设置页面中把后端返回的 `otpauth` URI 本地渲染为 SVG 二维码。
- 上游许可：MIT。
- 项目边界：二维码生成完全在当前 H5 或 Android WebView 进程内完成；`otpauth` URI 和 Base32 密钥不得发送给第三方二维码服务、日志或持久化存储。
