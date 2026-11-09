# Windows 原生组件：对应源码与许可材料

更新：2026-11-09。源码仓库及已审阅历史已公开，二进制 Pre-release 尚未发布。以下材料绑定本地优先候选 `lumen-0.2.2-local-20261106.1` 的实际文件；日用完整版本和标准 ZIP 未替换。材料取得、对应关系、最终随包交付分别判断。

## 当前组件结论

| 组件 | 已核对的实际材料 | 剩余边界 |
| --- | --- | --- |
| OpenJDK 23.0.2 | 本机安装记录、[官方归档](https://jdk.java.net/archive/)和官方 SHA-256 对应 GPL OpenJDK Windows 发行物；224 份模块许可及核心 DLL 与本机完整 JDK 同字节。`SOURCE=a13b02ce6f85` 对应已取得的完整[源码修订](https://github.com/openjdk/jdk/tree/a13b02ce6f8545b9fce1263f0b5a219b55fb2522)，含 HotSpot、native、make/configure | 发行身份、精确修订源码取得及许可材料已核对；未重下或重建 209 MB 原二进制。最终包须保留模块许可及对应源码入口 |
| JavaFX 21.0.12+2 | 已取得完整July CPU源码`edd890290afc8f80b9cea901615c1aba9bdf8a83`；27,503文件重建Git根树与官方`36e26f11e3dcabce119d3470d17f4dec7d21d7b0`一致，含native/build/第三方声明。六份native声明与实际SDK同字节，复用57/57 DLL配对 | 按[Gluon同次CPU发布](https://gluonhq.com/news/2026-07-21-july-2026-critical-patch-update/)、[OpenJDK CPU合并](https://github.com/openjdk/jfx21u/pull/136)及完整树/SDK声明一致性，工程审核接受该产品源码版本对应；**这是有官方事实支持的推断，不是供应商直接checkout证明**。build #124具体checkout/编译命令和逐位重建未取得，作为溯源限制保留；不再使用+1参考源码代替 |
| Microsoft VC/UCRT | 上述 57 DLL 中 5 个 VC runtime、41 个 UCRT/APISet；41/41 与现成 Windows SDK Redist 同字节且逐名列入[SDK REDIST](https://learn.microsoft.com/en-us/legal/windows-sdk/redist)。维护者已确认接受适用 Community 许可；Community 第4节、[VS REDIST](https://learn.microsoft.com/en-us/visualstudio/releases/2022/redistribution)、SDK RTF及CRT条款原文已保留 | 适用许可事实及46文件材料映射已核对；最终发行按各自条款保留权利说明。微软文件不适用项目/OpenJFX GPL；不需要为此购买付费VS |
| JNA 5.13.0 / JPMS 5.14.0 | Java/native 选择 Apache-2.0，libffi 另保留其原始许可；四构件来源、六份官方全文、代码版权归属与两个精确 tag 的 52/52 native 成员同字节检查通过，含旧统计漏计的 AIX `.a` | 材料对应已核对；原生源码归档仍是 native/libffi 子集，不冒充全仓库。没有上游 NOTICE 文件时不虚构；项目归属清单明确为整理材料 |
| VLC 3.0.23 | 官方[pipeline674296](https://code.videolan.org/videolan/vlc/-/pipelines/674296) / [Win64 job2403067](https://code.videolan.org/videolan/vlc/-/jobs/2403067)及容器digest/实际参数已绑定。96待构建target、3容器header已收敛；88普通源码SHA512、3 Git源完整树、263实际补丁、585原声明核对通过。105许可分项及具体致谢已整理 | **本次release未使用旧推导的prebuilt contrib包。** 完整源码、补丁与声明输入已补齐；不声称全部target都链接或逐位重建。最终须落实静态copyleft的源码/重链接要求，使用经过筛选的公开源码清单，不能把原含NC测试数据的mysofa源档自动公开 |
| Rust 侧车 | Windows normal/build 的101个锁定crate与167份许可/版权文本；EXE内rustc完整提交与1.98.1一致，补官方精确 rust-src 组件（3,628文件）和76份原许可及标准库版权 | 材料输入已核对。保留各crate的MIT/Apache选择及AND组合义务；标准库源码不是完整编译器仓库，也不声称101个组件均链接 |
| Python 3.13.15 | 实际文件与旧核验字节一致；精确CPython源码、完整Windows LICENSE、native构建版本/来源及 `_pth` 项目配置说明保留。OpenSSL3.0.21精确提交树24,662条未截断，NOTICE/COPYING路径覆盖检查及必要许可已补齐 | 基础与原生归属材料输入已核对；微软代码继续遵守其原条款，不统一归GPL。检查未扩大为所有Git子模块和每条源注释审计 |
| vlcj 4.11.0 / vlcj-natives 4.8.3 | 两份匹配的sources.jar、GPLv3全文、原版权注释及发布POM | 材料输入已核对；最终保留版本、版权和源码访问关系 |

## 可执行的材料与候选校验

新增[离线校验器](../scripts/verify-prerelease-materials.py)，仅用 Python 3.11+ 标准库，不联网、不解压、不启动应用、不修改被检查镜像。它校验完整文件清单、BuildId/profile、每个材料的大小/SHA-256、组件覆盖和明确的人工核对结论。`reference` 不满足 `source` 角色；哈希一致不能自动证明许可充分。

维护者持有实际镜像和材料后运行：

```powershell
python scripts/verify-prerelease-materials.py `
  --catalog build/native-materials-20261109/native-material-catalog.json `
  --app-home build/local-preview/lumen-0.2.2-local-20261106.1/image/LumenMusicPreview `
  --materials-root . `
  --output build/prerelease-material-check-new `
  --require-release-ready
```

`build/` 中的实际材料和catalog是本地维护证据，不随源码克隆提供；命令须换成操作者实际输入，输出用新目录。catalog含 `schemaVersion=1`、`image` 的 `buildId/releaseProfile/mainJar/files`、`materials` 的 `id/path/bytes/sha256/component/role`，以及 `components` 的 `id/imagePaths/materialIds/reviewState/reviewEvidence/openItems`。组件标识为jdk、javafx、jna、vlcj、vlc、rust、python及first-party的project；路径均相对于显式输入根，拒绝越界和重复。

本轮复核 **786个候选文件、2,314件材料** 的身份/完整性通过，`nativeMaterialGatePassed=true`；严格模式退出0。该门槛是显式组件工程审阅与材料输入核验，不代表最终成品、窗口/声音、重启或独立电脑已验收，也不声称供应商直接证明了JavaFX build #124 checkout。校验器及8项回归代码未变，复用对应通过结果。

已有525份许可草稿继续保留；新增VLC声明文件包含765段可追溯原文/源头声明，具备IJG、FreeType及MIT-KEMAR具体致谢。原始非UTF8内容有可逆映射和原字节哈希；没有改写条款或把所有组件统一归为GPL。最终NOTICE/SOURCE成品尚未组包。

当前机器结果为 `build/native-materials-20261109/material-check/verification.json`；主代理增量核验见同目录`integration-verification.json`。`source-publication-manifest.json`显式选择212件source角色材料，其中VLC为92件来源档；这不是已生成SOURCE.zip。`build/`材料不随源码克隆提供，原始CI日志/下载记录及本机证据保持私有。

## 下一步

1. 按显式公开清单组装匹配本候选的SOURCE、原许可、VLC声明和构建映射，冻结实际BuildId/校验和。不得按catalog的source角色全量复制：原mysofa77MB档含未编译测试数据的独立NC条款，保持私有；改用已排除16份数据、保留82文件原字节及可再分发默认数据的源码变体。该默认数据适用1994 MIT Media Laboratory署名条款，不是MIT软件许可证。
2. 对新mysofa变体采用随附的明确SHA512替换补丁及本地tarballs映射；不能冒充原上游档或绕过校验。CMake、代码、实际配方行为不变。Git来源包按精确commit/完整Git树提供，不虚构不同压缩封装的上游校验值；最终构建过程尚未执行。
3. 资源稳定且重新安排接管后完成[候选窗口验收](LOCAL_PREVIEW_ACCEPTANCE.md)，再补既定重启与独立电脑。实际二进制发布前由用户审阅附件、源码、已知限制并确认；本次只补材料，没有替换日用安装或发行ZIP。
