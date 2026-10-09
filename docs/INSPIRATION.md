# GitHub 灵感调研

## 调研依据

参考项目：[Jmiao11/bili-music](https://github.com/Jmiao11/bili-music)。查阅日期：2026-09-23。

本轮读取仓库首页与 README，并通过 GitHub API 定位到提交 `c6894f11787a50185dcac5252fba32af5d2e45f9`。读取了该提交的 Cargo 清单、LICENSE、核心库及部分桌面后端代码。未运行原项目，不将其自述性能数字当成本项目测量结果。

可追溯来源：

- [根 Cargo.toml](https://github.com/Jmiao11/bili-music/blob/c6894f11787a50185dcac5252fba32af5d2e45f9/Cargo.toml)：Rust 核心库和 workspace。
- [桌面 Cargo.toml](https://github.com/Jmiao11/bili-music/blob/c6894f11787a50185dcac5252fba32af5d2e45f9/src-tauri/Cargo.toml)：Tauri 2、Axum、Tokio、Reqwest 等依赖。
- [桌面入口 main.rs](https://github.com/Jmiao11/bili-music/blob/c6894f11787a50185dcac5252fba32af5d2e45f9/src-tauri/src/main.rs)：解析任务协调、取消标志和代理 Range 转发代码。
- [library.rs](https://github.com/Jmiao11/bili-music/blob/c6894f11787a50185dcac5252fba32af5d2e45f9/src-tauri/src/library.rs)：曲目快照、歌单、播放记录等模型与原子写入调用。
- [src/lib.rs](https://github.com/Jmiao11/bili-music/blob/c6894f11787a50185dcac5252fba32af5d2e45f9/src/lib.rs)：BV 校验、可取消的 yt-dlp 调用及音频信息模型。
- [LICENSE](https://github.com/Jmiao11/bili-music/blob/c6894f11787a50185dcac5252fba32af5d2e45f9/LICENSE)：MIT，署名 Jmiao11，2026。

## 借鉴与取舍

| 参考观察 | Lumen Music 的转化 |
| --- | --- |
| README 将搜索结果与播放队列分开管理 | 当前 Java 界面保存搜索列表，PlayerQueue 保存独立快照；搜索不会重置队列 |
| 后端用任务标识及取消标志协调解析请求 | 搜索和播放加载使用递增版本号丢弃迟到结果；切歌取消下载并关闭旧响应，慢速取消已有确定性测试 |
| 本地代理转发 Range 等媒体请求头 | Python 代理已支持 HEAD、完整 GET 与单段 Range；JavaFX 为可靠 seek 使用会话临时文件 |
| 曲目快照、收藏和歌单有独立模型 | Python 个人曲库保存版本化曲目快照、收藏与歌单；JavaFX 队列仍独立 |
| 产品围绕搜索、在线播放与个人收藏组织体验 | 先实现核心听歌链路，再做歌词、主题、推荐和桌宠等扩展 |

原项目以 Rust 核心库和 Tauri 桌面层组织能力。本项目保留职责分离的思想，但改用 Java 桌面与 Python 服务，并独立设计协议，不做逐文件移植。

## 产品方向

暂定名称 Lumen Music，面向本地个人使用。主界面包含搜索、结果、队列和底部控制区域。搜索不打断当前队列，加载失败可重试。当前 JavaFX 深色界面可播放本地文件、演示音频及已验证的 B 站音轨；封面、歌词及更多视觉细节仍待完善。

## 当前边界

未复制参考源码、图片或音频。B 站搜索和取流接口已按本机实际响应实现并测试；网页端接口、音频格式和匿名访问条件仍可能变化，不能由参考项目说明推断其持续可用。

参考仓库 LICENSE 为 MIT，README 另有使用说明。若后续复用具体代码或素材，应依据所复用内容的许可保留必要署名；当前仅作设计灵感来源。
