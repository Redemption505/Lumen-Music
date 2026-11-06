# Windows 原生组件：对应源码与许可材料

更新：2026-11-06。当前材料核对对象是 `lumen-0.2.2-20261024.3` 使用的运行组件；本地优先构建不更换这些依赖。材料已在本地取得并校验，**尚未全部进入发行包，也未判定整个二进制可以公开再分发**。当前已取得、参考用途和真实缺口分别列明，避免重复收集或将相近版本当作精确来源。

## 已取得的材料

| 组件 | 精确身份和材料 | 已关闭与仍需核对 |
| --- | --- | --- |
| JDK / JRE 23.0.2 | 本机完整 JDK 的 `SOURCE=.:git:a13b02ce6f85` 对应 [OpenJDK 提交](https://github.com/openjdk/jdk/tree/a13b02ce6f8545b9fce1263f0b5a219b55fb2522)。已取得完整源码归档，含 HotSpot、桌面原生代码、make、configure 和构建说明；117,239,606 字节，SHA-256 `dda0120d9ac77f27ff774a2019a6c9b284dba830bcd2d8cfedbe3573f5d17e78` | 对应修订的源码取得已完成；既有 224 份模块许可继续保留。依据为本机元数据与相同组件字节，未重建二进制；原始 JDK 下载链和适用分发方案仍按实际材料核对，不能单凭供应商名套用另一发行物的许可 |
| JavaFX 21.0.12 | 已取得 base / controls / graphics / media 四个 Maven 精确 GAV 的 sources.jar；实际随包 `javafx.properties` 和 `VersionInfo.class` 均为 **21.0.12+2** | 四份 Java 源码已取得；native 精确源码/构建归属仍待确认。已取得的 [jfx21u 21.0.12+1](https://github.com/openjdk/jfx21u/tree/cbae1f63e74a91f90e0e713f70b62e3e8f1bc5f1) 只作同发行线参考，不能冒充 +2。另对四份sources.jar的2,137份Java文件与+1参考作有限对照：2,135同字节（忽略CRLF），HLSConnectionHolder有实际差异，VersionInfo为生成项未在相同路径找到，不能假定只是版本标记差别。查到 +1/+3 标签不代表 +2 不存在于其他来源；也不能用 Oracle JavaFX 下载许可套用 org.openjfx 的 Maven 二进制 |
| VLC 3.0.23 | 已取得 [官方源码 tar.xz](https://download.videolan.org/pub/videolan/vlc/3.0.23/vlc-3.0.23.tar.xz)及同目录官方 SHA-256 校验文件，实际匹配；26,486,988 字节 | VLC 本身源码取得和完整性已完成；所复制 Windows 插件及底层库的实际构建参数、依赖/许可映射仍需补齐。源码中 contrib 的规则/版本用于定位依赖，不能单独证明完整 Windows 二进制构建闭包 |
| JNA 5.13.0 / 5.14.0 | 四个实际构件对应 sources.jar；另取得两个精确 tag 的根许可及 native/libffi 源码子集。tag 提交为 `4962fd7758493b7395e86578705d8a32f6238872`、`c366bc4fd0c50a30ac8d2475603ec564f6611462`，子集每个文件有 SHA-256。六份官方许可原文及四 JAR 声明比对沿用上一轮 | Java 源码和原生源码子集已取得，拟采用 Java Apache-2.0 路径；native/libffi 的实际声明和随包 NOTICE 仍单独核对。子集不是完整跨平台仓库归档，不能声称验证了已不保留的全仓库归档哈希 |
| Rust 侧车 | 离线取得 Windows `normal,build` 保守依赖图 **101 个**锁定源码 `.crate`，共 12,470,342 字节；所有包 SHA-256 与 Cargo.lock 相符，收集 **167 份**许可/版权文件 | 补齐旧 98 项普通图之外的 cc、find-msvc-tools、shlex 三个构建组件；保留 ring 的 native 源和构建脚本。该图不是“101 个都链接进程序”的证明；最终打包须纳入适用文本、归属与源码取得清单 |
| Python / vlcj | Python 3.13.15、vlcj 4.11.0、vlcj-natives 4.8.3 已有匹配源码归档与材料，按已核对的不变字节复用 | 不重复下载或升级；最终包保留其许可、精确源码与 Python `_pth` 配置改动说明 |

Rust 的双许可选用方案为：存在 MIT 选项时选 MIT，Apache/BSL 选择 Apache-2.0；保留 `AND Unicode-3.0`、`AND ISC`、`AND BSD-3-Clause` 等组合义务。完整原始源码包中的其他许可文本也不删去；不把所有传递组件统一写成 MIT。

本地证据位于未上传的 `build/public-prerelease-followup-20261106/`：`native/manifest.json` 记录 15 件源码/参考归档和 JNA 子集成员，`native-verification.json` 是主代理逐项复核；`rust/manifest.json` 记录 101 个源码包、167 份文本、实际图及许可选择。旧材料身份与完整性核对仍见[材料准备](PHASE4_RELEASE_MATERIALS.md)。

## 下一步的有限补齐范围

1. 从 JavaFX 实际构件的上游/构建元数据定位 **21.0.12+2** 的 native 源码修订及其附带第三方许可。只取得相邻 tag 不关闭此项；目前没有为材料方便而替换依赖。
2. 对现有 VLC Windows 包取得其插件/底层库构建清单和对应来源方案，复用官方 3.0.23 源码与已有哈希，不重新抓取所有平台。不能用单个 COPYING 文本替代实际多组件归属。
3. 将 JDK、JNA native、Rust 及已有 Python/vlcj 的材料按最终实际分发清单组装，保留必要的完整许可、版权与 NOTICE；核对源码取得路径和对应身份。采用网络提供对应源码的方案时，发布位置应同时提供清楚且可用的访问入口，见[GNU 说明](https://www.gnu.org/licenses/gpl-faq.html#AnonFTPAndSendSources)和[VideoLAN 说明](https://docs.videolan.me/vlc-user/en/support/faq/legalconcerns.html)。
4. 完成材料组装后才冻结新的 SOURCE 与校验和；本地收集不是对旧安装 ZIP 的内容更新，也不是公开 Release。

网络来源与媒体内容边界另见[在线来源范围](ONLINE_SOURCE_SCOPE.md)，原生软件许可不能为媒体内容提供授权。首次公开与最终确认入口见[公开测试版准备](PUBLIC_PRERELEASE_READINESS.md)。
