# 第四阶段 D：Windows 内测候选

更新：2026-10-24。当前本地安装候选 **v0.2.2 / `lumen-0.2.2-20261024.2`**，包含第四轮前端改进及按钮对比度、首页悬停和底栏对齐修复。源码、实际安装 JAR 双缩放、同版本升级及原资料原生窗口/正常退出验收通过。

## 身份与入口

| 对象 | 身份与位置 |
| --- | --- |
| 主 JAR SHA-256 | `338f902082e530c1dc0b6712518e2ec0081bff3f4c826fa686a7abb6d26c2297` |
| 安装器 SHA-256 | `2d8dbbcde9dc6f1e5c9171f3f35e8a253d611629872c4f32151ecb069eaeff0a` |
| 完整包 | `build/releases/Lumen-Music-v0.2.2-Windows-x64.zip` |
| 增量包 | `build/releases/Lumen-Music-v0.2.1-to-v0.2.2-Windows-x64-Update.zip` |
| 对应源码 | 两包共用 SOURCE.zip；BUILD-INFO.txt 记录实际 BuildId/工具链，SOURCE-SHA256SUMS.txt 核对源文件 |
| 校验入口 | ZIP 旁 .zip.sha256、包内 SHA256SUMS.txt，以及发行核对脚本 |

首次安装和已有 v0.2.2 同版本升级使用完整安装器；增量仅适用 manifest 声明的 v0.2.1 基线。[Windows 指南](WINDOWS_GUIDE.md)说明安装、资料和备份范围。GitHub 当前提供私有源码，没有公开二进制 Release。

## 已验证及剩余范围

Java 325 项、双缩放扩展离屏、源码完整视觉与首页通过；实际安装主 JAR 的双缩放完整视觉与新交互通过，来源明确为安装目录。真实同版本升级、原资料首页/本地音乐/歌单/迷你返回和正常退出通过；捆绑 JVM/Python/Rust，自有进程及端口释放。升级前保留恢复备份，运行后业务文件原字节不变，未播放个人曲目。

本轮是歌单内查找定位，不是过滤；原路线图其他未实施功能列在[第四阶段前端](FRONTEND_PHASE4_ROADMAP.md)。细项与测试边界见[第四阶段验收](FRONTEND_PHASE4_ACCEPTANCE.md)和[前端交付](FRONTEND_RELEASE_ACCEPTANCE.md)。

旧[启动样本](STARTUP_ACCEPTANCE.md)、焦点与前三阶段记录保留原日期/构建范围。本轮没有新增独立朋友电脑、原闪退者反馈、系统重启首启、性能预算或公网长听结果；第三方公开条件仍按对应材料核对。Android 不属于本次 Windows 交付。
