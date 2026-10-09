# 第四阶段 A：增量审计与范围冻结

本页本地证据与归档位于未随源码上传的 `build/`；不是 GitHub 下载入口。

日期：2026-10-08。状态：**A 已完成；下文保留 A 冻结时的只读基线。B 随后已完成，当前修复与运行证据见[核心质量验收](PHASE4_CORE_ACCEPTANCE.md)；C 本地交付整理与关键交互补验见[交付验收](PHASE4_DELIVERY_ACCEPTANCE.md)，外部验证条件仍保留。** 产品范围为 Windows 10/11 x64 的个人及朋友日常收听，保留已有桌面特色，暂停安卓和大型新功能。下文源码行号、缺口与“待验”指 A 时点，不替代当前[架构](ARCHITECTURE.md)。

审计结论：现有启动协调、播放版本保护、曲库原子保存、缓存取消与诊断脱敏可以继续复用。本轮工程范围冻结为 **5 项 B 任务**：外观偏好安全读写、AI 配置与本机请求边界、损坏歌词恢复、繁忙退出、拖动回退中的进度交接。后两项先验证假设；不以 `LumenFxApp` 文件长度为由全面重写。交付文案、可复现入口、Windows 验收及公开准备归入 C～E。

## 1. 审计基线与可信边界

本轮读取当前源码、依赖/锁文件、基本测试方法、启动/打包/验收脚本、历史证据和归档元数据；三个只读子任务的结论由主代理核对关键调用链。本轮只写本报告、[架构](ARCHITECTURE.md)、[第四阶段](../第四阶段.md)及简短内部进度记录。

未运行项目测试、服务请求、构建、应用或安装；未读取真实个人资料/凭据，未初始化 Git、修改依赖或系统配置，也未联网、上传或发布。没有进行完整安全、漏洞数据库、许可证/现行条款审计。下文“源码确认”指控制流可定位，“待验风险”指缺少对应运行证据；两者均不等于已复现用户故障。

| 基线 | 当前证据 |
| --- | --- |
| 版本/构建 | `0.2.2` / `lumen-0.2.2-20261007.4`；最新历史摘要见最终验收（本地路径：`build/v022-install-local-final-verification.json`） |
| 主 JAR | 历史最终 SHA-256：`ee87482af614fe99f7108b22d5c1fedcfd610e74ac48cef19d4c4c330439bf6e`，两包 SOURCE 的 BUILD-INFO 一致 |
| 完整包 | 182368596 字节；SHA-256 `347bdd2d25b135b45a6fbefdfdb0856cfca58486df0e6520aec91c4b461037b9` |
| 增量包 | 75955769 字节；SHA-256 `f76a86073f2814744bbfa154dc9cde9e86f5ff9d16c35c4944e066f00542b406` |
| 当前安装/数据 | 历史实际安装为 `%LOCALAPPDATA%/LumenMusic`；资料在 `%LOCALAPPDATA%/Lumen Music`，偏好在 `%APPDATA%/Lumen Music`；本轮未重新启动安装版 |
| 版本管理 | 工作区不是 Git 仓库；不能据此断言远程不存在。公开候选范围见 §6 |

现有包的 manifest、完整包说明及两份 SOURCE/BUILD-INFO 指向 `.4`，但[更新说明模板](../release/v0.2.2/UPDATE-README.txt)第 4 行、增量包 README、两份 SOURCE 内模板仍写 `.3`。这是已确认的说明冲突，尚无证据表明二进制版本错误。本次新文档未进入旧包 SOURCE；旧哈希不能代表未来重打包字节。

历史 Java 记录为首次完整 302 项、后续新增并发测试及受影响范围复测，摘要汇总 303 条报告；不是最终完整 303 项重跑。Python 历史记录为 346 项、344 通过、2 跳过。没有据此计算覆盖率或宣称本轮通过。

## 2. 核心调用链与现有防护

