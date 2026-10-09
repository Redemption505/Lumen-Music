# 第四阶段 E：本地仓库与公开交付准备

日期：2026-10-09。用户本轮选择**先完成本地准备，暂不推送**，尚无独立朋友电脑或原首次启动闪退的复测结果。本地 Git、CI 支持和材料整理服务于后续维护；远程仓库、实际 Actions 和公开 Release 继续待确认。

## 1. 当前交付边界

应用基线仍为 [D 内测候选](PHASE4_RELEASE_CANDIDATE.md)：v0.2.2 / `lumen-0.2.2-20261009.2`，主 JAR SHA-256 `ce77801d4e0586c301d70591fae31cf08c8806ee3d867bdc3e55a898777e364c`。E 的工程脚本、CI 和文档是后续开发仓库内容；D 二进制包内的 `SOURCE.zip` 保留当时冻结的对应源码，不自动变成 E 仓库快照。

| 对象 | 本地准备内容 | 仍需的外部结果 |
| --- | --- | --- |
| Git 基线 | `main` 本地仓库，按复核清单精确暂存；检查索引路径、原字节与忽略规则，再保存真实提交及恢复 bundle | 目标地址、可见性与对应远程操作授权 |
| 源码范围 | 实际 Git 忽略探针、文本扫描和暂存内容比对；排除个人状态、密钥、备份、签名材料、缓存与工具记录 | 新增内容及公开素材来源仍须按实际对象复核 |
| CI | Ubuntu 基础测试及 Java/Python 集成；新增 Windows PowerShell/Maven Wrapper demo 路径 | GitHub-hosted runner、Temurin 21 和 Ubuntu 实际运行链接 |
| 发行材料 | 从当前 D 镜像、对应源码和校验匹配的本地缓存整理许可/NOTICE与来源证据 | 平台/歌词/素材范围及部分 native 组件完整对应材料，见[具体材料](PHASE4_RELEASE_MATERIALS.md) |
| 朋友试用 | [试用说明](FRIEND_TRIAL.md)、[反馈模板](FRIEND_FEEDBACK.md)与唯一 D 构建可供使用 | 非开发电脑独立使用、原闪退者复测 |

本地提交采用单次自动化身份 `Codex <codex@localhost>` 保存现有源码快照；没有代填用户姓名/邮箱，也不改全局 Git 身份。提交时间是实际创建时间，不伪造之前的开发历史。实际提交号、源码 ZIP 和 bundle 校验见本地[验证索引](../build/phase4-public-e/final-verification.json)；该索引及内部备份不纳入开发仓库。

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

本地使用 Windows、Python 3.12.14、OpenJDK 23.0.2、PowerShell 7.6.5，实际成功调用、故意错误 JDK 引起的 Maven 失败后服务清理，以及 Windows 推荐 demo 入口均通过。保留沙箱回环权限拒绝等早期工具诊断；没有以权限错误充当端口已释放。Windows 本地结果不能冒充 Ubuntu 或远程 Temurin 21 成功，workflow 当前只作静态核对。

D 的 Java322、Python362/2跳过、Rust7/1忽略及安装/音频证据保留其原构建与日期。E 未改应用生产代码，按源码影响范围复用这些结果；本轮实际新增执行与复用结果分别登记。

## 3. 使用本地快照与下一步

Git bundle 保留本地真实提交和 `main`，可在另一个目录用 `git clone '<bundle路径>' '<新目录>'` 恢复；仓库 ZIP 提供已提交文件，包含 workflow 和 `.gitignore`，不包含历史、工具、二进制安装镜像或私人材料。它是开发仓库快照，不能替代 D 二进制对应的 `SOURCE.zip`。

后续在用户指定准确远程、可见性及上传范围后，先核对已有远程历史，再连接/推送并取得真实 CI 结果。独立试用和来源材料未决时，继续使用明确标识的内测候选；接受剩余风险公开候选或正式 Release 必须作具体决定。安卓仍按第四阶段的恢复条件单独安排。
