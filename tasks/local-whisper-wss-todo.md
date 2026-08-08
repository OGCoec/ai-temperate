# 本地 Whisper Medium 实时语音转写任务清单

## 第一阶段：代码交付

- [x] Python WSS 固定监听 `127.0.0.1:7896`，使用 Medium、CUDA 和 `int8_float16`。
- [x] 实现五分钟硬上限、单活动会话、滚动 partial 与完整 final。
- [x] 实现 Java 一次性票据、Redis HMAC Key、双维度限流和原子单次消费。
- [x] 实现 Java `6655` WSS 网关、Origin 校验、TLS 上游、背压和关闭传播。
- [x] 实现 H5 AudioWorklet 采集、16 kHz PCM 转换与二进制帧发送。
- [x] 实现 Android UTS AudioRecord 原生录音插件。
- [x] 在聊天输入区加入麦克风、临时预览、五分钟提示和 final 追加。
- [x] 编写 Python、Java 和前端测试代码。
- [x] 更新 CSP、Permissions-Policy、Android 权限和项目配置。

## 第二阶段：安全测试结果

- [x] 使用标准库 `unittest` 运行 Python 全套测试，共十四项通过；当前虚拟环境未安装 pytest。
- [x] 编译 Java 六模块并运行本次语音及安全依赖定向测试，共三十六项通过。
- [x] 运行前端语音契约测试，共九项通过。
- [x] 启动最新 Python WSS，并完成 Java 21 客户端到 Python 的受信任 TLS/协议联调。
- [ ] 全仓库 Java 测试仍有十九个与语音无关的既有失败套件，需要作为独立修复任务处理。
- [ ] 使用 H5 麦克风完成中文、英文和中英混合验收。
- [ ] 使用 Android 真机与自定义基座完成同等验收。
- [x] 使用真实 `新录音.m4a` 验证 partial/final，并由 `nvidia-smi` 记录 RTX 4070 峰值利用率百分之九十。
- [x] 检查最新 Python 服务日志，不包含音频、转写正文、票据、Token 或完整设备标识。
- [ ] 重启加载新代码的 Java 6655 进程后，验证公开 WSS、一次性票据和 H5 输入框全链路。