| 链路 | 当前实现与边界 | 审计处置 |
| --- | --- | --- |
| 单实例 → 显示窗口 → 后台服务 | `LumenLauncher` / `SingleInstanceGuard` / `StartupCoordinator`；失败可重试，取消后迟到资源回收 | 保留，补受影响回归；不把朋友原闪退归因于已修复的阻塞 |
| 托管 Python → 可选侧车 | 健康复用的实例不归本应用所有；只结束保存的自有 `Process`，侧车失败可回到 Python 代理 | 保留；B-04 验外部复用实例存活 |
| 选曲 → 音频加载 → UI 回调 | `loadVersion` 与媒体身份校验，旧请求取消，结束事件按加载版本去重 | 保留；B-05 验异步进度交接与快速切歌 |
| 本地文件/文件夹 → 元数据 → 播放 | 后台导入；持久本地列表保存引用；同名 LRC、内嵌封面/歌词；FLAC/Ogg/Opus 等等待 VLC 而非 Python | 本地 FLAC 在服务失败/等待时的真实播放仍缺证据，纳入 B 验收，不先重写导入 |
| 在线流式 → 拖动回退 → 完整文件 | VLC、`MusicClient`、会话/持久缓存；取消关闭读取流，不提交半文件，迟到版本不应用 | 保留；回退 READY 前退出见 B-05 |
| 曲库/历史 → 文件替换 → 内存提交 | Python 检测外部修改、保留有效备份、主文件成功后更新内存；桌面 `LocalStateFile` 使用临时文件替换 | 保留；偏好和 AI 组合保存的差异见 B-01/B-02 |
| 保存歌词 → 本地读取 → 在线候选 | 手动与自动文件分离，身份包含来源/分 P/片段，保存原子替换 | 读取原件保留不保证后续自动匹配保留，见 B-03 |
| 退出 → 快照 → 听歌收尾/任务 → 音频释放 | 有 `closing`、取消和限时等待；当前界面线程串行等待预算可达 9 秒，音频释放靠后 | 属待验风险 B-04，不能写成已测到九秒卡顿 |
| Provider 错误 → HTTP → 桌面反馈 | XML/错误头 → `StreamException` / `PlaybackFailure` → 中文状态与固定诊断代码；搜索统一 `SEARCH_ERROR` | 不扩大为“所有错误码必须原样进诊断”；未知码归 `UNKNOWN_ERROR` 的设计保留 |

详见[当前架构](ARCHITECTURE.md)。`LumenFxApp.java` 约 6990 行是维护风险，已有队列、音频封装、Store 和启动协调继续复用。A 没有发现必须整类拆分的证据；必要抽离限定为下面行为修复的直接需要。

## 3. B 冻结问题清单

优先级按影响判断：P0 为崩溃/核心不可用、不可恢复数据损坏或凭据暴露等阻断；P1 为重要反馈/恢复和稳定性问题。风险项的优先级是**验证与处置顺序**，不是事故发生证明。以下验收均为 B 的待执行标准，使用隔离数据、合成密钥/媒体和可控失败，不破坏真实个人资料。

### B-01 · P0：外观偏好安全读写

**证据与触发：** [LumenFxApp.java](../desktop/src/main/java/com/lumenmusic/LumenFxApp.java) `startUi:360` 调用 `readAppearance`；`:6871` 的 `Properties.load` 可抛损坏 Unicode 转义异常，外层 `:6894` 只捕获 I/O 和非法路径，未捕获该异常。`:6921` 直接截断重写偏好，`:6933` 退出再次保存。损坏读取异常逃逸、非原子覆盖是源码确认；实际启动表现及失败写入后的损失尚未运行复现，不能归为朋友原闪退根因。

**已有保护/最小范围：** 枚举与部分数字有逐项默认值，其他桌面 Store 已有安全替换模式。拟改应用偏好读写及必要的独立小模块/测试，沿用现有路径和 `.properties` 格式；处理解析失败、保留损坏原件、临时文件替换，不重新设计主题或扩大配置系统。

