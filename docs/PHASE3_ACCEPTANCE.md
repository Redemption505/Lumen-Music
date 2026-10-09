# 第三阶段整体验收

状态：**通过，已交付 v0.2.0**。第三阶段功能、真实网络、界面、本机安装运行、旧版更新与发行均完成验收。范围依据[第三阶段说明第 6 节](PHASE3_EXPANSION.md#6-验收要求)，以下只列实际执行结果。

## 版本与回归

版本 `0.2.0`，构建标识 `lumen-0.2.0-20261006.2`。正式[应用镜像](../build/dist/20261006-170706-final/LumenMusic/)与[安装器](../build/dist/20261006-171131-308/LumenMusic-0.2.0.exe)使用同一份应用与运行环境，安装器不重新生成 Java 镜像。生产 JAR SHA-256：`bc700a65f80cac6306593f64b75c3890c65a439a321d9e6187472bb2ac4acc54`。

- Java：47 个测试类，**239/239 通过**，无失败、错误或跳过。
- Python：312 项运行，**310 通过、2 项原有公网测试跳过**。
- Rust：**7 项通过、1 项辅助子进程测试入口忽略**；它不是 Windows 更新器。跳过/忽略不计为通过。
- PowerShell 更新器：20 项隔离测试通过；保留旧格式兼容后另有 2 项受影响的增量测试通过。发行元数据/源码归档 10 项测试通过，不把合成安装器测试当成真实安装证据。

本轮 `.1` 候选上的真实功能/UI 矩阵可复用：与最终 `.2` 逐条比较 JAR，195 个条目字节一致，差异仅为更新包检查类、构建元数据及内置更新助手。最终 `.2` 已重新执行 Java 全套与真实公网长播；旧版本升级及原生启动单独验收。[字节对应证据](../build/dist/20261006-170706-final/jar-entry-diff.json)

综合流程和滚动驱动使用本机 JDK 23，生产 JAR、依赖、Python 和 VLC 来自候选包；它们不代替捆绑 JVM 与原生启动器的安装运行验收。

## 验收要求映射

| 第 6 节范围 | 实际结果 | 验证入口与证据 |
| --- | --- | --- |
| 片段播放 | 本地/本机 HTTP 源的范围、相对定位、暂停恢复、终点、循环和试听恢复通过；实际安装版真实 B 站 17–23/35–41 秒片段的偏移、相对定位、端点仅推进一次及整曲切回通过；公网快速切换不串台。 | [片段日志](../build/segment-smoke/f348d61e0f2b42c8b501f46e3e8d4d20/desktop.stdout.log)、[真实公网片段](../build/installed-online-smoke/a600a9c13c8b4273a816739bab843d10/desktop.stdout.log)、[公网快速切换](../build/installed-online-smoke/a37254e20eb44e8c859bdd9980edf2b8/desktop.stdout.log)。 |
| 歌词 | 片段独立时间轴、偏移反向定位、暂停跳转、收藏歌词直达、A–B 与变速通过；普通/多 P、手动匹配、缓存及旧请求保护回归通过。 | [练习日志](../build/release-practice-smoke/normal-542697bc27384ee3b0fe443c075d2eeb/desktop.stdout.log)、[既有歌词验收](VALIDATION.md)。 |
| 队列 | 连续插队、手动优先、队尾追加、随机、拖拽、移除/撤销与导航保留通过；Ctrl+K 输入不误触播放。 | [队列日志](../build/release-queue-navigation-smoke/normal-92ebcfa0627c4fcb94887e39696b03ff/desktop.stdout.log)、[完整流程](../build/phase3-release-smoke/dpi-1.25-b2a187e0bf1146baa92df8f3ad525666/workflow-desktop.stdout.log)。 |
| 个人数据 | 真实旧收藏、歌单、歌词、队列和设置只读解析通过；片段、首选版本、标签、规则和私人备注在独立桌面/服务重启后保留；ZIP 恢复与损坏文件保留回归通过。 | [旧格式来源](../build/phase3-release-smoke/dpi-1.25-b2a187e0bf1146baa92df8f3ad525666/legacy-evidence.json)、[重启日志](../build/phase3-release-smoke/dpi-1.5-719fd77ed9b04b79951dccf60df9727d/restart-desktop.stdout.log)、[本机安装数据核对](../build/phase3-install-acceptance/fa3f5204578f475b9df8e1a0b300b62f/evidence.json)。 |
| 缓存与网络 | 源级缓存共享、离线复播、取消、有限重试及未播放隔离通过；默认/纯直连/不可达代理三个真实路由通过，最终公网播放超过 300 秒。 | [连续播放日志](../build/release-continuous-playback-smoke/normal-fa969aa112184ecba86345daedfec1cf/desktop.stdout.log)、[公网逐秒 CSV](../build/installed-online-smoke/a37254e20eb44e8c859bdd9980edf2b8/native-long-playback.csv)。 |
| 推荐与规则 | 电台真实候选、反馈/恢复、中立模式通过；智能规则编辑、未知字段处理、普通快照、标签/文件夹不改变当前音频/队列；版本替换/合并/拆分/撤销通过。 | [电台](../build/radio-smoke/ec94828d72b14dfd87b9f41417a2867c/desktop.stdout.log)、[智能歌单](../build/release-smart-playlist-smoke/normal-882cdd9ee80b4b53b5d50b054c489dd9/desktop.stdout.log)、[版本管理](../build/version-smoke/7d70a642f0e04835b39902a6c74415cb/desktop.stdout.log)。 |
| 界面与陪伴 | 五主题、860×600/1280×800、125%/150% 输出缩放、可中断动画、歌词居中、专注/睡眠与减少动态通过；日记与桌宠真实事件/隐藏/夜间限制通过。 | [150% 场景](../build/release-playback-scene-smoke/hidpi-1.5-eae6b370692541adb41ddbe5bec57750/desktop.stdout.log)、[最近播放](../build/release-music-diary-smoke/normal-3ecc4e4fde9244b0b689b8ca679c3d2c/desktop.stdout.log)、[深色菜单](../build/release-playback-scene-smoke/hidpi-1.5-eae6b370692541adb41ddbe5bec57750/dark-queue-menu.png)、[浅色备注](../build/release-music-diary-smoke/normal-3ecc4e4fde9244b0b689b8ca679c3d2c/light-note-860.png)。 |
| 分发 | 安装器使用已测镜像；实际安装退出 0，文件/个人数据核对、捆绑 JVM 原生运行及安装界面增量通过；四种真实旧版升级/回滚与助手直接重启通过；最终 ZIP 与对应源码逐文件核对。 | [发行入口](../build/releases/)、[安装证据](../build/phase3-install-acceptance/fa3f5204578f475b9df8e1a0b300b62f/evidence.json)，详细结果见下文。 |

## 日常流程与旧数据

两档 DPI 的独立综合流程通过：读取冻结旧实现生成的收藏/歌单样本及旧键设置样本 → 粘贴并编辑三个片段 → 建立普通歌单 → 设置首选版本 → 添加标签并保存智能规则 → 从歌单开启电台并手动插队 → 保存私人备注。独立重启桌面与服务后，片段身份、源范围、顺序、歌曲身份、首选版本、规则及备注保持一致。[125% 来源](../build/phase3-release-smoke/dpi-1.25-b2a187e0bf1146baa92df8f3ad525666/release-input-evidence.json)、[150% 流程](../build/phase3-release-smoke/dpi-1.5-719fd77ed9b04b79951dccf60df9727d/workflow-desktop.stdout.log)

旧格式样本由冻结 v0.1.1 `LibraryStore` 实现生成并记录历史源文件哈希，不把当前格式重新保存的样本称作旧版证据。个人安装与资料已备份 826 个文件；真实副本只读解析得到 20 条收藏、1 个歌单、14 份歌词缓存共 576 行、20 条队列及 12 项设置，原文件未变。只报告数量，不公开私人内容或将备份纳入源码包。

已有 ZIP 恢复、损坏文件保留、多 P 歌词与诊断隐私证据见[阶段验证记录](VALIDATION.md)。3.11 的真实 Windows 文件选择器和两档 DPI 复用此前验收，本轮未重复操作系统对话框。AI 发送契约使用本机桩测试，不调用个人 Key；未知模型 ID 被过滤，不把它等同于真实远端模型质量测评。

## 网络与连续播放

真实 B 站音源的默认路由、纯直连、不可达代理 `127.0.0.1:1` 分别退出 0，均实际搜索、加载两首公开资源并观察 VLC 进度；未修改系统代理。[默认](../build/installed-online-smoke/965413fbe8424202a9f188bcd0c56e6f/desktop.stdout.log)、[纯直连](../build/installed-online-smoke/2182088829004df892c8330a27f8e518/desktop.stdout.log)、[不可达代理](../build/installed-online-smoke/dcb0f73890df456388f1e94d2c079ccb/desktop.stdout.log)

最终 `.2` 实际连续运行 **300.651 秒**，保守累计原生推进 **294.954 秒**；观察到一次真实 VLC EOF 后自然循环，不用视频元数据补计尾秒。300 条逐秒样本无 15 秒播放停顿，堆内存 51.77–90.77 MiB。暂停/恢复、8 次 90ms 快速切换、最终来源和加载版本稳定通过；收藏落盘、独立重启不自动播放、真实双击收藏通过。[最终完整日志](../build/installed-online-smoke/a37254e20eb44e8c859bdd9980edf2b8/desktop.stdout.log)

音频端点全部返回 503 时，重新启动的应用从完整持久缓存播放公开音源，VLC 推进至 3.901 秒，音频请求数为 **0**。双引擎合成慢连接的冷启动/已准备切歌分别为 VLC 1.965/0.703 秒、JavaFX 1.519/0.239 秒；原生淡化、归一化、变速、练习边界和自然队列推进通过。此测量不承诺公网零等待、交叉淡化或无缝播放。

## 大歌单与界面

真实可见 `playlist-tracks` 加载 2000 首，暖机后每档 DPI 三轮顶部/中部/底部滚动，共 9 条 CSV。首响由实际 FX pulse 的视口移动检测，首末索引/ID 来自 `VirtualFlow`；记录活跃 cell、pulse 间隔及 `MemoryMXBean`，稳定截图已抽查。

| 输出缩放 | 滚动首响 | 活跃 cell | 堆内存样本 | 最大 pulse 间隔 |
| --- | --- | --- | --- | --- |
| 125% | 37.79–47.44 ms | 恒 12 | 65.13–143.57 MiB | 48.28 ms |
| 150% | 35.62–50.71 ms | 恒 12 | 55.71–119.71 MiB | 48.59 ms |

[125% CSV](../build/phase3-release-smoke/dpi-1.25-b2a187e0bf1146baa92df8f3ad525666/playlist-scroll.csv)、[150% CSV](../build/phase3-release-smoke/dpi-1.5-719fd77ed9b04b79951dccf60df9727d/playlist-scroll.csv)、[150% 中部截图](../build/phase3-release-smoke/dpi-1.5-719fd77ed9b04b79951dccf60df9727d/playlist-round-3-1000.png)、[125% 底部截图](../build/phase3-release-smoke/dpi-1.25-b2a187e0bf1146baa92df8f3ad525666/playlist-round-3-1999.png)。无同条件旧版性能基线，不据短时观测断言泄漏消除或性能提升；底部 `maxCellCount` INFO 未造成空白或错误条目。

## 安装与发行

本机安装器退出 0，实际安装目录 781 个程序/运行环境文件与已测镜像一致，27 个个人文件与升级前备份逐文件 SHA-256 相同；Windows Installer 产品状态及唯一版本登记为 0.2.0。[安装证据](../build/phase3-install-acceptance/fa3f5204578f475b9df8e1a0b300b62f/evidence.json)

实际安装目录的原生 EXE 在移除开发环境 PATH、隔离个人资料后，使用捆绑 `runtime/bin/server/jvm.dll`、Python 和音频代理启动，服务查询和代理转发通过，窗口与自有子进程正常退出。[原生运行证据](../build/release-runtime-smoke/4764a107de244ff29f6e01dcc7eb67a5/evidence.txt)

实际安装 JAR 的界面增量退出 0：恢复位置、不自动播放、缓存歌词、一次性引导、生成反馈与推荐不改变队列、桌宠隐藏/恢复、五主题背景控制，以及 860×600 的独立设置页滚动和按钮可见性通过；第二次启动保持暂停与推荐结果。测试宿主为本机 JDK，生产类来源明确为安装 JAR；上段原生检查另行证明捆绑 JVM。[界面日志](../build/phase3-installed-smoke/c62602c88cfb4a38967b509f3817758d/stdout.log)、[稳定设置画面](../build/phase3-installed-smoke/c62602c88cfb4a38967b509f3817758d/phase3-settings-860x600.png)

原版 v0.1.1、歌词修复版、阶段开发版和真实旧 MSI 安装备份分别通过实际写入失败回滚 → 成功升级 → 重复更新。每种状态均由正式更新助手直接 `-Restart`，启动捆绑 JVM/Python/代理、查询服务并正常退出，隔离个人 canary 与未受管文件保持不变。前三种一次通过；第四种修正测试库存基线对既有历史备份的遗漏后增量通过，不声称一次四种运行退出 0。[汇总与逐项证据](../build/phase3-update-smoke/4f45443938e1420bba0390638fc83279/combined-acceptance.json)

真实旧 MSI 的 Java 镜像由 `804c6251…688d5` 更新为 `d554d1b1…66bf`，写入失败时恢复实际旧文件；既有历史备份也逐文件保留。生产更新助手未因测试基线修复而改变。

正式交付：[v0.2.0 完整安装 ZIP](../build/releases/Lumen-Music-v0.2.0-Windows-x64.zip)、[v0.1.1 → v0.2.0 更新 ZIP](../build/releases/Lumen-Music-v0.1.1-to-v0.2.0-Windows-x64-Update.zip)。均包含对应 `SOURCE.zip`、GPLv3、第三方声明与校验和。完整包解压运行安装器；旧用户解压更新包、退出程序后运行 `update.cmd`。

文档与测试驱动冻结后重新归档：安装器、生产 JAR、代理、内置/外置助手、25 个更新载荷及 757 个保留约束与已验收输入一致；ZIP 清单/哈希、源码清单与当前工作区、过滤后的构建标识、许可和源码包在完整/更新包间的对应关系全部核对。检查由 `scripts/verify-phase3-release.ps1` 执行，结果保存在[发行核对证据目录](../build/phase3-release-verification/)；文档和压缩包重归档不作为一次新的原生升级测试。

更新载荷包含 25 个受管文件（包括 `runtime/lib/modules`），其余 757 个运行环境文件逐文件严格匹配。这样统一独立打包造成的 Java 镜像差异，保留 Python/VLC 等其余运行环境；原 Java 镜像也进入备份与回滚，不放宽其他路径或哈希检查。

本机验收不是多台电脑、所有公网环境或全天稳定性保证。未实现在线自动更新、手机遥控、双音轨交叉淡化；这些不在本次发行范围。用户操作以[阶段说明](PHASE3_EXPANSION.md)和各功能文档为准。
