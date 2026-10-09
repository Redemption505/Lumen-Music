# 第四阶段 B：核心工程质量验收

本页本地证据与归档位于未随源码上传的 `build/`；不是 GitHub 下载入口。

日期：2026-10-08。范围为 [A 冻结的 B-01～B-05](PHASE4_AUDIT_SCOPE.md) 及直接关联回归。本轮修改当前源码，沿用已有依赖；已安装版和发行 ZIP 仍为 `lumen-0.2.2-20261007.4`。下列开发环境结果不代表新安装包或公开发布通过。

## 1. 修复与恢复方式

| 项目 | 当前行为 | 行为证据 |
| --- | --- | --- |
| B-01 外观偏好 | 普通 properties 格式及路径保持；损坏 Unicode 使用默认值，先保存完整原字节；安全副本不可用时拒绝覆盖；同目录临时文件原子替换 | 12 项 Store 测试覆盖损坏、不可读、备份/写入/替换失败及重启；实际窗口可打开，设置恢复提示可见，主题按钮生效并重新读取正确，原件副本字节相同 |
| B-02 本机写入与 AI | POST/PATCH/DELETE 校验回环 Host/端口及有值的 Origin；POST/PATCH 要求 XML 类型。文件/环境密钥绑定完整地址；组合提交的本地恢复记录支持失败回滚与中断后恢复；HTTP 备份恢复不会重新绑定旧密钥 | 18 项新增边界测试：非法请求无修改，合法桌面/DELETE 可用，A→B/环境/旧格式/实际备份恢复不发旧密钥，第二次替换失败恢复两文件，回滚受阻重试，外部修改不覆盖；实际 Java→Python 曲库写入通过 |
| B-03 损坏歌词 | 读取失败显示持续提示及固定诊断码；保存前保留精确 `.corrupt` 原件副本，已有副本不同或无法保留时拒绝覆盖；手动优先和偏移/起点保持 | Store 回归和实际窗口的候选检索→应用→自动保存→重新读取；损坏原字节保留，提示不被成功状态清掉，`LYRICS_ERROR` 无曲名、内容及路径，手动选择不触发新候选请求 |
| B-04 繁忙退出 | 先保存位置、使旧回调失效并停止音频；听歌报告及最后列表/歌词写入的限时等待交给非守护关闭线程，随后清理会话缓存；超时用固定码反馈 | 原编译版本受控复现界面线程等待约 9.1 秒；修复后相同忙队列条件关闭返回 58/77 毫秒，正常完整文件暂停关闭 160 毫秒；排队的实际列表/歌词写入完成；真实 VLC 释放及外部服务存活，实际自有 Python 子进程关闭/复用通过 |
| B-05 回退中的目标进度 | 停止旧音频前捕获相对曲目目标秒数；下载中与文件 LOADING/READY 前保存使用同一目标，迟到结果检查版本及关闭状态 | 原版本下载中保存 0 秒，修复后保存/关闭/实际再次打开为 60 秒且无自动播放；32 秒合成 AAC 的 60% 目标在 READY 前保存/重启为 19.2 秒；正常完成回退也到达该暂停目标 |

外观损坏副本在偏好文件同目录，名为 `preferences.properties.damaged-*.bak`；歌词副本在 `lyrics-selections` 同目录、以原文件名追加 `.corrupt`。这些副本只供本地恢复，无法保留时不覆盖原件。设置可见提示不暴露完整个人路径。

AI 的 `.ai-settings-recovery.json` 与 `ai-secret.json` 一样包含凭据，只保留在个人目录，均不进入可移植 ZIP 或诊断包；若恢复受阻，恢复目录访问后重试。地址改变后为该地址重新输入密钥。详见 [AI 设置](AI_INTENT.md)与[备份格式](BACKUP_FORMAT.md)，底层独立恢复 API 的调用方也须执行密钥目标保护步骤。

## 2. 验证结果与证据

测试资料全部为独立目录、合成密钥和自产音频；请求限本机回环，在线提供者及 AI 传输受控。本轮没有读取真实曲库/密钥，也没有使用个人音频作为测试素材。