**验收与恢复：** 损坏转义时窗口能启动并反馈；退出不会悄悄覆盖损坏原件。注入写入/替换失败后旧有效文件逐字节不变；正常保存、重启恢复所有已有选项。损坏原件有明确可恢复副本，不以重建空资料掩盖问题。

### B-02 · P0：AI 配置、本机请求与密钥目标边界

**证据与触发：** [server.py](../service/lumen_service/server.py) `:1321` 绑定回环地址，但 `do_POST:403–414` 直接分派 AI 写入；`_library_body:999–1003` 仅校验长度并解析 XML，没有来源、Host 或媒体类型检查，服务端代码因此也接受包含有效 XML 的 `text/plain`。`_handle_ai_settings:974–980` 在省略 `apiKey` 时传入 `None`。

[ai_settings.py](../service/lumen_service/ai_settings.py) `save:88–90` 先提交配置，再在提供密钥时提交密钥；第二步失败不回滚第一步。`config:108–118` 用新地址配对原文件密钥或优先的环境密钥，没有目标绑定。[ai_intent.py](../service/lumen_service/ai_intent.py) `:134–144` 向该地址发送 Bearer。保存本身不联网；后续推荐/电台在 AI 启用、配置有效、存在安全上下文和可用数据 Store 时才调用。

这些条件链和部分提交是源码确认。跨来源网页能否实际送达本机受浏览器策略等约束，本轮没有浏览器利用验证，也没有真实凭据泄露证据；缺少 CORS 响应不能作为写入接口的代码防护。

**已有保护/最小范围：** 配置、密钥分离，单文件有 `fsync`/原子替换，响应不回传密钥，启用地址要求 HTTPS。拟改 `server.py`、`ai_settings.py`、相关契约测试及必要的 AI 设置反馈：在配置解析前校验本机请求边界和 XML 类型，允许现有桌面无 Origin 的合法 XML 请求；地址改变不得隐式继承原密钥，环境密钥也需限定目标；组合写入失败恢复。按相同危险写入入口复用必要校验，不引入账户系统、远程服务或新依赖。

**验收与恢复：** 外来 Origin、非本机 Host、`text/plain`/无类型的 AI 写入被拒且旧文件逐字节不变，现有桌面 XML 保存正常。地址 A 改为 B 且省略密钥后，后续调用不得向 B 发送 A 的合成密钥；分别覆盖文件密钥与环境密钥。合法提交 B 密钥后，用内存 transport 断言 URL/Authorization 配对，不访问外网。注入第二次替换失败，配置/密钥都保持旧值，重启一致；回滚失败保留可恢复副本并说明状态。保留 AI 关闭时基础功能可用和现有环境配置入口。

### B-03 · P1：损坏歌词恢复与可见反馈

**证据与触发：** [LyricsSelectionStore.java](../desktop/src/main/java/com/lumenmusic/LyricsSelectionStore.java) `load:72` 在无手动记录时读自动记录，`:96–97` 报错且本次读取保留原件。`LumenFxApp:6155` 仅写标准错误并继续检索；`:6252` 自动候选 → `:6284–6286` 自动来源 → `saveManualLyrics:6360` → Store `:113/119` 替换同一自动文件。因此“自动缓存损坏、无手动记录、检索有自动候选”时，整个恢复流程可能覆盖原损坏字节；此调用链源码确认，完整场景未运行。

**已有保护/最小范围：** 手动优先、分离文件、身份校验、原子保存继续使用。拟改 Store 的损坏记录保留/隔离及应用歌词反馈，防止后台结果无声覆盖原件；记录已有 `LYRICS_ERROR` 固定码，不写曲名/路径/异常原文到诊断。不调整匹配算法或手动优先级。

