# 公开源码候选范围

核对日期：2026-10-11（第三方现行资料访问基线为2026-10-08）。用户已授权将复核后的源码提交至 [Redemption505/Lumen-Music](https://github.com/Redemption505/Lumen-Music)，保持仓库现有私有可见性。本次上传开发源码、脚本、规范和合成截图，不上传安装器、第三方二进制、个人资料或本地材料归档，不创建 Release。范围由实际 Git 忽略与索引检查验证，来源及二进制未决项见[第三方核对](THIRD_PARTY_REVIEW.md)和[E 材料准备](PHASE4_RELEASE_MATERIALS.md)。

## 1. 三种对象分别验收

| 对象 | 用途与范围 | 当前边界 |
| --- | --- | --- |
| 开发仓库候选 | 维护、构建、测试与必要文档；含 `.github/workflows/`、`.gitignore`、`.editorconfig` 及下表精确列出的协作模板 | 按下表白名单准备，逐文件复核后才能纳入真正提交；不伪造历史 |
| 发行包 `SOURCE.zip` | 与某个二进制对应的源码、构建信息及第三方源码材料 | 当前由 `scripts/package-release.ps1` 单独复制指定树；**不读取 `.gitignore`**。不能仅修改忽略规则就声称打包排除了凭据 |
| 可公开发行附件/截图 | 最终安装器、更新包、对应源码、许可证、声明、校验和，以及允许展示的界面 | 必须核对冻结版本的实际字节与来源；旧包哈希不代表本轮修改，截图和媒体另验授权 |

已交付候选 `lumen-0.2.2-20261010.1` 的 `SOURCE.zip` 包含485个文件，其中483个与该次构建的工作区输入字节一致，已收录 `.github/workflows/`、`.gitignore`、根 `AGENTS.md`、`CONTRIBUTING.md` 和 `CHANGELOG.md`。其主要源码树为 `desktop/src`、`desktop/.mvn`、`service/lumen_service`、`service/tests`、`stream-proxy/src`、`scripts`、`release`、`docs`，另有根 README、阶段文档、POM、Maven wrapper、Cargo.toml/Cargo.lock、三份第三方源码归档及 GPL 文本，并生成 `BUILD-INFO.txt`、`SOURCE-SHA256SUMS.txt`；不含 Git 历史。构建身份和验收见[候选说明](PHASE4_RELEASE_CANDIDATE.md)。

当前打包规则另将 `.editorconfig` 和下表四个协作模板精确纳入未来 `SOURCE.zip`。这些本轮新增文件不会自动进入已冻结的20261010.1归档；再次打包时必须与目标二进制、构建身份和实际字节一并核对，不能声称旧归档已包含它们。

更新打包脚本从目标完整包复制 `SOURCE.zip`、许可证及第三方声明，不独立重新生成源码。完整包材料变化后，更新包也要按新目标重新组装并校验。不能将修改后的工作区与旧 SOURCE 说成同一份源码。

## 2. 源码候选白名单

以下是允许**进入复核**的范围，不是目录内所有未来新增文件自动获准公开。必须先排除个人资料/生成物，再复核来源和扫描项。

| 范围 | 候选内容 |
| --- | --- |
| 根文件 | `README.md`、`AGENTS.md`、`CONTRIBUTING.md`、`CHANGELOG.md`、`第四阶段.md`、`LICENSE.txt`、`.gitignore`、`.editorconfig` |
| Java | `desktop/src/` 源码、测试与已核对素材；`desktop/pom.xml`、`desktop/mvnw`、`desktop/mvnw.cmd`、`desktop/.mvn/` |
| Python | `service/README.md`、`service/lumen_service/*.py`、`service/tests/` 源码与人工编写的合成 fixture；运行时生成资料不纳入 |
| Rust | `stream-proxy/src/`、`stream-proxy/Cargo.toml`、`stream-proxy/Cargo.lock` |
| 工程支持 | `scripts/` 必要脚本、`release/` 模板/安装器资源/许可证、`docs/` 当前说明及必要历史验收、`.github/workflows/` |
| 协作模板 | 仅 `.github/ISSUE_TEMPLATE/bug_report.yml`、`.github/ISSUE_TEMPLATE/feature_request.yml`、`.github/ISSUE_TEMPLATE/config.yml`、`.github/pull_request_template.md`；不放开整个 `.github/` 或 `ISSUE_TEMPLATE/` |
| 新增素材/示例 | 只纳入自产、明确许可或已获授权的素材；能读取、能播放、能下载均不是公开许可证据 |

当前首页截图候选为 [Lumen主题](assets/home-lumen.png)和[浅色主题](assets/home-light.png)：主代理从当前 `PersonalHome` 实际窗口取得，使用隔离合成资料，无个人曲库或私人路径。这是实际窗口截图，不代表安装版或外部朋友已经验收；两张图片已纳入最终候选清单与哈希，二进制图片的视觉/隐私依据来自该次主代理窗口验收。

本轮扫描器严格使用上表具体文件/目录，不使用“整个工作区全部纳入”。后续新增文件须追加复核；不能用本轮检查为未扫描文件背书。

## 3. 默认排除

| 排除对象 | 原因/处置 |
| --- | --- |
| `build/`、`desktop/target/`、`stream-proxy/target/` | 构建输出、工具、媒体、原生镜像、安装与运行证据、整目录备份可能混在其中；只按最终发布清单选择已核对产物 |
| `.planning/`、`.codex/`、`.agents/` | 本机工作记录与工具状态；不作为正式工程文档公开 |
| 缓存、虚拟环境、日志 | `__pycache__`、`.pyc`、`.venv`、`.pytest_cache`、`.cache`、`.idea`、日志不公开 |
| 个人状态 | `userdata/`、`personal-data/`，真实收藏/历史/日记、播放状态、偏好、歌词与音频/封面缓存、本地媒体路径及个人备份 |
| 凭据与恢复副本 | `ai-secret.json*`、`.ai-settings-recovery.json*`、`.env*`、`credentials.json*`、cookie 导出；AI 非敏感设置也可能含个人地址/查询参数，默认排除实际 `ai-settings.json*` |
| 签名材料 | 私钥、`.pfx`、`.p12`、`.key`、`.pem`、`signing/` 与 `certificates/`；公开证书如确有用途，须明确挑选并复核，不能把证书容器当成天然公开文件 |

`.gitignore` 是误纳入防线，不会移除已提交的文件，不会过滤独立打包脚本，也不会脱敏文档正文中的路径或截图。建立或更新 Git 基线时只暂存复核后的清单；检查实际暂存路径和内容，不能以一次 `add` 全部目录代替范围审阅。

可移植数据 ZIP 包含私人曲库、记录、歌词收藏、可能私密的歌单背景和本地引用；整目录备份还可能含密钥及恢复副本。诊断 ZIP 有内容白名单，而原始 `service.log`/标准输出没有同等保证。本轮没有读取真实备份、日志或数据文件，不能声称这些文件已经脱敏。公开演示只使用隔离合成资料或明确获准资料，用户自选媒体及截图单独确认。

## 4. 当前范围扫描与真实 Git 核对

[E 本地准备](PHASE4_PUBLIC_PREPARATION.md)使用 [repository-scope.py](../scripts/repository-scope.py)枚举上述白名单，通过实际 `git check-ignore --no-index -z --stdin` 验证22个合成排除路径；探针只使用内存路径及隔离的 Git 元数据，没有向源码放入秘密。暂存后 `--check-index` 逐项比对实际 index 的路径、stage、文件类型和 blob SHA-256，不能把全目录 `git add` 当成范围复核。

检查私钥头、常见服务密钥外形、疑似凭据字面量、本机路径及合成敏感标记。结果只记录相对文件名、类别、行号、数量与哈希，不保存命中正文或秘密值。证据在默认排除的 `build/phase4-public-e/scope/`：`candidate-inventory.json`、`scope-scan.json`、`candidate-paths.nul`，最终状态见本地E 验证索引（本地证据/归档：`build/phase4-public-e/final-verification.json`，不随源码上传）。

九个历史脚本中的真实个人目录、旧镜像和启动器默认值已改为显式目标或有效的项目相对位置；历史说明用环境变量及解释器占位保留事实。剩余路径外形检出为 `RadioClientTest.java` 的人工 demo URL 编码样本，直接构造、不读取文件，源码哈希绑定此分类。两张截图继续与 D 的合成窗口字节一致。图片、ICO及嵌套第三方归档没有被文本扫描当作内容权利证明。

六处凭据字面量均为四个既有合成测试/fixture内的直接输入，四文件整文件 SHA-256 与 C/D 已复核分类对应；变更这些文件后必须重新阅读上下文，不能以旧白名单放行新值。

| 文件/处数 | 合成上下文与依据 |
| --- | --- |
| `test_ai_boundaries.py` / 2 | TemporaryDirectory、DemoProvider、内存transport或本机随机端口；目标绑定及旧配置输入，不取真实配置 |
| `test_ai_settings.py` / 2 | 临时目录内配置分离/重启恢复、备份排除测试，不使用外网transport |
| `test_discovery_http.py` / 1 | 合成曲库、人工provider和替换的AI检索/transport，目标为本机测试server |
| `update_feedback_smoke_fixture.py` / 1 | 显式隔离fixture根目录，固定输入且AI关闭，不从真实个人目录取值 |

旧 D `lumen-0.2.2-20261009.2` 的冻结 SOURCE 为467成员、465个当时工作区输入，仅作为历史归档。当前已交付20261010.1的485/483验收事实见[候选说明](PHASE4_RELEASE_CANDIDATE.md)。本轮仓库呈现文件属于后续开发源码；文档和白名单更新不会改写任何已归档 SOURCE 或二进制。C 的457文件初始候选与测试分类证据保留在 `build/phase4-delivery-c/public/`，当前候选文件数以重新运行范围扫描所得的实际清单为准。

以上仅覆盖白名单内当前 UTF-8 文本、实际索引字节及已分类的合成/图片依据；未知编码、二进制嵌入秘密及素材权利仍需具体复核。未读取真实个人状态、密钥、原始日志或备份负载，不声明启发式检查能够证明没有任何秘密。

## 5. 公开前的具体门槛

1. 当前源码上传已获授权，目标为上述私有仓库。扩大到公开可见性、发布附件或 Release 时另核对准确范围及对应授权；源码上传不自动授权这些操作。
2. 完成剩余路径复核，结合已确认的合成测试字面量检查最终暂存清单/附件，拒绝个人数据、备份、媒体缓存、密钥和签名材料。新文件须重新进入清单。
3. 解决[第三方核对](THIRD_PARTY_REVIEW.md)中的实际音源/歌词接口与素材边界，并补齐匹配实际打包组件的材料。未知项目继续明确标为未决，必要的功能范围变更由用户审阅后决定。
4. 按冻结版本重新生成完整包与更新包材料、对应 SOURCE 及真实校验和，验证说明、源码、构建身份和二进制关系；仅整理文档不能替代新包验收。
5. 截图和示例使用获准材料，提供实际存在的入口和 CI/运行证据。没有发布就不编造下载链接，没有远程运行结果就不宣称 CI 通过。
