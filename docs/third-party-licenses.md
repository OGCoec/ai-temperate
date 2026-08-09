# 第三方许可补充说明

## thinking-orbs Canvas engine

- 上游项目：`Jakubantalik/thinking-orbs@0.2.0`，固定源码修订 `8157b726c35712acba57f5d72149c2d33b5f0fd6`。
- 使用目的：在 uni-app H5 与 Android App-Vue 中本地渲染 AI 录音、推理、联网搜索和整理状态的 2D Canvas Orb。
- 上游许可：MIT，版权归 Jakub Antalik 所有。
- 项目边界：项目仅采用其状态预设与 2D Canvas 绘制算法，去除 React 组件层并通过 renderjs 驱动；分发时保留本说明和上游 MIT 许可文本。

上游 MIT 许可文本：

> MIT License
>
> Copyright (c) 2026 Jakub Antalik
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.

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
