# Lumen Music 本地服务

这是一个仅使用 Python 标准库的本地 HTTP 服务，要求 Python 3.10 或更高版本。服务只绑定 `127.0.0.1`。默认使用 B 站网页端公开接口搜索视频元数据，并以游客身份和 WBI 签名解析音频流；`DemoProvider` 保留给离线开发与测试。

## 运行

在 `service` 目录执行：

```powershell
python -m lumen_service --port 8765 --provider bilibili
```

省略 `--provider` 时默认使用 `bilibili`。离线开发可传入 `--provider demo`。

元数据和错误响应使用 UTF-8 XML：

- `GET /api/health`：服务状态。
- `GET /api/tracks?q=关键词&rerank=true|false`：搜索真实 B 站视频 metadata，结果额外包含 `bvid` 和 `page` 属性；空关键词返回空列表。默认本地相关性重排，关闭时保留上游顺序；BV 直达始终保持分 P 顺序。
- `HEAD /api/tracks/{id}/stream`：预检真实 B 站曲目，返回 `audio/mp4`、完整 `Content-Length` 与 `Accept-Ranges: bytes`，无响应体。失败时可读取 `X-Lumen-Error-Code` 与 `X-Lumen-Error-Message`。
- `GET /api/tracks/{id}/stream`：代理真实 B 站 DASH AAC-LC 音频。支持单段 `Range: bytes=...`，成功返回 `206`、`Content-Range`、当前段的 `Content-Length`；不指定 Range 时返回完整音频。曲目 ID 格式为 `bilibili:BV号:p分P序号`，URL 中可将冒号编码为 `%3A`。

服务在 60 秒内缓存解析结果，让预检后的实际播放和拖动进度快速复用地址；缓存过期或 CDN 直链失效时重新解析。优先选 AAC-LC 音轨并在主 CDN 失效时尝试备用地址。`--provider demo` 不提供在线取流，演示音频由桌面端在本地生成，此路由仍返回 501。

游客会话检查 buvid3 和 buvid4，缺任一项时通过 SPI 补齐；不读取登录 Cookie。取流按目标分 P 获取 CID，通过 nav 领取 WBI 密钥，签名请求 playurl。密钥缓存一小时，遭拒绝时至多刷新一次；搜索持续返回 v_voucher 时明确报告风控，不能当作空结果。实现与验收见[第三阶段第一步](../docs/PHASE3.md)。

源站返回的是分段 MP4（DASH 音轨）。即使同为 `audio/mp4`/AAC-LC，不同视频的 MP4 品牌和封装也可能不同；代理保证字节与 Range 协议，不改写原始音频。桌面端优先使用 VLC 经 Axum Range 代理边下边播；远距离 seek 未到达目标时，自动将完整音轨暂存为会话文件并由 JavaFX 恢复进度。

未知路由返回 XML 格式的 `404 NOT_FOUND`。上游超时返回 `504 UPSTREAM_TIMEOUT`；B 站风控、HTTP/API 错误或响应无法解析时返回明确的非 200 XML 错误，不会伪装成空搜索结果。

## 测试

在 `service` 目录执行：

```powershell
python -m unittest discover -s tests -v
```

测试会在 `127.0.0.1` 的临时空闲端口启动真实 HTTP 服务，覆盖健康检查、中文过滤、XML 转义、错误响应，以及取流的 HEAD/Range/备用地址契约。

确定性测试使用模拟上游，不访问网络。需要单独执行实时小样本验收时：

```powershell
$env:LUMEN_RUN_LIVE_TESTS = "1"
python -m unittest tests.test_bilibili_provider.BilibiliProviderLiveTest -v
```

实时验收会访问 B 站，可能受网络与匿名访问风控影响；这种失败会与代码边界测试分开呈现。
