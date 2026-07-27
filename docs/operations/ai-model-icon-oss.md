# AI 模型图标 OSS 运维说明

模型图标本地上传使用独立的 `ai-model-icon.oss` 配置组。应用通过阿里云 OSS SDK 的标准环境变量读取凭据，YAML 不保存 AccessKey。Bucket 必须保持公共读、禁止公共写；上传对象显式设置 `public-read`，并使用 `Cache-Control: public, max-age=0, must-revalidate`，保证固定 Object Key 被覆盖后客户端会重新验证内容。

部署环境需要设置或确认：

- `ALIYUN_OSS_MODEL_ICON_BUCKET`
- `ALIYUN_OSS_MODEL_ICON_REGION`
- `ALIYUN_OSS_MODEL_ICON_ENDPOINT`
- `ALIYUN_OSS_MODEL_ICON_PUBLIC_BASE_URL`
- `ALIYUN_OSS_MODEL_ICON_PREFIX`，默认 `ai-temperate/models/icons/`

Object Key 由应用根据图标显示名称生成小写 ASCII Slug，并根据真实解码格式添加 `.png`、`.jpg`、`.webp`、`.gif`、`.ico`、`.avif` 或 `.svg` 后缀。原始文件名、客户端 Content-Type 和扩展名都不参与最终路径判断。只修改显示名称不会移动现有对象；下次替换本地文件时才按最新名称与真实格式生成路径。SVG 上传的是安全 DOM 重新序列化后的字节，不是原始请求字节；其他允许格式保留通过完整解码验证的原文件。

外部 HTTPS 图片不会复制到 OSS，`ai_model_icon.object_key` 保存 `NULL`。应用登记前使用有界 GET 验证最终 URL、每一跳公网 DNS、响应类型、大小和真实图片内容；外部 SVG 也执行同一安全检查，但只保存最终 URL。外部站点日后替换内容或失效属于直接外链方案的已接受风险。

## 官方 SVG 外链兼容配置

外部 SVG 完成全部重定向后，应用才根据最终主机选择安全档位。命中八家官方主机时，可以兼容受限 `<style>`、行内样式和内嵌 PNG/JPEG/WebP Data URI；未命中、关闭总开关、本地上传或共享 CDN 的其他租户都继续使用严格档位。兼容档位仍拒绝脚本、事件、动画、外部子资源、危险 CSS、重复或悬空本地引用以及资源上限越界。

部署环境可以覆盖：

- `AI_MODEL_ICON_TRUSTED_OFFICIAL_SVG_ENABLED`：默认 `true`；设置为 `false` 可立即回退到严格档位。
- `AI_MODEL_ICON_TRUSTED_OPENAI_HOSTS`
- `AI_MODEL_ICON_TRUSTED_ANTHROPIC_HOSTS`
- `AI_MODEL_ICON_TRUSTED_GOOGLE_HOSTS`
- `AI_MODEL_ICON_TRUSTED_XAI_HOSTS`
- `AI_MODEL_ICON_TRUSTED_DEEPSEEK_HOSTS`
- `AI_MODEL_ICON_TRUSTED_ZHIPU_HOSTS`
- `AI_MODEL_ICON_TRUSTED_MOONSHOT_HOSTS`
- `AI_MODEL_ICON_TRUSTED_QWEN_HOSTS`

主机列表使用逗号分隔，只允许 DNS 主机名，不允许协议、路径、端口、通配符或 IP 字面量。匹配采用完整主机或点边界子域规则；跨厂商重叠配置会导致应用启动失败。共享 CDN 必须精确配置到实际主机，例如 Google 默认使用 `www.gstatic.com`，不得扩大成整个 `gstatic.com`。加入共享 CDN 前还必须确认该主机不会让其他租户上传任意 SVG；不能确认时应保持严格档位。

外部官方 URL 登记成功后只写入最终 `icon_url`，`object_key` 仍为 `NULL`，不会调用 OSS。由于浏览器后续直接加载该 URL，官方站点日后替换成危险或失效内容不会自动触发重新验证；这是直接外链方案明确接受的时效风险，不应把官方主机兼容档位描述为持续内容担保。

运行环境固定为 Java 21 Windows x64。WebP 使用 TwelveMonkeys `imageio-webp`，ICO 使用 TwelveMonkeys `imageio-bmp`，AVIF 使用 `io.github.nemanjastokuca:avif-imageio-native-reader:0.1.0`。启动时必须确认 AVIF ImageIO Reader 已注册，并真实解码内置的 2×2 静态探针；SPI、JNI 或原生库任一不可用时应用都会拒绝启动。发布包必须保留 `docs/third-party-licenses.md` 记录的 LGPL-3.0 上游许可义务。

数据库和 OSS 之间没有分布式事务。若应用在补偿删除前中断，可能留下不再被数据库引用的对象。排查时应以 `ai_model_icon.object_key` 的非空集合为数据库引用基准，先导出并人工核对差集，再删除确认无引用的 OSS 对象；禁止根据文件名猜测或执行无边界目录删除。模型图标删除返回 409 时，必须先为所有引用模型替换或清除 `icon_id`，不得绕过引用检查直接删除数据库行。
