# 普通用户头像 OSS 部署要求

普通用户头像使用 Bucket `ihaveaplan`，默认地域为美国硅谷 `us-west-1`。应用只通过阿里云 SDK 标准环境变量读取凭据，不把 AccessKey 写入 YAML、日志或数据库。

需要提供：

```text
ALIYUN_OSS_AVATAR_BUCKET=ihaveaplan
ALIYUN_OSS_AVATAR_REGION=us-west-1
ALIYUN_OSS_AVATAR_ENDPOINT=https://oss-us-west-1.aliyuncs.com
ALIYUN_OSS_AVATAR_PUBLIC_BASE_URL=https://ihaveaplan.oss-us-west-1.aliyuncs.com
OSS_ACCESS_KEY_ID=...
OSS_ACCESS_KEY_SECRET=...
OSS_SESSION_TOKEN=...
```

`OSS_SESSION_TOKEN` 仅在使用 STS 临时凭据时提供。

Bucket ACL 保持“公共读”，不能开启“公共读写”。写入、复制和删除都必须使用服务端 RAM 身份或预签名 PUT 授权。RAM 权限至少覆盖目标 Bucket 中下列前缀的 `PutObject`、`GetObject`、`GetObjectMeta`、`DeleteObject`：

```text
ai-temperate/user/temp/*
ai-temperate/user/*
```

H5 直接 PUT 还需要在 OSS 配置 CORS：

- Allowed Origin：实际 H5 生产域名与受控开发域名。
- Allowed Method：`PUT`。
- Allowed Header：`Content-Type`、`x-oss-object-acl`、`x-oss-forbid-overwrite` 以及阿里云签名要求的请求头。
- Expose Header：可以包含 `ETag`。
- 禁止把生产 CORS Origin 设置为 `*` 并同时携带浏览器凭据。

为 `ai-temperate/user/temp/` 配置一天后过期的 OSS Lifecycle，用于回收浏览器直接关闭等未发送取消请求的临时对象。正式头像只在 `user_profile.avatar_url` 保存公开地址；应用不持久化 Object Key，也不维护删除重试表，因此用户更换头像后遗留的旧正式对象属于当前明确接受的存储成本。