**验收与恢复：** 合成损坏自动记录配合可控自动候选，歌词可继续匹配，但原损坏字节可恢复、界面有可读反馈、诊断仅固定码。手动损坏不被自动缓存绕过，正常手动/自动选择、偏移和片段起点重启保持。

### B-04 · P1 待验：繁忙退出的响应与保存交接

**证据与触发：** `LumenFxApp:6956–6984` 在 JavaFX 退出流程中结束听歌、调用 [ListeningReporter.close](../desktop/src/main/java/com/lumenmusic/ListeningReporter.java) `:160`（最长等 3 秒），再对本地、歌词、下载队列各等 2 秒，最后关闭音频。9 秒是串行等待上限的静态合计，未包含原生释放/同步保存，不是实测耗时。触发条件是网络收尾慢且多个队列繁忙。

**已有保护/最小范围：** 等待有上限，快照先于媒体销毁，托管进程异步关闭。先做可控延迟的退出验收；若确认阻塞/停止发声或保存交接问题，仅调整应用退出协调、Reporter 和直接相关资源边界，不拆全部页面。

**验收与恢复：** 记录普通与多个延迟任务情况下的关闭响应、实际停止发声、保存及进程结束时间，避免界面串行等待全部队列；最后一笔本地/歌词保存完成或明确失败，重启恢复正确暂停快照，自有子进程/桌宠释放，复用外部服务仍在。保留退出前的隔离资料快照；未复现则记录结果，不叠加猜测性补丁。

### B-05 · P1 待验：拖动回退准备期间退出的进度

**证据与触发：** `LumenFxApp.fallbackSeekToLocal:5682–5711` 先 `audio.stop()`，完整文件下载成功回调后才写 `pendingResumeSeconds:5705`；[播放状态保存](../desktop/src/main/java/com/lumenmusic/LumenFxApp.java) `:5997–5998` 优先取待恢复位置，否则读取音频当前位置。普通整曲停止后位置/未知时长可变零。下载中或本地媒体 READY 前退出，可能保存零位置；片段有独立时长，不能泛化。源码支持该假设，运行未验证。

**已有保护/最小范围：** 回退保留播放/暂停意图、加载版本检查、READY 后正常定位。先用可控音轨/延迟验证；成立时在停止前捕获目标秒数，贯穿回退/退出状态保存。拟改应用的回退与快照交接及行为测试，不改缓存格式或引擎选型。

**验收与恢复：** 分别在下载中、下载结束但 READY 前关闭，重启曲目和目标位置正确且保持暂停；合成音轨位置误差不超过 1 秒（计划验收阈值）。正常回退、取消后快速切歌、片段播放不串台。仍使用独立播放状态和任务日志，不写真实用户队列。

## 4. 关键行为与测试证据映射

以下读取了实际测试方法/断言，未在本轮执行。历史运行与当前缺口分开；一个 Store 方法通过不代表完整界面调用链通过。