| 验证 | 实际结果 | 记录 |
| --- | --- | --- |
| Java 完整构建与单元回归 | JDK 23.0.2，编译目标 17；320 项全部通过 | 完整日志（本地路径：`build/phase4-core-b/java-full.txt`） |
| Python 完整回归 | Python 3.13.15；364 项中 362 通过、2 跳过，零失败/错误；18 项新增边界全部通过 | 完整摘要（本地路径：`build/phase4-core-b/ai/full-summary.json`）、边界摘要（本地路径：`build/phase4-core-b/ai/boundary-summary.json`） |
| 原版本受控复现 | 下载中的快照 0 秒；四个忙队列关闭返回 9104 毫秒 | 原版本证据（本地路径：`build/phase4-core-b/ui-baseline/normal-a9b08acc2e3c474c89124703319d1ab8/phase4-core-evidence.txt`） |
| 下载中关闭/排队写入/再次打开 | 60 秒、58 毫秒；真实列表/歌词写入与暂停恢复通过，外部服务仍健康 | 证据（本地路径：`build/phase4-core-b/ui-pending/normal-ae616148629a49d0a3ff1f88756eba49/phase4-core-evidence.txt`） |
| 完整文件 READY 前关闭 | 19.2 秒、77 毫秒；在真实 AAC 的 LOADING 回调排队关闭，验证尚未 READY；实际再次打开保持暂停 | 证据（本地路径：`build/phase4-core-b/ui-before-ready/normal-6715b1cf675c443faeb65404cef16015/phase4-core-evidence.txt`） |
| 正常完整文件暂停定位 | 到达 19.2 秒，保持未播放；关闭返回 160 毫秒，写入和再次打开通过 | 证据（本地路径：`build/phase4-core-b/ui-after-ready/normal-fba45f6a6b254796a30c7e9f031cf0e6/phase4-core-evidence.txt`） |
| 实际 Java→Python 写入协议 | 收藏、普通歌单、排序、去重、XML、本地引用和片段增删改通过 | 客户端日志（本地路径：`build/phase4-core-b/library-contract/normal-1454c131827341c7b29da3b40bea084f/desktop.stdout.log`） |
| 自有 Python 进程生命周期 | 当前 Java + 已有标准 Python 3.12.14；随机端口启动、健康复用、数据源冲突拒绝、复用关闭不杀服务、自有关闭后不再健康 | 日志（本地路径：`build/phase4-core-b/service-lifecycle/normal-47af122c663a4242a7b6e2979e408424/desktop.stdout.log`） |
| 真实本地 FLAC 界面 | 正常及 150% 显示通过；服务等待/失败时两曲播放/暂停/拖动/恢复/切歌，偏好和歌词恢复，退出及持久化；真实 VLC 关闭返回 143/146 毫秒 | 正常证据（本地路径：`build/phase4-core-b/local/normal-d6190cfe0618406ca1ea62ba5d820585/phase4-local-evidence.txt`）、150% 证据（本地路径：`build/phase4-core-b/local/hidpi-941fc24062ef405782a8c4062971f0b4/phase4-local-evidence.txt`） |
| 关联启动 | 服务等待时导航/主题/界面心跳、HTTP 失败/重试及已有队列/位置/本地列表保持；原生音频初始化待处理时心跳 20 次 | 证据（本地路径：`build/phase4-core-b/startup-ui-smoke/normal-e973479f1e6742c68b4547f2c1f08d90/startup-ui-evidence.txt`） |
| 桌宠关联退出 | 按钮、窗口关闭、最小化、沉浸、迷你、离开/返回桌面七种方式通过；附属窗口/动画释放，七个 Java 进程自然退出 | 全部场景（本地路径：`build/pet-exit-smoke/eebac3898e7d46c0993dfceaeeff907a/`） |
| 片段关联回归 | 自产 AAC、真实 VLC；源偏移/相对拖动/结束切段/单曲重复、独立歌词、共享缓存、实际重启恢复及 UI 预览/取消/创建/编辑/删除通过 | 日志与截图（本地路径：`build/segment-smoke/5b47c157208c4f1384d6d63170b2a655/`） |

FLAC 验证经生产选曲/加载路径使用真实 VLC，分别在服务等待和失败时检查两曲起播、时钟推进、暂停、拖动约 12 秒、恢复和切歌；还检查设置/歌词恢复、最后暂停位置、VLC 关闭、偏好/列表/歌词重新读取与恢复副本。界面测试不接管鼠标；原生状态与时间检查不等于声学音质或实际扬声器采集。

