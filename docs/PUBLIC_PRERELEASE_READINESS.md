# Windows 首次公开测试版与验收范围

2026-11-18，经维护者确认，[v0.2.2-preview.1](https://github.com/Redemption505/Lumen-Music/releases/tag/v0.2.2-preview.1) 已公开发布为 **Windows Pre-release**。便携版使用方法见 [Windows 使用指南](WINDOWS_GUIDE.md#公开便携测试版)。

## 1. 当前结论与冻结对象

公开构建为 `lumen-0.2.2-local-20261106.1`，标签指向 `7654a35b8cc13d9a48492e7bda8021be93545676`，对应 [CI 44239988681](https://github.com/Redemption505/Lumen-Music/actions/runs/44239988681) 两项通过。该便携版内置运行时并使用独立资料目录。日用安装候选 `lumen-0.2.2-20261024.3` 另见[内测候选说明](PHASE4_RELEASE_CANDIDATE.md)。

| 附件 | 字节数 |
| --- | ---: |
| [Windows x64 便携 ZIP](https://github.com/Redemption505/Lumen-Music/releases/download/v0.2.2-preview.1/LumenMusic-0.2.2-local-preview-windows-x64.zip) | 164675274 |
| [对应 SOURCE ZIP](https://github.com/Redemption505/Lumen-Music/releases/download/v0.2.2-preview.1/LumenMusic-0.2.2-local-preview-SOURCE.zip) | 504625209 |
| [SHA256SUMS.txt](https://github.com/Redemption505/Lumen-Music/releases/download/v0.2.2-preview.1/SHA256SUMS.txt) | 221 |

已核对 GitHub 附件 SHA-256 和匿名下载入口。SOURCE 随附第三方许可和构建材料，后续主分支文档更新只影响 GitHub 上的现行说明。125%／150% 基本窗口、合成音频和正常退出已通过，本机隔离便携补验覆盖导入去重、搜索、暂停和拖动、收藏与歌单重开、150% 迷你返回以及备份导出与预检。三项待验收见 §5，组件对应与溯源边界见[原生材料](NATIVE_REDISTRIBUTION.md)。

## 2. Git 历史与隐私结果

首次源码公开前审查了 16 个可达提交、全部 1,930 个本地 Git 对象中的 1,198 个 blob，合计约 20.8 MB 原始 blob；1,185 个文本、13 个二进制，没有超出当时 20 MiB 单文件上限的跳过项。范围包含普通分支/远程跟踪历史、两条本地工具快照引用和本地不可达 blob；提交正文、作者/提交者身份另外检查。当时远程只有 `main`。

| 检查结果 | 结论与边界 |
| --- | --- |
| 可发布历史 9 处文本命中 | 6 处合成密钥测试字面量、2 处同一测试 URL 的凭据/邮箱形状、1 处 `demo` 用户文件 URI；源码上下文已复核，既有四个合成 fixture 哈希未改。未发现真实令牌或个人路径泄露证据 |
| 其他本地对象 7 处命中 | 4 处为 Android URL 拒绝测试，3 处为不可达文档中的本机工作区链接；不属于拟公开 `main` 的提交历史，不作为“已清除”处理 |
| 提交身份 | 维护者在本轮明确同意保留自己的历史提交邮箱；公开历史后该信息可被读取。其余为 GitHub 服务身份及本地自动化占位，没有修改历史身份 |
| 敏感路径 | 可达对象路径未发现凭据、私钥或个人资料目录；二进制截图和素材单独审阅，见[素材来源](ASSET_SOURCES.md) |
| Git 推送边界 | 两条本地工具快照不是普通发布分支。后续只推送已审阅分支/标签，不使用 mirror 推送，不上传 `.git` 或整个工作区目录 |

这是有限规则扫描加命中人工分类，不是未知格式、嵌套归档、所有图片隐写或远程不可见对象的绝对无秘密保证。没有测试任何凭据是否有效，没有重写历史、清理对象、改 Git 配置或推送。本机脱敏证据位于未上传的 `build/public-prerelease-20261106/`。

## 3. 第三方与素材核对（组包前记录）

本轮把旧材料绑定到当前实际镜像：785 个镜像文件中 **784 个与旧材料清单同字节**，仅主应用 JAR 改变，无新增/缺失；旧 411 份材料逐一校验通过。当前完整包的冻结 POM、Cargo.toml/Cargo.lock 与工作区相同，SOURCE 中三份既有第三方源码归档和项目图标另有哈希记录。这允许复用相同组件的既有证据，**没有把旧材料总摘要冒充新包总摘要，也没有关闭原有材料缺口**。

| 对象 | 材料审阅结果 | 组包要求与范围 |
| --- | --- | --- |
| 项目图标、桌宠、演示截图 | 维护者确认图标和桌宠为本项目创作；截图使用合成曲库的来源证据与历史图像审查另见[素材清单](ASSET_SOURCES.md) | 未来替换素材时补来源；本轮确认不推广到用户自选图片/真实封面/歌词 |
| JNA 四个 JAR | 四构件声明、两个精确tag的52/52 native成员及Java/native的Apache-2.0、libffi原许可/版权对应已核对 | 原字节已入材料草稿，最终随包保留；原生源码子集不称全仓库，详见[原生材料](NATIVE_REDISTRIBUTION.md) |
| JRE、JavaFX、VLC | JDK精确源码、JavaFX完整CPU树/SDK声明工程对应、VLC实际Win64构建闭包与105许可选择已补齐 | 材料输入通过不替代最终SOURCE/NOTICE及静态重链接实施；JavaFX build124 checkout无直接证明，保留溯源限制 |
| Python、vlcj | 精确源码/版权材料及Python原生归属已补；OpenSSL精确树的NOTICE路径覆盖闭合，微软Community事实及实际SDK/REDIST映射明确 | 最终把已组装原文、源码访问关系和Python `_pth` 配置事实纳入实际发行物；不覆盖日用安装 |
| Rust 侧车 | 101锁定crate、167上游文本、精确rust-src1.98.1及标准库版权/76许可已核对 | 最终纳入适用全文/组合义务及源码方案；不是101个均链接的结论 |
| B 站、网易云 | 本轮仍无本项目用途的许可证据；网易云现行条文入口未取得可核正文 | 用户已选择本地优先：独立候选在UI与服务中实际关闭B站和网易云请求；日用full未改。该范围不再等待平台许可来启用这些已关闭功能，也不声称full用途获准 |
| LRCLIB | 官方现行纯文本 API 文档已读到：匿名访问仍可用，注册是可选项；需客户端身份标识、顺序节流及尊重 `Retry-After` | 客户端身份/退避已修并通过离线回归；本地候选保留LRCLIB运行时查询，不附抓取歌词库，不把服务器MIT当内容任意再分发许可 |

官方依据：[JNA 5.13.0](https://raw.githubusercontent.com/java-native-access/jna/5.13.0/LICENSE)、[JNA 5.14.0](https://raw.githubusercontent.com/java-native-access/jna/5.14.0/LICENSE)、[OpenJFX 21u 许可说明](https://raw.githubusercontent.com/openjdk/jfx21u/master/ADDITIONAL_LICENSE_INFO)、[VideoLAN 再分发说明](https://docs.videolan.me/vlc-user/en/support/faq/legalconcerns.html)、[GNU 对应源码说明](https://www.gnu.org/licenses/gpl-faq.html#AnonFTPAndSendSources)、[LRCLIB 当前 API](https://lrclib.net/docs.txt)、[B站用户协议](https://www.bilibili.com/blackboard/protocal/licence.html)。移动的上游分支只支持一般许可解释，不能代替实际二进制的精确版本材料。此次猜测的 `openjdk/jfx` 中 `jfx21.0.12` 标签 URL 返回 404，未把它记成已取得的精确许可来源。

完整组件分项及历史基线见[第三方核对](THIRD_PARTY_REVIEW.md)、[材料准备](PHASE4_RELEASE_MATERIALS.md)，公开包实际提供的 SOURCE、NOTICE 和构建输入见[原生材料](NATIVE_REDISTRIBUTION.md)。

## 4. 本轮实现与验证

- Python完整套件379项：377通过、2项既有公网默认跳过；歌词专测44项通过。最初全套两失败（full参数调用兼容和新增歌单顺序断言）已修正，失败日志保留；没有降低断言或修改credential fixture审阅哈希。
- Java默认profile定向7项通过、本地profile构建过滤/策略4项通过；编译通过。原330项和旧窗口证据保留旧版本范围；11月13日新增基本双缩放运行证据，不等于全量主题/窗口矩阵。
- 独立镜像786文件哈希、243生产class与编译输出、23服务模块与源码全部匹配；使用候选内置Python实际启动local-first服务，核对health、在线GET/HEAD拒绝、收藏夹导入拒绝及本地曲库写入读取。只用隔离资料和本机HTTP；脚本关闭了自己的服务子进程并确认端口释放，这不是播放器原生正常退出验收。
- LRCLIB身份为LumenMusic/0.2.2及项目URL；串行请求、完成后的短间隔、429/503共享冷却；支持整数及HTTP-date。长退避不截成更短时间，忙请求不无界排队；NetEase的full路径保留有限重试。本地首发注入空补充结果，不向网易云请求。新集成的真实公网行为未测。
- 11月9日原生材料集成核对已通过：786候选文件、2314件材料；JavaFX完整CPU源码与实际SDK工程对应审阅及VLC实际Win64 source/patch闭包已补齐。build124直接checkout未取得，仍披露溯源限制；11月18日已组装对应 SOURCE 和许可声明，并验证 VLC 源输入准备。完整 VLC 重链接未运行。见[精确材料](NATIVE_REDISTRIBUTION.md)。
- 11月13日同一候选的125%/150%基本窗口、合成音频及本地LRC、正常退出通过；用户确认实际有声。运行后786文件仍同字节，自有进程/端口均释放；首次探针检测错误及截图错配证据保留，不将重试算为新资料/重启样本。

- 11月18日便携 ZIP 解压及1,917项清单复核通过。从该解压目录以仅系统 `PATH` 运行包内服务。空资料、在线接口拒绝、收藏歌单跨重启、备份导出与预检通过。维护者确认目前没有独立电脑，允许本机模拟，独立设备覆盖仍缺。

## 5. 三项待验收

| 项目 | 当前证据 | 完成标准 |
| --- | --- | --- |
| Windows 重启后第一次启动 | 实际采样 **0/3**，探针已通过预检 | 三个不同 Windows 重启会话，各记录启动、窗口可操作、服务就绪、正常退出及资源释放。沿用[启动方案](STARTUP_ACCEPTANCE.md) |
| 独立 Windows 电脑试用 | 只有本机隔离解压、捆绑服务模拟及原生窗口结果 | 至少一台无开发工具的 Windows 10/11 x64 电脑，按公开便携版说明完成导入、播放、收藏歌单保存、备份预检和退出重开。原闪退者的复测单独登记 |
| VLC Win64 重链接验证 | SOURCE 的构建输入、校验补丁及对应指南已核对 | 按随包材料完成 Win64 全量编译，在修改静态依赖后重链接并替换 DLL，实际启动播放器验证播放。见[原生组件材料](NATIVE_REDISTRIBUTION.md) |

具体批次与窗口证据见[便携测试版验收](LOCAL_PREVIEW_ACCEPTANCE.md)。这三项随首次 Pre-release 披露，实际结果取得后再更新覆盖状态。