| 核心行为 | 已有方法或证据 | 尚缺验证/阶段 |
| --- | --- | --- |
| 后台启动、失败重试、取消/迟到释放 | [StartupCoordinatorTest](../desktop/src/test/java/com/lumenmusic/StartupCoordinatorTest.java) 的 `slowStartupDoesNotBlockCallerAndConcurrentRequestsShareAttempt`、`failedStartupCanRetryAndOwnsOnlySuccessfulResource`、两个 close 方法；历史四项零失败 | B 关联回归；跨 JVM 单实例唤醒不是锁测试已覆盖结论 |
| 初始化期间界面仍可用 | [StartupUiSmoke](../desktop/src/test/java/com/lumenmusic/StartupUiSmoke.java) 服务失败/重试与 2.5 秒等待；历史两缩放窗口 PASS、19/27 次界面脉冲 | 不覆盖繁忙退出、FLAC 发声或朋友原闪退；B-04 / C |
| 本地持久引用、元数据、移除不删音频、并发 | [LocalMusicStoreTest](../desktop/src/test/java/com/lumenmusic/LocalMusicStoreTest.java) 的 `retainsReferencesArtworkAndLyricsAcrossRestartAndRemovalDoesNotDeleteAudio`、`damagedCatalogueIsRetainedForRecovery`、并发读写方法；`.4` 真实安装版本地窗口历史验收 | B 验服务失败/长等时 FLAC 起播、暂停、拖动、切歌/退出；WAV 成功不能替代 |
| 在线缓存、取消、不留半文件、持久复用 | [OnlineAudioCacheTest](../desktop/src/test/java/com/lumenmusic/OnlineAudioCacheTest.java) 九方法；历史九项零失败 | B-05 的下载/READY 前退出，以及 B-04 慢下载关闭 |
| 播放队列/进度序列化、失败保留 | [PlaybackStateStoreTest](../desktop/src/test/java/com/lumenmusic/PlaybackStateStoreTest.java) 队列、片段往返和失败替换 | 不覆盖引擎停止后目标交接；B-05 |
| 曲库/历史外部损坏与保存失败 | [曲库测试](../service/tests/test_library.py)、[历史测试](../service/tests/test_history.py) 的失败替换、外部损坏/修改断言；内存与旧字节保持 | 保留关联回归；不是偏好/AI 组合写入的覆盖 |
| 偏好正常退出 | [PetExitSmoke](../desktop/src/test/java/com/lumenmusic/PetExitSmoke.java) 历史七种退出场景保留桌宠设置/自然结束 | B-01 损坏解析、写入失败和原件保护；B-04 延迟任务 |
| 手动/自动歌词、损坏/失败替换 | [LyricsSelectionStoreTest](../desktop/src/test/java/com/lumenmusic/LyricsSelectionStoreTest.java) 的 `corruptManualChoiceIsReportedDespiteAutomaticCache`、`failedReplacementKeepsOriginalAndCleansTemporaryFile`；已有正常缓存界面检查 | B-03 自动损坏→在线回退→后台保存的完整链路和 UI/诊断反馈 |
| AI 设置分离、重启、响应不含密钥 | [test_ai_settings](../service/tests/test_ai_settings.py) 的 `test_saved_config_uses_separate_secret_and_survives_restart`、`test_http_settings_never_return_key`；[test_ai_intent](../service/tests/test_ai_intent.py) 合成 Bearer/最小提示断言 | B-02 第二文件失败、来源/类型、地址变更及文件/环境密钥目标约束 |
| 备份预检/多文件回滚 | [test_backup](../service/tests/test_backup.py) 的 `test_restore_failure_rolls_back_every_replaced_target` 注入第二次替换失败；篡改/重复/越界 ZIP 拒绝 | 保留已有白名单；整目录安装备份与可移植 ZIP 不等同 |
| 诊断白名单/隐私 | [DiagnosticLogTest](../desktop/src/test/java/com/lumenmusic/DiagnosticLogTest.java) 的 `exportContainsOnlyWhitelistMetadataAndSafeCodes` 检查合成隐私 canary 不存在；已有保存对话框/单成员 ZIP 检查 | B-03 新反馈沿用固定码；普通日志、备份不能据此声称脱敏 |
| 快速切歌/远距离拖动/长听 | `.4` 历史播放与窗口材料、较早公网快速切歌证据；[安装与本地音乐验收](V022_INSTALL_LOCAL_ACCEPTANCE.md) | 新候选按第四阶段 §8.2 验 20 次切歌、10 次拖动、60 分钟；属 D 计划，未运行 |
| 原生安装、升级、原资料与卸载 | 最终摘要（本地路径：`build/v022-install-local-final-verification.json`） 记录 `.4` 隔离安装/同版本升级/缓存 MSI 卸载、安装文件与个人数据保留 | C 真实鼠标目录选择、无开发环境独立试用/原反馈者复测；本轮未确认原闪退根因 |
| 自动化 CI | [verify.yml](../.github/workflows/verify.yml) Linux Java/Python/Rust/demo HTTP 集成；POM 排除 `SmokeTest`/`FxDesktopSmokeTest`，CI 另跑前者 | 未取得远程运行结果；Windows UI/VLC/安装、打包/SOURCE 不在当前 CI 覆盖 |

