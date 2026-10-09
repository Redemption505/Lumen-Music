# Lumen Music

面向 Windows 10/11 x64 的个人音乐播放器。以本地音乐、个人首页、收藏歌单、歌词和沉浸播放为基础，保留歌曲版本、合集片段与桌宠陪伴等特色。

应用源码采用 [GNU GPL v3](LICENSE.txt)，第三方组件保留各自许可证。项目独立实现，早期设计参考 [Jmiao11/bili-music](https://github.com/Jmiao11/bili-music)。

![Lumen 主题的个人首页](docs/assets/home-lumen.png)

![浅色主题的个人首页](docs/assets/home-light.png)

截图来自当前源码的真实窗口，使用隔离的合成曲库；其中歌名、歌单及记录为演示资料。

## 安装与使用

完整 Windows 包内置 Java、Python、JavaFX、VLC 和音频侧车，无需开发工具或 AI 密钥即可听本地音乐。解压完整包，运行 `LumenMusic-0.2.2-Setup.exe`，从桌面或开始菜单打开应用。在线功能需要联网，AI 设置为可选项。

首次使用可按以下顺序：

1. 在“本地音乐”导入文件或含子目录的文件夹，双击歌曲播放；重复导入会去重。
2. 点击红心收藏，在“我的歌单”整理曲目。
3. 点击底部封面进入沉浸页，使用歌词、迷你播放器或桌宠。
4. 退出后再次打开，从首页继续听；恢复队列时保持暂停。

安装、升级、卸载、封面歌词、备份范围和无法启动时的处理见 [Windows 使用指南](docs/WINDOWS_GUIDE.md)。移除本地列表条目不会删除原音频；曲库 ZIP 备份也不包含音频及所有本机设置。

## 主要功能

- **个人首页：** 继续听、最多六个普通或智能常驻、按真实日期回听与私人电台。
- **本地曲库：** 文件夹导入、搜索与文件夹筛选、持久列表，以及常见内嵌封面和歌词读取。
- **收藏与记忆：** 歌单封面和说明、歌曲版本偏好、最近播放、私人备注与歌词收藏。
- **精听与片段：** 同步歌词定位、A–B 循环、变速、合集片段与批量整理。
- **播放场景：** 五种主题、减少动态效果、沉浸页、迷你播放器、桌宠和 Windows 任务栏控制。

可选在线搜索、歌词与推荐的实际来源和公开使用边界见 [第三方核对](docs/THIRD_PARTY_REVIEW.md)。AI 由用户自行启用并配置服务，向所选服务发送有限音乐摘要、候选信息与本次引导；密钥不进入曲库备份。详细行为见 [AI 设置](docs/AI_INTENT.md)。

## 版本与交付状态

| 对象 | 当前状态 |
| --- | --- |
| 应用版本 | v0.2.2，当前桌面目标为 Windows 10/11 x64 |
| 本地安装 | `lumen-0.2.2-20261009.2`，已包含第四阶段 B/C 与最近播放启动修复 |
| 当前源码 | 以 D 内测候选为应用基线，E 已整理后续 Git/CI/工程入口；D 包内 SOURCE 保留对应构建的冻结快照 |
| 本地仓库 | 已准备可恢复 Git 基线，范围与本地验证见 [E 准备说明](docs/PHASE4_PUBLIC_PREPARATION.md)；暂不推送 |
| 候选与公开下载 | 私下内测候选，交付及验收边界见 [D 候选说明](docs/PHASE4_RELEASE_CANDIDATE.md)；尚无已核验的公开下载地址 |
| 安卓 | 保留规划，暂停实施 |

请按包内说明和应用“设置 → 版本与反馈”确认完整构建号。相同版本号不代表相同修复构建。当前阶段、验收与下一步见 [第四阶段](第四阶段.md)；历史验收保留当时的构建身份。

## 从源码开发

需要完整 JDK、Python 3.10+；音频侧车构建需要 Rust，Windows 原生播放还需 x64 VLC。安装器构建另外需要 WiX、MSVC 和 Windows SDK。开发与安装用户的要求不同，完整步骤见 [开发与复现](docs/DEVELOPMENT.md)。

在独立资料目录准备好环境后，使用项目入口：

```powershell
# 离线演示；解释器/JDK由参数、环境变量或PATH提供
./scripts/launch.ps1 -Provider demo

# 工程基本检查；不需要历史发行镜像
./scripts/check.ps1
```

首次 Maven/Cargo 运行会获取项目已声明依赖；已有缓存时可显式选择离线。窗口、VLC、安装器和发行归档另有验收，基本检查通过不代表安装版或远程 CI 已通过。场景测试应显式选择目标镜像或依赖目录，不能自动沿用他人的旧 `build/`。

## 文档入口

- [Windows 使用指南](docs/WINDOWS_GUIDE.md)
- [开发与复现、依赖及 CI 分工](docs/DEVELOPMENT.md)
- [架构与职责](docs/ARCHITECTURE.md)
- [接口契约](docs/API.md)与[备份格式](docs/BACKUP_FORMAT.md)
- [个人首页](docs/PERSONAL_HOME.md)、[收藏与记忆](docs/COLLECTION_MEMORY.md)、[播放场景](docs/PLAYBACK_SCENES.md)
- [朋友试用说明](docs/FRIEND_TRIAL.md)与[反馈模板](docs/FRIEND_FEEDBACK.md)
- [第四阶段 D 内测候选与验证范围](docs/PHASE4_RELEASE_CANDIDATE.md)
- [第四阶段 E 本地仓库与 CI 准备](docs/PHASE4_PUBLIC_PREPARATION.md)
- [公开候选范围](docs/PUBLIC_SCOPE.md)与[第三方来源、许可及未决项](docs/THIRD_PARTY_REVIEW.md)

真实截图仅使用合成或已获准资料。个人曲库、密钥、日志、缓存、构建备份及内部工作记录不默认纳入源码基线。当前仓库和 CI 均为本地准备，来源许可未决及朋友未复测仍按具体条件登记。