第一次 FLAC 验收失败的根因是自产的流式转码文件 STREAMINFO 总样本数为 0，原生时长为未知，不能映射拖动比例。经原生解码实际统计为每曲 1,056,195 样本、23.95 秒，只填写总样本数字段并保留原失败文件，原暂停/12 秒断言保持；没有为此修改生产播放器。异步歌词写入的轮询也修正为等待预期的损坏记录替换完成，未放宽最终原件/内容断言。

片段测试增加队列与已加载曲目的身份校验，避免等待首段时误命中第二段；短片段先暂停定位，再真实恢复播放，截图期间暂停以保留采样。首次选曲及重启后播放等待生产 VLC 初始化成功，全部范围、状态、切段、缓存和编辑断言保持。先前 READY 超时未记录实际音频类，初始化竞态只是依据源码的推断；默认 WAV 首轮失败也未认定为生产缺陷。最终 AAC 运行确认使用真实 VLC 并通过，失败记录均保留。日志中的两条 VLC `reading while paused` 提示也保留；本轮未进行扬声器采集或声学评价。

首次沙箱测试受文件原子替换/回环连接限制；一次测试启动参数未正确引用含空格路径，未执行用例。实际回归在获准的独立测试环境运行并通过。自有服务生命周期首次误选未配置服务搜索路径的工具目录嵌入式 Python，报模块未找到；改用已有标准解释器验证当前服务源码。Python 首轮遗漏 XML 类型的既有测试 fixture 仅补请求头，原断言保持；中途单条未再复现的增量 error 及失败记录保留，最终完整回归为上表结果。

## 3. 复现入口与范围

先按项目现有依赖编译桌面测试；Java/Python 回归分别使用 Maven `verify` 与 `python -m unittest discover -s service/tests -v`。本机嵌入式 Python 的模块路径前提与标准解释器不同；使用既有测试驱动时需确认其实际生效的源码路径，不改全局解释器配置来绕过失败。

真实 FLAC 入口是 [phase4-local-smoke.ps1](../scripts/phase4-local-smoke.ps1)，支持 `-Profile Normal/HiDpi/All`、显式 `-Java` 和 `-RuntimeApp`。后者提供现有依赖 JAR/VLC，生产代码来自当前 `desktop/target/classes`，不加载旧主 JAR。可用 `-FixtureSource` 复用 `build/phase4-core-b/local` 下已验证的合成 FLAC；始终复制到新资料目录，不修改原件。没有 fixture 时使用现有 VLC 从自产 PCM 生成并核实样本数。

退出/回退入口复用 [playback-scene-smoke.ps1](../scripts/playback-scene-smoke.ps1)，指定 `-SmokeLauncher com.lumenmusic.Phase4CoreSmokeLauncher -ArtifactDirectory phase4-core-b/ui-regression -Profile Normal`；默认测下载中关闭，`LUMEN_PHASE4_SEEK_WINDOW=prepared` 测 READY 前，`ready` 测正常暂停回退，结束后恢复该环境变量。基线 `LUMEN_PHASE4_PROBE=1` 只适用于保存的原编译版本，不可对新源码运行后冒充原版本。

片段入口是 [segment-smoke.ps1](../scripts/segment-smoke.ps1)，本轮显式指定 JDK 23.0.2 和 `-AudioFixture build/phase4-core-b/ui-before-ready/normal-6715b1cf675c443faeb65404cef16015/cache-fixture.m4a`。该 AAC 来自本轮自产音频，应用代码仍来自当前 `desktop/target/classes`；未指定已安装目录。本轮未验证该脚本默认 WAV 的最终结果。

修改前已保存 314 文件的私有源码基线（本地路径：`build/phase4-core-b/baseline/a1f7a6a42d0340ff879b3943ef7d791a/workspace-baseline.zip`），SHA-256 为 `75c659add0e3e646410bdbd811dded7ea1e74b798289355c95ed694d40c8938f`；该快照和构建证据不属于公共源码候选。本轮未初始化 Git、改变依赖、更新真实安装、重打发行 ZIP 或发布；全量发行、长时/跨设备收听、外网来源与朋友独立复验属于 C/D 后续门槛。