## 5. 依赖、运行入口与工程基线

| 范围 | 当前声明/交付 | 审计判断 |
| --- | --- | --- |
| Java 桌面 | [POM](../desktop/pom.xml)：目标 17；JavaFX 21.0.12；JNA/JNA-platform 5.13.0；vlcj 4.11.0；JUnit 5.11.4 | 当前发行 JDK/runtime 23.0.2；Linux CI JDK 21。三个维度不混同，无升级结论 |
| Maven | [Wrapper](../desktop/.mvn/wrapper/maven-wrapper.properties)：Maven 3.9.11、wrapper 3.3.4 | 已有固定下载 URL，未配置 `distributionSha256Sum`；C 核对可复现/校验说明，不在 A 新增工具 |
| Rust 侧车 | [Cargo.toml](../stream-proxy/Cargo.toml) / [Cargo.lock](../stream-proxy/Cargo.lock)：Axum 0.8.9、bytes 1.12.1、futures-util 0.3.34、reqwest 0.12.28、Tokio 1.53.1、url 2.5.8；test http-body-util 0.1.5 | CI/打包已有 `--locked`，Rust 工具链未独立设置/固定；未做漏洞扫描 |
| Python 服务 | Python 3.10+，模块仅标准库与项目内部依赖 | 不需要新增 pip 依赖；内置运行时 3.13.15 与 Linux CI 3.12 分别验证 |
| Windows 原生运行时 | [内置 Python 准备](../scripts/prepare-python-runtime.ps1) 与 [Windows 打包](../scripts/package-windows.ps1)：Python 3.13.15、VLC 3.0.23、官方来源及固定 SHA | 当前镜像文件版本相符；来源与版本不等于完整再分发/安全审核 |
| Java 打包依赖 | 脚本另列 vlcj-natives 4.8.3、JNA JPMS/JNA-platform JPMS 5.14.0，与普通 JNA 同时复制 | C 对照实际 Maven dependency graph / 启动类路径，确认每份用途后调整；共存本身不证明故障，不先盲目升级/删除 |
| 安装器 | `jpackage`、WiX 3.14.1；[自定义资源编译](../scripts/installer/prepare-resources.ps1) 需要 MSVC x64 C++17 / Windows SDK，`/MT` | README 只提 WiX，C 补完整工具前提；本轮不安装工具 |

| 入口 | 当前可复现边界与 C 待办 |
| --- | --- |
| [launch.ps1](../scripts/launch.ps1)、[desktop.ps1](../scripts/desktop.ps1) | 相对项目定位，支持 Python 参数/环境变量、Java/PATH 和 wrapper；保留。UI smoke 默认音频 fixture 需要明确输入；README 私人 Python 示例需替换 |
| Python unittest、Maven verify、Cargo locked test | 已有基本入口，C 文档写清工作目录/前提。`phase3-smoke` 使用离线 Maven，不作为干净环境首轮入口 |
| 视觉/首页/收藏等场景 smoke | 支持参数，但共用脚本混有个人 Python/JDK 候选。未传 AppHome 可检查开发 classes，显式 AppHome 才能证明目标安装 JAR；C 区分 |
| 安装/发行/更新验收 | 部分默认私人安装/JDK 路径或旧 app-image，更新需要两代镜像；C 参数化当前推荐入口，历史专用脚本只标明 fixture 前提 |
| Windows package / release / update | 已有源/目标镜像、BuildId、版本与源码校验；打包跳过 Maven 测试，成功不替代验收。release 还需已有第三方源码归档；D 核对受影响材料 |
| CI | Ubuntu + JDK 21/Python 3.12 + Python/Cargo/Maven/demo 集成；C 明确 Windows 检查分工，E 才记录真实远程结果 |

