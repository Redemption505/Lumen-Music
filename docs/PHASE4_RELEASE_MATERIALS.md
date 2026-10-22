# 第四阶段 E：本地第三方材料准备

更新：2026-10-22。当前候选为 `lumen-0.2.2-20261022.1`。与 10 月 10 日旧安装备份比较，784 个净运行文件中 783 个原字节一致，唯一变化为主 JAR；JRE346、Python34、VLC368、服务22、Rust1及依赖 JAR12 全字节一致（依赖 JAR12 为实际比较范围，不改写旧材料收集时的运行图口径）。JAR 250 个文件成员中 17 个变化，限首页/主界面类、主题 CSS 及构建属性；无新增、升级依赖。既有第三方材料仅按对应组件复用，旧镜像/SOURCE/ZIP 总摘要不验证新归档。新交付身份见[候选说明](PHASE4_RELEASE_CANDIDATE.md)。

以下材料总摘要仍绑定旧 D `lumen-0.2.2-20261009.2`。10 月 10 日构建相对旧 D 仅主 JAR 变化，既有比较继续保留其范围；本轮未重新收集第三方材料，也未取得二进制公开分发完成结论。

核对日期：2026-10-09。**已完成 D 内测候选的本地材料收集与精确版本清单；仍有原生组件、对应源码与内容来源未决项，未取得二进制公开分发完成结论。** 用户已另行授权源码上传至指定私有 GitHub 仓库；此页所述材料归档不在本次上传范围。材料收集没有新增或升级依赖，也没有联系朋友或服务方。

## 1. 对象与证据

基线为 v0.2.2 / `lumen-0.2.2-20261009.2`，主 JAR SHA-256 为 `ce77801d4e0586c301d70591fae31cf08c8806ee3d867bdc3e55a898777e364c`。收集器先验证 D 验证摘要（本地证据/归档：`build/phase4-release-d/final-verification.json`，不随源码上传） 中的完整包、内嵌 SOURCE 与主 JAR 哈希，再核对 SOURCE 的 BuildId、启动器及 Rust 二进制哈希，以及当前 Cargo.toml、Cargo.lock、POM 与该 SOURCE 的字节。新材料和本文没有改写已经冻结的 D 包；工作区新增文件不能被说成已进入 D 的 SOURCE。

可审阅的本地结果均在默认不公开的 `build/`：

- 材料 manifest（本地证据/归档：`build/phase4-public-e/materials/manifest.json`，不随源码上传）：每个材料的来源、归属、大小与 SHA-256，以及镜像全部文件、JAR/native 成员、精确 Rust 图和未决状态。
- 材料校验和（本地证据/归档：`build/phase4-public-e/materials/SHA256SUMS.txt`，不随源码上传）：411 份材料和 manifest 的字节校验；不把验证摘要自身写进该清单。
- Windows 普通依赖图（本地证据/归档：`build/phase4-public-e/materials/rust-windows-normal.txt`，不随源码上传）：从当前锁定源码离线重取，去重后按名称与版本排列，不输出开发者绝对路径。
- 本轮验证摘要（本地证据/归档：`build/phase4-public-e/materials/verification.json`，不随源码上传）：材料哈希、清单唯一性、路径边界与旧 C 图版本集合复核。
- 收集器复现与写入边界（本地证据/归档：`build/phase4-public-e/materials-collector-boundaries.json`，不随源码上传）：411 份材料原字节及镜像清单复现一致，拒绝覆盖既有输出或向被检查的 AppHome 新建材料目录。

材料目录的 `existing/` 是 D 已有文本/源码的原字节副本；`supplemented/` 是本轮从匹配版本的现成缓存补出的许可、NOTICE、作者致谢或元数据。收集器给 AppHome 的全部 785 个文件记录哈希，包括构建镜像标记 `app/.jpackage.xml`。与实际安装只读相减后，仅此标记不在安装目录，其余 784 个运行文件全部 SHA-256 相同；差项证据（本地证据/归档：`build/phase4-public-e/installed-image-difference.json`，不随源码上传） 记录该范围。现有 `install-phase3-release.ps1` 明确将该非运行标记排除于安装验收，因此 D 的 784 与这里的 785 是不同清单范围，不是应用负载缺失，也不是 Hidden 属性差异。

## 2. 已有与补齐材料

