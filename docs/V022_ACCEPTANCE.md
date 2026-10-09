# v0.2.2：个性化桌面综合验收

本文记录 §3.1～3.3 首次交付构建 `lumen-0.2.2-20261007.1` 的综合验收。当前发行压缩包与本机安装已按[滚动、长片段与桌宠修复验收](V022_FIXES_ACCEPTANCE.md)更新；以下首次构建的路径、哈希与数量用于对应其原始证据。

2026-10-07，第四阶段 §3.1 统一视觉、§3.2 个人首页和 §3.3 本轮桌面收藏记忆已实现并通过最终发行 JAR 综合验收。本机安装版已从 v0.2.1 升级到 v0.2.2。纪念卡图片按原规划后置，安卓客户端仍待实施。

版本 `0.2.2`，构建 `lumen-0.2.2-20261007.1`。没有新增依赖；沿用项目声明的 JavaFX/JNA、Python 标准库、Rust 侧车及现有 VLC/WiX 工具。发行不上传或公开发布。

## 交付行为

| 范围 | 当前行为与验收 |
| --- | --- |
| 3.1 视觉语言 | 五主题共用颜色和文字角色、完整比例封面、操作/空态/焦点；浏览与沉浸切换保持播放，减少动态效果即时生效；80 组主题/页面/尺寸/缩放组合通过 |
| 3.2 个人首页 | 继续听复用队列和位置，启动安静；最多六个普通/智能常驻及四区排序；真实回听和现有电台入口；失败保留布局草稿，ZIP 往返及新实例恢复通过；20 组首页布局组合通过 |
| 3.3 收藏与记忆 | 普通歌单封面、实际曲目拼图和短说明；手动偏好版本与真实标注；按日期阅读备注/歌词，旧日期未知，失败保留原草稿；100 组五视图布局组合及保存/回听/备份/重启通过 |

共 200 组窗口组合覆盖 1280×800、860×600 与实际 125% / 150% 输出缩放。检查控件边界、滚动可达性、完整封面及文字对比度；截图保留代表视图，数量不等同于组合数。测试使用隔离资料、明确合成历史/歌词和真实本地 WAV；没有冒充用户经历或使用个人 AI 密钥。

最终 Java 全套 274/274 通过；Python 327 项运行、325 通过/2 项既有公网跳过，最终图片测试夹具补充后的新增 9 项通过。跳过不算通过，不把增量与全套数量相加。Rust 音频侧车源码未改动，现有锁文件 release 构建通过，本轮未重复 Rust 单测。

详细规则与边界：[视觉](PHASE4_VISUAL_ACCEPTANCE.md)、[首页](PHASE4_HOME_ACCEPTANCE.md)、[收藏记忆](PHASE4_COLLECTION_ACCEPTANCE.md)。使用方法：[收藏与记忆](COLLECTION_MEMORY.md)、[个人首页](PERSONAL_HOME.md)。

## 最终生产文件与运行证据

三项窗口入口均显式传入同一 AppHome，生产 JAR 先于测试入口，类加载日志确认 `LumenFxApp` 来自发行 JAR，不使用 `target/classes`。普通窗口驱动使用已有 JDK 23.0.2；jpackage 捆绑运行时没有命令行 java.exe，原生 EXE 的捆绑 JVM 由独立安装运行检查验证。

| 检查 | 125% | 150% |
| --- | --- | --- |
| 视觉 | [日志](../build/visual-language-smoke/normal-dfc25a9292254393b25a8a4f15980f59/desktop.stdout.log) | [日志](../build/visual-language-smoke/hidpi-1.5-c0f0ccb55978410dad9e75ebf55d125e/desktop.stdout.log) |
| 首页 | [日志](../build/personal-home-smoke/normal-09fcc7431aeb4198ab0a34455a88b8b9/desktop.stdout.log) | [日志](../build/personal-home-smoke/hidpi-1.5-4db07b88c24c445aa4dd875deaa90691/desktop.stdout.log) |
| 收藏记忆 | [日志](../build/collection-memory-smoke/normal-00ef3cfd83124d61adace95f0e041fa7/desktop.stdout.log) | [日志](../build/collection-memory-smoke/hidpi-1.5-e0900d8c099a47ad83f7d2881a4e8ff3/desktop.stdout.log) |

