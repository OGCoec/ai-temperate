# AI Temperate Android PCM Recorder

该 UTS 插件使用 Android `AudioRecord` 直接输出 `16 kHz / mono / PCM S16LE`，每个回调最多约 100 ms 音频，并由公共语音会话模块作为真正的 WebSocket 二进制帧发送。

插件依赖 HBuilderX 4.51 或更高版本，因为 Android VDOM UTS 插件从该版本开始支持将原生 `ByteBuffer` 通过 `ArrayBuffer.fromByteBuffer()` 返回到 Vue/JavaScript 层。修改插件或权限后必须制作包含本插件的自定义基座，不能继续使用未包含该原生能力的旧基座。

插件不编码 MP3、AAC 或 Base64，不保存录音文件，也不持有网络连接；页面进入后台或语音会话结束时由调用方执行 `stop()`。