| 对象 | D 已有 | 本轮本地准备 | 仍需核对 |
| --- | --- | --- | --- |
| 项目 | GPLv3 全文、匹配 BuildId 的 SOURCE 与构建信息 | 保存既有全文及冻结身份 | 将来纳入 E 新源码/材料时重组受影响归档，不能沿用旧归档的哈希与内容声明 |
| JDK 23.0.2 | `runtime/legal/` 的 224 份模块许可、例外与第三方材料 | 所有文本与本机完整 JDK 相同，`java.dll`、`jvm.dll` 也相同；完整 JDK 元数据确认 `Oracle Corporation`、`23.0.2+7-58`、源码修订 `a13b02ce6f85`；保存来源证据 | 字节与元数据支持“来自这份本机 JDK”的推断，不证明原始下载链。缓存 `lib/src.zip` 仅为 Java 源文件，未复制为完整对应源码；原生源码、构建材料与原始获取凭据仍缺 |
| JavaFX 21.0.12 | base、controls、graphics、media 四个 Windows JAR，已有声明给源码 URL 规则 | 四个二进制与 Maven 缓存完全相同，保存四份发布 POM；记录 JAR 内 57 个以 native 扩展名结尾的成员哈希 | 四个 JAR 未找到 LICENSE/COPYING/NOTICE 命名成员，精确源码 JAR未缓存；完整许可、native/底层库归属与对应源码仍缺，57 个成员也不是已经审过的归属清单 |
| Python 3.13.15 | embedded `LICENSE.txt`、SOURCE 中 `Python-3.13.15.tgz` | 原字节保留许可及已有源码归档 | 继续保留内嵌组件各自条款；项目替换 `_pth` 的配置事实仍适用 |
| VLC 3.0.23 | 核心 DLL、365 个插件文件、GPLv2 `COPYING.txt` | 随包 368 个文件全部与现成 Windows ZIP 对应成员相同；补出同一 ZIP 的 AUTHORS 与 THANKS | AUTHORS/THANKS 不等于各插件许可证。libVLC、插件、底层库及构建材料必须按实际构建分别确认；仍没有完整对应源码材料 |
| JNA 5.13.0 / JPMS 5.14.0 | jna、jna-platform、jna-jpms、jna-platform-jpms 四 JAR 各有 `META-INF/LICENSE` | 保存四文本及精确 POM；补可复用的 Apache-2.0 标准全文候选，来源为锁定哈希匹配的 atomic-waker 许可文件；manifest 补齐四种构件的精确源码 URL | Java 部分拟采用 Apache-2.0，尚未形成完整分发方案。标准全文不是 JNA 自己的版权/NOTICE，两个 jna JAR 内合计 48 个 native 成员的来源和附加材料仍需审查，不能用通用文本覆盖 |
| vlcj 4.11.0 / vlcj-natives 4.8.3 | SOURCE 内两份对应 sources.jar，根 GPLv3 全文 | 保存原源码归档与精确发布 POM，二进制与本机缓存一致 | 二进制中没有独立许可成员，后续分发继续保持精确版本源码、版权和适用声明配对 |
| Rust Windows 普通图 | Cargo.lock 与六个直接依赖来源声明 | 当前普通图 98 个唯一 crate，98 个均找到 SHA-256 与锁文件一致的原始缓存 `.crate`；从中保留 161 份 LICENSE/COPYING/NOTICE/版权材料及其成员路径；包含 ring 的组合许可、Unicode-3.0 与 CDLA-Permissive-2.0 等实际全文 | 98 个包含 proc-macro 及其编译期闭包，不等价 98 个全部进入二进制。仍未核验最终链接来源及另行启用的 build-only 闭包；双许可选择与所有 native/编译材料完整性不因收集正文自动完成 |

Rust 锁文件的 143 个外部条目覆盖多种目标与依赖类别，不能作为 Windows 普通图的运行时清单。本轮只收集实际离线普通图中的精确版本，不把未进入该图的其他平台条目都复制进来。完整 `.crate` 缓存哈希提供材料版本归属证据；本轮复制许可文本，没有把所有 crate 源码归档新增到发行包。准确下载 URL 与缓存源码可用状态记在 manifest。

项目的 SVG、PNG、ICO 三图标在当前源码、D 的 SOURCE 与 JAR 中逐字一致，manifest 记录三组哈希；SVG 仅包含可读矢量图形，这些事实仍不能证明作者、生成过程或素材授权。本轮未读取个人图片或封面缓存。C 的合成资料首页截图证据继续按 [公开范围](PUBLIC_SCOPE.md) 使用，图片来源审查不由许可证复制代替。

## 3. 复现方法

[收集器](../scripts/collect-release-materials.py) 使用 Python 3.11 及以上的标准库、现有 Cargo 和缓存，不联网下载、不安装工具、不修改 AppHome 或发行 ZIP。默认从现有 Maven/Cargo 缓存及 PATH 中 `jpackage` 对应的 JDK 读取；可用 `--maven-home`、`--cargo-home`、`--jdk-home` 明确指定相同版本缓存。输出必须是工程 `build/` 内尚不存在的新目录，已有材料不会覆盖。

从工程根目录执行，将示例路径替换为已验证的 D 镜像与本机 Python；这里的 D 引用必须仍与验证摘要对应：

```powershell
python scripts/collect-release-materials.py `
  --app-home build/dist/20261009-110845-472/LumenMusic `
  --full-zip build/phase4-native-sync/previous-release-20261009.2/Lumen-Music-v0.2.2-Windows-x64.zip `
  --output build/phase4-public-e/materials-repeat
```

许可材料按原字节复制，所有源归档只读，不将本机缓存路径、错误输出或潜在凭据值写入清单。直接依赖变动、目标版本或镜像换版时需重新收集；冻结 D 来源核对失败时停止，不自动把旧验证摘要套到新包。

## 4. 官方资料与当前边界

2026-10-09 的有限官方核对取得 [JNA 5.14.0 许可文件](https://github.com/java-native-access/jna/blob/5.14.0/LICENSE)，支持其 Java 许可可选择 Apache-2.0 或 LGPL-2.1 及之后版本；取得 [OpenJFX jfx21 许可文件](https://github.com/openjdk/jfx/blob/jfx21/LICENSE)，但该分支资料不能单独证明本机 21.0.12 native 组件及材料完整。[OpenJDK 归档入口](https://jdk.java.net/archive/) 返回 403，没有用它确认本机 JDK 的原始下载链。许可正文收集主要依据已验证本机归档，未下载新依赖，也没有为失败页面编造原文或授权结论。

音源/平台接口、两类歌词、图标来源和公开展示素材仍遵循 [第三方来源核对](THIRD_PARTY_REVIEW.md) 的具体未决项。朋友独立电脑试用及原首次启动闪退反馈者复测仍无结果。此页完成本地可审阅材料准备；本轮源码上传的授权不覆盖材料附件、仓库改为公开或二进制 Release，材料复制也不代表上述条件已通过。
