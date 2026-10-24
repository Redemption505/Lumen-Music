# 第四阶段 E：GitHub 源码与交付准备

状态核对：2026-10-24。第四轮前端改进及三项界面修复已进入新候选 `lumen-0.2.2-20261024.2`，实际安装、原资料保留及原生操作与退出通过。源码交付目标仍为 [Redemption505/Lumen-Music](https://github.com/Redemption505/Lumen-Music) 的私有 `main`，普通提交推送，保留原历史和可见性。当前提交的运行结果从[工程检查](https://github.com/Redemption505/Lumen-Music/actions/workflows/verify.yml)查阅，不使用旧 CI 证明新代码。没有发布二进制 Release，独立试用与第三方公开条件仍待验。

## 1. 当前交付边界

当前交付身份、完整/增量包、同字节 SOURCE 及验收边界统一见[内测候选](PHASE4_RELEASE_CANDIDATE.md)和[前端交付验收](FRONTEND_RELEASE_ACCEPTANCE.md)。旧候选及归档保留可恢复副本；本轮 SOURCE 按新候选冻结，后续工作区内容不自动成为冻结包内容。

| 对象 | 本地准备内容 | 仍需的外部结果 |
| --- | --- | --- |
| Git 基线 | 开发根仓库的 `main` 关联准确 `origin`，正常合并远程初始历史后上传；精确暂存路径、原字节及忽略规则核验 | 若后续改变可见性或发布附件，核对相应范围及授权 |
| 源码范围 | 实际 Git 忽略探针、文本扫描和暂存内容比对；排除个人状态、密钥、备份、签名材料、缓存与工具记录 | 新增内容及公开素材来源仍须按实际对象复核 |
| 仓库呈现与协作 | README、[文档导航](README.md)、贡献指南、Issue/PR 模板和 EditorConfig 已交付，精确白名单和后续 SOURCE 打包规则已同步 | 不因新增规范批量重写源码或更新旧安装包；按真实反馈维护 |
| 历史 CI | 旧基线 `0cdf47b` 的 [Actions run 38150046491](https://github.com/Redemption505/Lumen-Music/actions/runs/38150046491) 两项任务成功；详细范围见[验证结果](VALIDATION.md) | 新提交取得各自结果；当前状态文档不冒充已由旧 run 验证，CI 不代替原生安装/外部试用 |
| 发行材料 | 旧 D 材料继续保留；新旧镜像的 Python/VLC/JRE/运行依赖/侧车字节相同，按实际组件复用 | 平台/歌词/素材范围及部分 native 组件完整对应材料，见[具体材料](PHASE4_RELEASE_MATERIALS.md) |
| 朋友试用 | [试用说明](FRIEND_TRIAL.md)、[反馈模板](FRIEND_FEEDBACK.md)与唯一候选身份 | 非开发电脑独立使用、原闪退者复测 |

最初本地快照 `e3f917b` 采用单次自动化身份 `Codex <codex@localhost>`，没有代填用户姓名/邮箱或伪造之前历史。后续源码交付沿用用户已配置的 Git 身份，并保留 GitHub 初始提交 `084d622`。历史 ZIP/bundle 的本地验证索引为 `build/phase4-public-e/final-verification.json`，上传核对记录为 `build/phase4-github-e/`；这些证据和内部备份不随源码上传。远程提交可从[仓库历史](https://github.com/Redemption505/Lumen-Music/commits/main/)查看。

## 2. 工程入口与验证

新增 [repository-scope.py](../scripts/repository-scope.py) 只枚举明确源码树，通过真实 `git check-ignore --no-index` 检查排除，用 `--check-index` 比对暂存文件路径和 blob 原字节。输出仅含相对文件名、类别、计数和哈希，不能将启发式扫描写成全面的秘密或素材授权证明。既有六处测试字面量绑定已复核的合成 fixture；人工 URL 编码测试中的示例路径保留其测试作用。

```powershell
python scripts/repository-scope.py
# 复核清单后按 NUL 路径列表精确暂存；脚本自身不会暂存
git add --pathspec-from-file=build/phase4-public-e/scope/candidate-paths.nul --pathspec-file-nul
python scripts/repository-scope.py --check-index
git status --short
git log -1 --oneline
git remote -v
```

九个历史验收脚本移除了个人目录及自动猜测旧镜像、启动器和冻结服务的默认行为。需要真实目标的入口要求显式参数，Python 可显式提供或使用 `LUMEN_PYTHON`；保留原 GUI 场景及断言。环境恢复同步采用 D 已确证的缺省处理：原值为 null 时真正删除 `Env:` 项，避免 PowerShell 7/.NET10 将未设置变量恢复成空字符串；非 null 值按原值恢复。本轮补验参数拒绝、PowerShell 5.1/7 解析及真实环境循环的父/子进程恢复，没有重新运行历史 GUI 场景。相关操作见 [开发与复现](DEVELOPMENT.md)。历史验收文档仅将个人路径替换为环境变量或占位，日期、构建和结果保留。

新增 [ci-demo.py](../scripts/ci-demo.py) 运行既有 Java ↔ Python SmokeTest，使用独立资料、动态端口和 demo 健康身份核验，并在失败时释放自己启动的服务。Ubuntu workflow 使用此入口；Windows job 使用已有 `check.ps1 -Component Demo`。保留已有 Actions 主版本及项目依赖版本，workflow 使用只读仓库权限并关闭 checkout 凭据持久化。

```powershell
# Maven 缓存完整时可离线；不带 --offline 时按现有声明获取依赖
python scripts/ci-demo.py --offline
./scripts/check.ps1 -Component Demo -Python '<完整解释器路径>' -JavaHome '<完整JDK目录>' -Offline
```

本地准备使用 Windows、Python 3.12.14、OpenJDK 23.0.2、PowerShell 7.6.5，实际成功调用、故意错误 JDK 引起的 Maven 失败后服务清理，以及 Windows 推荐 demo 入口均通过。Windows 本地结果不能冒充 Ubuntu 或远程 Temurin 21 成功；推送后的执行与结论从上述 Actions 入口核验，源码、窗口、安装与音频范围仍分别验收。

D 的 Java322、Python362/2跳过、Rust7/1忽略及安装/音频证据保留其原构建与日期。首次 E 源码交付未改应用生产代码，后续缓存修复另见[稳定性验收](PHASE4_STABILITY_ACCEPTANCE.md)。首次 Ubuntu 执行发现 Win32 `THUMBBUTTON` 布局测试误用于 Unix JNA 的 4 字节 WCHAR；该 ABI 断言仍保留完整预期，在 Windows job 的全量 Java 检查运行，Ubuntu 继续运行该类的通用逻辑和其余测试，不将这个 Windows 布局判断写成 Linux 桌面支持。

首次 E 上传源码提交 `bcfbe71f5035d315b9860edea166bba014086c58` 的 473 个文件与当时审阅字节一致；[历史 CI](https://github.com/Redemption505/Lumen-Music/actions/runs/37909117734) 的 Ubuntu 基础检查及双平台 demo 成功，Windows Java 322 项零失败、零跳过。当前基线及已记录 CI 以上表为准，历史结果保留对应提交，后续文档不自动获得旧 CI 验证。

10 月 10 日核心提交 `76c0d67` 的 run `38026054219`：Windows Java 325 项零失败、零错误、零跳过；Ubuntu Java 325 项中 323 通过、2 项 Windows-only 检查跳过（`GlobalHotkeysTest`、`WindowsTaskbarControlsTest`），由 Windows job 覆盖。Python 364 项中 362 通过、2 项既有公网测试默认跳过；Rust 7 通过、1 项辅助测试忽略；启动报告工具 8 项通过。跳过/忽略不计为通过，CI 不替代实际安装和原生运行。

10 月 11 日仓库更新 `0cdf47b` 的 run `38150046491` 已独立核对双 job 成功，各组件数量和跳过范围与上段相同，双平台 demo 通过。该轮 112 个本地文档链接/锚点、487 个源码候选的范围与暂存字节、22 个忽略探针及 11 个打包 fixture 场景通过；7 个呈现文件的远程字节一致。GitHub API 的 README/导航渲染已核对，未登录浏览器未完成私有页面视觉复验。证据在不上传的 `build/repo-presentation/`；这次仓库更新未重新打包安装器或冻结 SOURCE。

## 3. 使用本地快照与下一步

Git bundle 保留本地真实提交和 `main`，可在另一个目录用 `git clone '<bundle路径>' '<新目录>'` 恢复；仓库 ZIP 提供已提交文件，包含 workflow 和 `.gitignore`，不包含历史、工具、二进制安装镜像或私人材料。它是开发仓库快照，不能替代 D 二进制对应的 `SOURCE.zip`。

开发操作在包含 `desktop/`、`service/` 与 `stream-proxy/` 的工作区根目录执行；本地已有的 `Lumen-Music/` 子 clone 是独立仓库，不纳入父仓库源码清单。后续同步先检查所在根目录、分支和工作区，不盲目覆盖任一份修改。

源码与仓库优化交付已完成，下一步以[第四阶段 §8.5](../第四阶段.md#85-下一轮优先工作与完成标准)为准：三份隔离新资料及统一首启计时已于 10 月 18 日完成，接下来按[启动方案](STARTUP_ACCEPTANCE.md)补系统重启首启，朋友独立试用并行，再按具体证据做有限修复。不再把已完成的安装、原资料重复启动或 README 整理列为待办。独立试用和来源材料未决时保留内测标识；改变可见性、二进制 Release 和安卓恢复分别按实际门槛及相应授权处理。
