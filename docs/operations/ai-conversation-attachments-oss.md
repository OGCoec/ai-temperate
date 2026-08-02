# AI 会话附件 OSS 运维要求

AI 会话附件与头像共用阿里云 OSS 凭据，但使用独立前缀和业务配置。应用只通过环境变量取得 OSS SDK 凭据；源码、YAML、浏览器状态和日志均不得保存 AccessKey、Secret 或预签名 URL。

## 对象布局

临时对象固定为：

```text
ai-temperate/conversations/temp/
  {userPublicId}/{uploadSessionId}/{attachmentId}.{ext}
```

正式对象固定为：

```text
ai-temperate/conversations/
  {userPublicId}/{conversationPublicId}/{messagePublicId}/{attachmentId}.{ext}
```

临时对象由预签名 PUT 显式设置为 `private`、禁止覆盖，并把客户端声明的 `Content-Length` 纳入签名。H5 由浏览器根据请求体自动写入该受保护请求头；Android 使用固定长度流模式。服务端仍必须在进入模型调用前通过 HEAD 复核实际大小与 Content-Type，签名约束不能替代业务校验。正式对象在消息落盘前由服务端复制或上传，并显式设置为 `public-read` 与不可变缓存。数据库只保存正式公网 URL；临时 Object Key、临时签名 URL和文件内容不得进入 PostgreSQL、Redis 或日志。

## 客户端预上传状态与放弃对象

H5 和 Android 在用户选择文件后立即调用一次批量预签名接口，不等待发送按钮。每条消息最多八个附件，
单文件最多 100 MiB、合计最多 200 MiB；所有选择批次共享最多三个并发 PUT。每个文件在客户端内存中
依次经历 `PREPARING -> UPLOADING -> UPLOADED`，失败进入 `FAILED`，并单独展示进度和重试入口。

网络错误以及 408、429、5xx 上传响应只自动重试一次。自动重试会为失败文件单独申请新的预签名地址；
本地文件变化、用户取消、格式或大小校验失败不自动重试。手动重试同样只处理对应失败文件，不重新上传
已经成功的附件。预签名 URL、临时 Object Key 和上传凭据只存在于运行时内存。

删除附件或页面卸载时，H5 调用 `XMLHttpRequest.abort()`，Android 关闭当前上传连接。项目不提供临时附件
删除 API：已经完成上传但被用户移除、替换或放弃发送的对象不再被消息引用，统一由一天 Lifecycle 清理。
因此一天 Lifecycle 是必须配置的回收边界，不能依赖客户端取消保证对象一定不存在。

发送按钮只有在模型已选择、存在文字或附件、全部附件上传成功、媒体附件受当前模型支持且当前没有生成中
回答时才启用。发送直接复用内存中的上传引用，不重新申请签名或执行第二次 PUT。图片、音频和视频即使
暂时不受当前模型支持也继续上传；切换到具备对应能力的模型后，客户端自动解除兼容性门禁。

## 必须配置的 Bucket 策略

为 `ai-temperate/conversations/temp/` 配置一天后删除的 OSS Lifecycle。追加 Redis 会话内容不会延长 OSS 临时对象生命周期；临时对象过期后，上下文构建器只忽略失效媒体，仍保留对应文字。

H5 直接 PUT 需要为实际普通用户站点配置 OSS CORS：

- 方法只允许 `PUT`、`HEAD` 和浏览器预检所需的 `OPTIONS`。
- Origin 只允许已部署的普通用户 H5 域名，不使用通配符。
- 允许的请求头必须覆盖预签名响应返回的 Header，例如 `Content-Type` 和 OSS 签名要求的 Header；`Content-Length` 由浏览器管理，前端脚本不得尝试手工覆盖。
- 不向浏览器暴露 OSS AccessKey，也不允许公共写。

正式前缀不得配置一天 Lifecycle。当前正式对象位于公共读 Bucket，知道完整 URL 即可读取；这是本期明确接受的产品边界。未来切换 CDN 域名或私有下载授权时，需要迁移历史消息 JSONB 中的完整 URL。

## 上线与回滚

上线顺序固定为：执行字段迁移、创建并验证并发索引、配置 CORS 与一天 Lifecycle、部署后端、部署 Worker、发布 H5/Android。迁移、真实 OSS 写入和 Worker 部署均需要独立授权。

回滚应用版本不会自动删除已经生成的正式对象。数据库事务失败时服务端会尽力删除本轮新对象；清理失败只通过低基数指标暴露，不宣称 OSS 与 PostgreSQL 原子一致。