镜像：`build/dist/20261007-115249-108/LumenMusic`。安装器：`build/dist/20261007-115825-819/LumenMusic-0.2.2.exe`，直接由该镜像制作。

| 文件 | SHA-256 |
| --- | --- |
| 最终 JAR（镜像/安装目录一致） | `4b8c69477e0d78ce17586584010849d1682737a6f4a373117b09c61f654c19a9` |
| 安装器 | `2c71fd3ec950987f5f501917c9412c6c4a45adb59d0e47bac730f3e37adae841` |

## 本机安装与资料保护

升级前备份安装程序目录和个人资料并逐文件核验：[备份目录](../build/phase3-release-backup/20261007-114209-672433d73cc3444a85b0e29056e7e9ab/)。安装器退出码 0；783 个程序文件与已测镜像完全一致，251 个个人资料文件大小与 SHA-256 保持不变。EXE 版本、唯一 Windows Installer 登记、产品状态与安装路径均核实为 0.2.2，见[安装证据](../build/phase3-install-acceptance/68ce3649baca4bb4b43dd903a5b85757/evidence.json)。

实际入口为 `%LOCALAPPDATA%/LumenMusic/LumenMusic.exe`。移除开发 PATH、清空 Java/Python 开发环境变量后，原生窗口使用安装目录的 jvm.dll；捆绑 Python 演示服务、搜索与 Axum 转发通过，正常关闭后自有进程均停止，见[原生运行证据](../build/release-runtime-smoke/9376cb2805b544668fd4c5c9fa6769a0/evidence.txt)。运行检查使用隔离数据。

安装 JAR 的隔离窗口复验退出 0，实际类加载来自安装 JAR。覆盖静默队列/位置恢复、真实演示 WAV 播放、一次引导推荐请求、歌词缓存零请求、桌宠隐藏、860×600 设置布局和第二实例持久化；服务正常停止。日志与截图：[安装窗口验收](../build/phase3-installed-smoke/247b0dd6998d4b748f8e82338046f772/stdout.log)。旧夹具补齐新首页和智能歌单只读接口，原播放与恢复断言保持。

## 发行入口

- [Windows x64 完整 ZIP](../build/releases/Lumen-Music-v0.2.2-Windows-x64.zip)：解压后运行 `LumenMusic-0.2.2-Setup.exe`。
- [v0.2.1 → v0.2.2 更新 ZIP](../build/releases/Lumen-Music-v0.2.1-to-v0.2.2-Windows-x64-Update.zip)：在设置中选择本地更新包，或完全退出后使用包内 `update.cmd`。

两种发行包包含对应 `SOURCE.zip`、GPLv3、第三方声明及校验和；完整包与更新包使用同一 JAR 和构建号。源码包含最新第四阶段文档，源码校验清单使用 UTF-8 保留中文路径。完整包约 173.7 MiB，更新包约 72.2 MiB，精确大小和最终归档/源码哈希见[最终校验记录](../build/v022-final-verification.json)。校验覆盖 ZIP 全部条目、根校验和、源码与当前工作区、服务模块、安装器、运行环境及构建身份的一致性。

更新包以升级前真实 v0.2.1 安装备份制作，受管 27 个程序文件，757 个运行环境文件按大小与哈希严格保留。隔离副本验证了最后一个受管文件之后注入失败时的完整回滚、成功升级、旧 JAR 退役及备份、重复更新不重复创建备份，以及保留既有备份和非受管资料。真实更新助手 `-Restart` 启动了 v0.2.2 原生窗口，使用捆绑 JVM/Python/代理并正常退出，固定资料校验保持不变，见[更新包实测证据](../build/phase3-update-smoke/7cab92a19ca64c49b468cd33bc20c3cb/evidence.json)。后续仅同步源码文档与 UTF-8 校验清单重新归档，受测程序载荷和更新助手不变。

## 实际范围

未自动操作系统封面文件选择框；选择之后的真实解码、缩放、预览及写入已验证。草稿仅保留于当前运行，关闭后不恢复。收藏记忆测试直接在播放中 HTTP 恢复备份后的旧会话去重拒绝详见其独立报告，不作为新计数验收。

本轮未重跑公网 B 站长听、真实模型调用、朋友无讲解试用、安卓或其他桌面平台；已有在线音频与归一化修复沿用 v0.2.1。本版不包含后置的纪念图片导出。