## 6. C～E 边界与公开候选范围

**C 固定交付整理项：** 统一当前用户/开发 README（架构已在 A 更新）；推荐入口消除个人路径假设并注明历史 fixture；明确依赖图/打包清单及安装工具前提；核对 Windows 与 Linux 检查分工；准备简短朋友试用/无法启动时诊断说明，补真实安装目录选择与独立使用；核对实际第三方音源/接口/素材/组件的拟公开条件。原闪退无转储且未复现，等待原反馈者证据，不猜测重构。

**隐私边界已按实现区分：** 可移植 ZIP 包含私人收藏、歌单、备注/歌词收藏、听歌记录、本地媒体/封面引用及可能私密的背景，不含独立密钥或音频缓存；AI `baseUrl` 查询参数不自动脱敏。整目录安装备份可能含 `ai-secret.json` 和所有个人资料。诊断 ZIP 是白名单，原始 stdout/stderr 不是；本轮未查看真实日志，不能宣称实际日志含凭据，也不能宣称整个目录已脱敏。

**准备纳入本地 Git 的候选：** Java/Python/Rust 源码与测试、POM/wrapper/Cargo.lock、必要构建脚本、现行根文档与 docs、release 模板、许可证/第三方材料、`.github/workflows` 和忽略规则。优先在 B 首次代码修改前、对应授权齐备后建立真实可恢复基线；不伪造历史。

**默认不公开：** `build/`（含备份、安装证据、媒体与工具）、target、缓存、虚拟环境、日志、`.planning/` 内部流水账、个人数据/密钥和签名材料。现有忽略规则未覆盖 `.planning` 和源码范围的 `ai-secret.json`；C 应核对候选内容并修正必要排除，不据此推断当前源码已有真实秘密。公开截图使用合成或获准资料。现有 SOURCE 白名单未收 `.github/workflows`、`.gitignore`，应明确其作为对应源码与完整开发仓库的区别；不据此作许可证违规结论。

**D 固定候选交付项：** B/C 完成后冻结源码与构建身份，补受影响测试及第四阶段 §8.2 候选回归，重建受影响完整/更新/SOURCE 归档，修正 `.3` 说明并重新计算真实字节校验。保留旧包与恢复材料。代码改动使用新唯一 BuildId；只改说明也须新归档校验，不能把旧 `.4` 证据改写为新候选通过。

**E 外部交付：** 准确远程/可见性仍待确认；获准后推送经过检查的源码、取得实际 CI 结果，再按发布门槛公开附件和真实下载链接。用户未授权本轮执行这些操作，A 不初始化仓库、不发布、不联系朋友。

## 7. 范围冻结与 A 完成判断

下一步是 **B-01/B-02 → B-03 → B-04/B-05 定向验证与必要修复**；同一文件的改动串行整合。B 的基本回归包括服务不可用时本地 FLAC、启动取消/自有进程释放、歌词手动优先、原子存储及快速切歌；各项验证通过后再扩大到 D 的候选完整回归。未证实的风险不强制重构。

B 不包含新主题、新音频引擎、全应用架构重写、整套新测试框架、依赖批量升级、安卓/云同步/社区或新特色功能；C/D 的脚本、CI、归档和试用不混入 B 问题清零。新出现的 P0 可以以证据追加范围，先更新清单，不能无界延伸。

A 交付已具备：当前职责与依赖入口、五项证据/触发/影响/保护/最小修改/验收/恢复、关键行为覆盖与缺口、Git/公开候选范围、C～E 前置与停止条件。文档链接和阶段状态经检查。**A 完成不代表 B 风险已解决、安装版已更新或发布候选已经通过。** 当前无需额外用户信息即可交付 A；后续具体实施与安装/发布分别按已覆盖的授权执行。
