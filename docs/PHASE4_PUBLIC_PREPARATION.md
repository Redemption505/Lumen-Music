# 第四阶段 E：GitHub 源码与交付准备

日期：2026-10-09。本地准备完成后，用户已明确授权上传至 [Redemption505/Lumen-Music](https://github.com/Redemption505/Lumen-Music)。GitHub 实际身份确认仓库为私有、当前用户具有 Push 权限；保持现有可见性并保留原始 README 提交历史。本次交付源码与工程文档，独立朋友电脑和原首次启动闪退复测、第三方二进制公开材料及 Release 继续按实际条件处理。

## 1. 当前交付边界

应用基线仍为 [D 内测候选](PHASE4_RELEASE_CANDIDATE.md)：v0.2.2 / `lumen-0.2.2-20261009.2`，主 JAR SHA-256 `ce77801d4e0586c301d70591fae31cf08c8806ee3d867bdc3e55a898777e364c`。E 的工程脚本、CI 和文档是后续开发仓库内容；D 二进制包内的 `SOURCE.zip` 保留当时冻结的对应源码，不自动变成 E 仓库快照。

| 对象 | 本地准备内容 | 仍需的外部结果 |
| --- | --- | --- |
| Git 基线 | 开发根仓库的 `main` 关联准确 `origin`，正常合并远程初始历史后上传；精确暂存路径、原字节及忽略规则核验 | 若后续改变可见性或发布附件，核对相应范围及授权 |
| 源码范围 | 实际 Git 忽略探针、文本扫描和暂存内容比对；排除个人状态、密钥、备份、签名材料、缓存与工具记录 | 新增内容及公开素材来源仍须按实际对象复核 |
| CI | Ubuntu 基础测试及 Java/Python 集成；Windows 完整 Java 单元检查及 PowerShell/Maven Wrapper demo | 实际运行以 [GitHub Actions](https://github.com/Redemption505/Lumen-Music/actions) 为准，不以配置存在声明成功 |
| 发行材料 | 从当前 D 镜像、对应源码和校验匹配的本地缓存整理许可/NOTICE与来源证据 | 平台/歌词/素材范围及部分 native 组件完整对应材料，见[具体材料](PHASE4_RELEASE_MATERIALS.md) |
| 朋友试用 | [试用说明](FRIEND_TRIAL.md)、[反馈模板](FRIEND_FEEDBACK.md)与唯一 D 构建可供使用 | 非开发电脑独立使用、原闪退者复测 |

最初本地快照 `e3f917b` 采用单次自动化身份 `Codex <codex@localhost>`，没有代填用户姓名/邮箱或伪造之前历史。本轮提交沿用用户当前已配置的 Git 身份，并保留 GitHub 初始提交 `084d622`。历史 ZIP/bundle 的本地验证索引为 `build/phase4-public-e/final-verification.json`，上传核对记录为 `build/phase4-github-e/`；这些证据和内部备份不随源码上传。远程提交可从[仓库历史](https://github.com/Redemption505/Lumen-Music/commits/main/)查看。

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

D 的 Java322、Python362/2跳过、Rust7/1忽略及安装/音频证据保留其原构建与日期。E 未改应用生产代码。首次 Ubuntu 执行发现 Win32 `THUMBBUTTON` 布局测试误用于 Unix JNA 的 4 字节 WCHAR；该 ABI 断言仍保留完整预期，在 Windows job 的全量 Java 检查运行，Ubuntu 继续运行该类的通用逻辑和其余测试，不将这个 Windows 布局判断写成 Linux 桌面支持。

## 3. 使用本地快照与下一步

Git bundle 保留本地真实提交和 `main`，可在另一个目录用 `git clone '<bundle路径>' '<新目录>'` 恢复；仓库 ZIP 提供已提交文件，包含 workflow 和 `.gitignore`，不包含历史、工具、二进制安装镜像或私人材料。它是开发仓库快照，不能替代 D 二进制对应的 `SOURCE.zip`。

开发操作在包含 `desktop/`、`service/` 与 `stream-proxy/` 的工作区根目录执行；本地已有的 `Lumen-Music/` 子 clone 是独立仓库，不纳入父仓库源码清单。后续同步先检查所在根目录、分支和工作区，不盲目覆盖任一份修改。

源码已获授权提交私有仓库；未授权改变可见性或发布二进制。独立试用和来源材料未决时，继续使用明确标识的内测候选；公开候选或正式 Release 需满足第四阶段 §8.3 的实际条件。安卓仍按第四阶段的恢复条件单独安排。
