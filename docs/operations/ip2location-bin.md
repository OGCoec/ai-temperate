# IP2Location LITE BIN 部署与维护

## 用途与边界

认证页面使用 IP2Location LITE DB11 IPv6 BIN，根据客户端 IP 提供手机号国家或地区的默认建议。服务端只读取 ISO2 国家代码，不向客户端返回 IP、城市、地区、经纬度或置信度。该结果不是精确定位，用户始终可以手动修改。

本功能不调用 IP2Location 在线接口，不读取或写入 PostgreSQL、Redis、RabbitMQ。

## 文件准备

1. 从 [IP2Location LITE DB11 官方页面](https://lite.ip2location.com/database/db11-ip-country-region-city-latitude-longitude-zipcode-timezone?lang=en_US) 获取 IPv6 BIN。
2. 将 BIN 放在应用 JAR 外部的只读目录，不要提交到代码仓库。
3. 使用 PowerShell 记录文件摘要，方便部署核对和回滚：

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'D:\data\ip2location\IP2LOCATION-LITE-DB11.IPV6.BIN'
```

4. 应用进程账号只需要读取权限，不需要修改权限。

## 环境变量

```text
AUTH_PHONE_COUNTRY_ENABLED=true
AUTH_PHONE_COUNTRY_BIN_PATH=D:\data\ip2location\IP2LOCATION-LITE-DB11.IPV6.BIN
AUTH_PHONE_COUNTRY_TRUSTED_PROXY_RANGES=127.0.0.1/32,::1/128
```

- `AUTH_PHONE_COUNTRY_BIN_PATH`：生产环境必须使用外部绝对路径。
- `application-local-https.yml` 默认只信任同机 cloudflared 的 IPv4/IPv6 回环连接，即 `127.0.0.1/32,::1/128`；环境变量只用于部署拓扑确实不同时覆盖。
- `AUTH_PHONE_COUNTRY_TRUSTED_PROXY_RANGES`：只填写由本方管理、会规范写入真实客户端头的代理地址，应该精确到单机 `/32` 或 `/128`。
- 主配置保持空可信列表；不配置可信代理时，服务端忽略全部转发头，且只接受直接连接的公网地址。
- 禁止信任 `0.0.0.0/0`、`::/0`、整个局域网、Cloudflare 客户端可以直接连接的地址或其他不受本方控制的来源。
- 配置非法 CIDR 会使应用启动失败；BIN 缺失或不可读不会阻止启动，接口将返回 `resolved=false`。

## 客户端 IP 信任边界

只有连接对端命中可信代理 CIDR 时，服务端才会读取真实客户端头，固定优先级如下：

1. `CF-Connecting-IP`：Cloudflare Tunnel 场景的权威来源。
2. `X-Forwarded-For`：仅在 Cloudflare 头完全缺失时兼容；从右向左跳过可信代理并在第一个非可信地址处停止。
3. `X-Real-IP`：仅在前两个头都缺失时作为最终兼容来源。

权威头存在但格式非法或不是公网地址时，请求立即识别失败，不会降级到其他头。XFF 中出现非法字面量，或者安全边界候选属于回环、RFC1918 私网、CGNAT、链路本地、`198.18.0.0/15` Fake-IP、文档、组播或保留地址时，也会直接失败。可信代理没有有效头时不会回退查询 cloudflared 的 `127.0.0.1` 或局域网地址。

VPN、Clash 或其他代理存在时，`CF-Connecting-IP` 表示 Cloudflare 实际看到的公网出口 IP。家庭公网 IP 已被上游代理隐藏时，应用无法也不应该绕过代理恢复它，因此国家建议会对应 VPN/代理出口。

客户端 IP 属于隐私数据。应用、代理和排障脚本禁止记录、缓存或响应完整 IP；接口仍只返回 `resolved` 和 `countryIso2`。

## 识别失败行为

后端无法得到有效公网地址或 IP2Location 无结果时，返回 HTTP 200、`resolved=false` 和 `countryIso2=null`。前端随后尝试设备明确地区；设备地区缺失、非法或不在国家列表时保持空选择，由现有表单要求用户手动选择，不再固定回退到 `cn-86`。如果设备地区本身明确为 `CN`，仍会按设备地区选择中国。

## 部署和验证顺序

1. 停止应用进程。
2. 将经过摘要核对的 BIN 放到目标目录。
3. 设置环境变量并启动应用。
4. 确认日志出现一次 BIN 已加载信息，且日志中没有完整客户端 IP。
5. 从经过配置的反向代理访问 `GET /api/auth/phone-country`，确认响应只有 `resolved` 和 `countryIso2`。
6. 分别使用公网头、缺失头、私网头和 Fake-IP 头验证；后三者必须返回 `resolved=false`，且不得进入 IP2Location 查询。
7. 从非可信来源伪造 `CF-Connecting-IP`、XFF 或 `X-Real-IP`，确认转发头全部被忽略。

## 更新与回滚

IP2Location 客户端使用内存映射读取 BIN。Windows 环境不得在应用运行期间覆盖正在使用的文件。

更新步骤：

1. 下载并校验新 BIN，将当前文件保留为带日期的外部备份。
2. 停止应用。
3. 原子替换目标 BIN，文件名和环境变量路径保持不变。
4. 启动应用并验证已知 IPv4、IPv6 和认证页面国家回显。

回滚步骤：

1. 停止应用。
2. 恢复上一个已验证摘要的 BIN。
3. 重新启动并复查接口。

本轮不实现运行期热更新；每次替换 BIN 后都必须重启应用。

## 许可署名

This product includes IP2Location LITE data available from [https://lite.ip2location.com](https://lite.ip2location.com).
