# Lumen Music

面向 Windows 10/11 x64 的音乐播放器，把本地曲库、个人首页、收藏歌单和沉浸播放放在一起。用继续听、歌曲版本、合集片段与桌宠，保留自己的听歌习惯。

[核心特色](#核心特色) · [从源码运行](#从源码运行) · [开发文档](docs/DEVELOPMENT.md) · [贡献指南](CONTRIBUTING.md) · [问题反馈](https://github.com/Redemption505/Lumen-Music/issues)

当前提供源码，v0.2.2 为 Windows 内测候选。**尚未发布二进制 Release，朋友独立复测尚未完成。** 版本变化见 [CHANGELOG.md](CHANGELOG.md)。

## 界面预览

### Lumen 主题

![Lumen 主题的个人首页](docs/assets/home-lumen.png)

### 浅色主题

![浅色主题的个人首页](docs/assets/home-light.png)

截图来自应用的真实窗口，使用隔离的合成曲库；歌名、歌单和记录均为演示资料。

## 核心特色

| 功能 | 说明 |
| --- | --- |
| 个人首页 | 继续听、最多六个普通或智能常驻、按真实日期回听与私人电台 |
| 本地曲库 | 导入文件或含子目录的文件夹，重复导入去重；搜索、文件夹筛选、内嵌封面与歌词读取 |
| 收藏与歌单 | 收藏曲目、整理普通或智能歌单、设置封面与说明，保留歌曲版本偏好 |
| 听歌记忆 | 最近播放、私人备注与歌词收藏，重启后恢复队列和位置并保持暂停 |
| 歌词与精听 | 同步歌词定位、手动匹配与校准、A–B 循环、变速 |
| 合集与片段 | 分 P 连播、片段边界和批量整理 |
| 多种播放场景 | 沉浸页、迷你播放器、桌宠、Windows 任务栏控制，共享播放状态 |
| 外观 | 深色、浅色、背景图、动态封面与 Lumen 五种主题，支持减少动态效果 |
| 数据备份 | 可移植曲库 ZIP，可选包含历史与部分设置；不包含密钥、原音频和全部本机状态 |

导入后双击歌曲播放，点击红心收藏；点击底部封面进入沉浸页。移除本地列表条目不会删除原音频。安装包使用、升级与备份范围见 [Windows 使用指南](docs/WINDOWS_GUIDE.md)。

在线搜索、歌词和推荐为可选能力。实际来源包括 B 站、LRCLIB 与网易云补充歌词，其接口使用、内容权利及部分二进制材料仍有未决项，见 [第三方来源核对](docs/THIRD_PARTY_REVIEW.md)。AI 需要用户自行启用并配置服务，会向所选服务发送有限音乐摘要、候选信息和本次引导；详见 [AI 设置](docs/AI_INTENT.md)。

## 技术栈与设计

| 模块 | 技术与职责 |
| --- | --- |
| `desktop/` | JavaFX 桌面界面、共享播放状态、本地元数据和 Windows 集成；JavaFX / VLC 音频封装 |
| `service/` | Python 标准库 HTTP 服务，负责搜索、曲库、历史、歌词、备份与可选 AI |
| `stream-proxy/` | Rust + Axum 音频侧车，转发音频字节及 `GET` / `HEAD` / `Range` |
| `scripts/`、`.github/workflows/` | 启动、组件检查、场景验收与 CI |
| `release/`、`docs/` | Windows 打包材料、使用与开发文档 |

Java 与 Python 通过本机 HTTP/XML 通信，默认监听 `127.0.0.1:8765`；Rust 侧车默认使用 `127.0.0.1:8766`。启动器管理自有子进程，关闭时释放资源。完整调用关系见 [架构与职责](docs/ARCHITECTURE.md) 和 [接口契约](docs/API.md)。

播放队列与搜索展示分别管理，主窗口、沉浸页和迷你窗口共享播放状态。异步加载校验请求版本，避免快速切歌时旧结果覆盖当前曲目。持久化采用原子替换并保留异常原件，读取损坏资料时不以空数据覆盖；细节见架构文档。

## 从源码运行

### 开发环境

| 工具 | 要求 |
| --- | --- |
| JDK | 完整 JDK，推荐 21；配置 `JAVA_HOME` 或确保 `PATH` 可找到 `java` / `javac`。Java 编译目标为 17，JavaFX 为 21.0.12 |
| Python | 完整 Python 3.10+，通过 `LUMEN_PYTHON` 或 `PATH` 指定；服务仅使用标准库，无 pip 依赖 |
| Maven | 使用仓库自带 Wrapper，固定 Maven 3.9.11，无需另装 Maven |
| Rust / Cargo | 构建音频侧车和运行完整工程检查时需要；沿用 `Cargo.lock` 并使用 `--locked` |
| x64 VLC | Windows 原生播放及 FLAC 等格式需要；`LUMEN_VLC_HOME` 指向包含 `libvlc.dll`、`libvlccore.dll` 和完整 `plugins/` 的目录 |

首次 Maven/Cargo 调用会下载项目已声明依赖。安装器构建另外需要带 `jpackage` 的 JDK、WiX 3.14.1、MSVC x64 C++17 与 Windows SDK，具体版本和路径说明见 [开发与复现](docs/DEVELOPMENT.md)。

### 克隆与启动

以下命令在 Windows PowerShell 中运行；先准备上表工具，再进入仓库根目录：

```powershell
git clone https://github.com/Redemption505/Lumen-Music.git
cd Lumen-Music

./desktop/mvnw.cmd -B -ntp -f desktop/pom.xml test-compile
cargo build --manifest-path stream-proxy/Cargo.toml --release --locked
```

首次体验使用独立资料目录和离线演示模式。在同一个专用终端继续执行：

```powershell
$devState = Join-Path (Get-Location) ('build/dev-profile/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $devState -Force | Out-Null
$env:LOCALAPPDATA = $devState
$env:APPDATA = Join-Path $devState 'roaming'
$env:LUMEN_LIBRARY_PATH = Join-Path $devState 'library.json'
$env:LUMEN_HISTORY_PATH = Join-Path $devState 'history.json'
$env:LUMEN_PREFERENCES_PATH = Join-Path $devState 'preferences.properties'
$env:LUMEN_AI_SETTINGS_PATH = Join-Path $devState 'ai-settings.json'
./scripts/launch.ps1 -Provider demo
```

`demo` 使用虚构元数据与项目演示音频，不访问在线音源。正常启动默认沿用用户数据位置；参数、服务单独启动及本地媒体验收入口见 [开发与复现](docs/DEVELOPMENT.md)。

### 工程检查

```powershell
# Python、Rust、Java 与隔离 demo 协议检查
./scripts/check.ps1

# 单个组件可选 Java / Python / Rust / Demo
./scripts/check.ps1 -Component Demo
```

已有完整 Maven/Cargo 缓存时可加 `-Offline`。基础检查不需要 VLC 或历史安装包；Windows 窗口、真实音频、安装与升级使用各自的验收入口。仓库已配置 [GitHub Actions](https://github.com/Redemption505/Lumen-Music/actions)，远程运行结果以 Actions 页面为准。

## 文档与贡献

| 需要了解 | 入口 |
| --- | --- |
| 使用、安装、升级、卸载 | [Windows 使用指南](docs/WINDOWS_GUIDE.md) |
| 开发、依赖与验证入口 | [开发与复现](docs/DEVELOPMENT.md) |
| 模块职责与跨语言协议 | [架构](docs/ARCHITECTURE.md)、[接口契约](docs/API.md) |
| 个人首页、收藏与播放场景 | [个人首页](docs/PERSONAL_HOME.md)、[收藏与记忆](docs/COLLECTION_MEMORY.md)、[播放场景](docs/PLAYBACK_SCENES.md) |
| 数据导入导出边界 | [备份格式](docs/BACKUP_FORMAT.md) |
| 参与开发 | [贡献指南](CONTRIBUTING.md)、[仓库工作规范](AGENTS.md) |
| 当前版本与内测反馈 | [变更记录](CHANGELOG.md)、[朋友试用说明](docs/FRIEND_TRIAL.md)、[反馈模板](docs/FRIEND_FEEDBACK.md) |
| 源码与第三方材料范围 | [公开范围](docs/PUBLIC_SCOPE.md)、[第三方核对](docs/THIRD_PARTY_REVIEW.md) |

欢迎通过 [Issues](https://github.com/Redemption505/Lumen-Music/issues) 报告问题或提出建议。请附版本与构建号、系统、复现步骤和预期/实际结果；截图或诊断资料提交前去除私人信息，不上传曲库备份、原始日志、Cookie 或密钥。

## 许可与致谢

Lumen 应用源码采用 [GNU GPL v3](LICENSE.txt)，第三方组件保留各自许可证。应用源码许可证不代表第三方平台接口、音源、歌词或封面已获得相同授权；具体来源与未决项见 [第三方核对](docs/THIRD_PARTY_REVIEW.md)。

早期设计与本仓库文档组织参考 [Jmiao11/bili-music](https://github.com/Jmiao11/bili-music)。感谢 OpenJFX、Python、Rust、Axum、VideoLAN / libVLC、vlcj 与 JNA 等项目，以及歌词服务 LRCLIB。
