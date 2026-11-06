# Windows 原生组件：对应源码与许可材料

更新：2026-11-06。源码仓库及已审阅历史已公开，二进制 Pre-release 尚未发布。以下材料绑定本地优先候选 `lumen-0.2.2-local-20261106.1` 的实际文件；日用完整版本和标准 ZIP 未替换。材料取得、对应关系、最终随包交付分别判断。

## 当前组件结论

| 组件 | 已核对的实际材料 | 剩余边界 |
| --- | --- | --- |
| OpenJDK 23.0.2 | 本机安装记录、[官方归档](https://jdk.java.net/archive/)和官方 SHA-256 对应 GPL OpenJDK Windows 发行物；224 份模块许可及核心 DLL 与本机完整 JDK 同字节。`SOURCE=a13b02ce6f85` 对应已取得的完整[源码修订](https://github.com/openjdk/jdk/tree/a13b02ce6f8545b9fce1263f0b5a219b55fb2522)，含 HotSpot、native、make/configure | 发行身份、精确修订源码取得及许可材料已核对；未重下或重建 209 MB 原二进制。最终包须保留模块许可及对应源码入口 |
| JavaFX 21.0.12+2 | 四份 Maven sources.jar；候选 JAR 内 57/57 DLL 与[官方 Gluon SDK](https://download2.gluonhq.com/openjfx/21.0.12/openjfx-21.0.12_windows-x64_bin-sdk.zip)同字节，取得实际四模块 18 份许可/声明，含 JPEG 10、GLib 2.82.4、GStreamer 1.26.5、libffi 3.4.4 | **精确 +2 native 源码/补丁与构建修订仍未关闭。** 已有 +1 仅作参考；`cbae...` 是 annotated tag 对象，实际 +1 commit 为 `416b7b09df6b76d7f3d78cf20b53fa22d0f35f94`。相邻 tag 或相同 Java 文件不证明 native 对应 |
| Microsoft VC/UCRT | 上述 57 DLL 中 5 个 VC runtime、41 个 UCRT/APISet；41/41 与现成 Windows SDK Redist 同字节且逐名列入[SDK REDIST](https://learn.microsoft.com/en-us/legal/windows-sdk/redist)。维护者已确认接受适用 Community 许可；Community 第4节、[VS REDIST](https://learn.microsoft.com/en-us/visualstudio/releases/2022/redistribution)、SDK RTF及CRT条款原文已保留 | 适用许可事实及46文件材料映射已核对；最终发行按各自条款保留权利说明。微软文件不适用项目/OpenJFX GPL；不需要为此购买付费VS |
| JNA 5.13.0 / JPMS 5.14.0 | Java/native 选择 Apache-2.0，libffi 另保留其原始许可；四构件来源、六份官方全文、代码版权归属与两个精确 tag 的 52/52 native 成员同字节检查通过，含旧统计漏计的 AIX `.a` | 材料对应已核对；原生源码归档仍是 native/libffi 子集，不冒充全仓库。没有上游 NOTICE 文件时不虚构；项目归属清单明确为整理材料 |
| VLC 3.0.23 | 两核心 DLL 与现有官方 tar 的 revision.txt/revision.c 同为 `3.0.23-2-0-g79128878dd`。368 文件保持；插件目录为363 DLL及2个libbluray JAR，363构建目标与原源树已定位 | **实际 Windows contrib/外部静态依赖的源码、补丁与许可闭包仍未关闭。** 116个配方候选和源文件声明不能证明全部实际启用，主VLC源码不能替代这些依赖 |
| Rust 侧车 | Windows normal/build 的101个锁定crate与167份许可/版权文本；EXE内rustc完整提交与1.98.1一致，补官方精确 rust-src 组件（3,628文件）和76份原许可及标准库版权 | 材料输入已核对。保留各crate的MIT/Apache选择及AND组合义务；标准库源码不是完整编译器仓库，也不声称101个组件均链接 |
| Python 3.13.15 | 实际文件与旧核验字节一致；精确CPython源码、完整Windows LICENSE、native构建版本/来源及 `_pth` 项目配置说明保留。OpenSSL3.0.21精确提交树24,662条未截断，NOTICE/COPYING路径覆盖检查及必要许可已补齐 | 基础与原生归属材料输入已核对；微软代码继续遵守其原条款，不统一归GPL。检查未扩大为所有Git子模块和每条源注释审计 |
| vlcj 4.11.0 / vlcj-natives 4.8.3 | 两份匹配的sources.jar、GPLv3全文、原版权注释及发布POM | 材料输入已核对；最终保留版本、版权和源码访问关系 |

## 可执行的材料与候选校验

新增[离线校验器](../scripts/verify-prerelease-materials.py)，仅用 Python 3.11+ 标准库，不联网、不解压、不启动应用、不修改被检查镜像。它校验完整文件清单、BuildId/profile、每个材料的大小/SHA-256、组件覆盖和明确的人工核对结论。`reference` 不满足 `source` 角色；哈希一致不能自动证明许可充分。

维护者持有实际镜像和材料后运行：

```powershell
python scripts/verify-prerelease-materials.py `
  --catalog build/prerelease-closeout-20261106/native-material-catalog-02.json `
  --app-home build/local-preview/lumen-0.2.2-local-20261106.1/image/LumenMusicPreview `
  --materials-root . `
  --output build/prerelease-material-check-new `
  --require-release-ready
```

`build/` 中的实际材料和catalog是本地维护证据，不随源码克隆提供；命令须换成操作者实际输入，输出用新目录。catalog含 `schemaVersion=1`、`image` 的 `buildId/releaseProfile/mainJar/files`、`materials` 的 `id/path/bytes/sha256/component/role`，以及 `components` 的 `id/imagePaths/materialIds/reviewState/reviewEvidence/openItems`。组件标识为jdk、javafx、jna、vlcj、vlc、rust、python及first-party的project；路径均相对于显式输入根，拒绝越界和重复。

本轮复核 **786个候选文件、1062件材料** 的身份/完整性通过；`nativeMaterialGatePassed=false`。严格模式按预期返回2，保留上述JavaFX/VLC缺口；缺文件、篡改或候选清单不符返回1。该工具不验窗口、声音、重启或独立电脑，不能替代整个发布门槛。

本地草稿已复制 **525份许可/声明原文**，附逐件校验和和 **120件source角色材料索引**。大源码归档仍引用已有原字节，未复制进草稿，草稿不是完成的SOURCE或可直接发布附件。首轮发现的23个旧测试字节码缓存已核对来源、时间/大小后移入证据目录保留；原786负载文件未更改，失败报告没有删除或放宽。

当前唯一机器结果为 `build/prerelease-closeout-20261106/material-check-02/verification.json`；分项原始输入、补充核对及草稿均保留在同一未上传目录。不要将私有报告、本机路径或不完整草稿直接作为公开附件。

## 下一步

1. 取得 `OpenJFX-release #124 / 21.0.12+2` 的权威native修订、补丁及构建清单；已有Java源码、精确SDK配对和相邻tag不重复收集。
2. 按实际VLC Windows构建启用清单，补contrib依赖源码/补丁及逐组件声明；不下载所有平台或把通用COPYING当完整闭包。[VideoLAN分发说明](https://docs.videolan.me/vlc-user/en/support/faq/legalconcerns.html)也要求按具体模块许可处理。
3. 上述两项关闭后，将精确许可、微软条款和对应源码方案真正纳入最终包，冻结SOURCE、BuildId和校验和，再结合[候选最小验收](LOCAL_PREVIEW_ACCEPTANCE.md)请求二进制发布确认。源码已公开不等于此流程已完成。
