# 第四阶段 D：Windows 内测候选

更新：2026-10-24。当前本地安装候选 **v0.2.2 / `lumen-0.2.2-20261024.3`**，包含前端第二阶段的收藏/本地播放全部、查找定位与Enter循环、沉浸状态及设置摘要，以及本轮列表刷新、唱针映射及交接修复。

2026-11-06已进入首次 Windows 公开测试版的本地收尾。历史隐私/项目素材核对及当前组件材料绑定已完成，独立使用和部分来源/再分发条件仍待关闭；见[公开测试版准备](PUBLIC_PRERELEASE_READINESS.md)。本页现有包及其哈希未变，尚未创建公开 Release。

另有尚未完成窗口验收的私有本地优先镜像 `lumen-0.2.2-local-20261106.1`；它使用独立程序与资料目录，不是本页日用安装版/标准ZIP的替换。新范围、已测/未测和入口见[公开测试版准备](PUBLIC_PRERELEASE_READINESS.md)。

## 身份与入口

| 对象 | 身份与位置 |
| --- | --- |
| 主 JAR SHA-256 | `3f4e3c28d46bdefccac7468bcdbc0640b4c78b8385c74bb24871ad7478f720fc` |
| 安装器 SHA-256 | `afc4eec2c1a7ccb4f7348edb227cb972bfcf2c1e29a0233096ee48f5e0a3eb43` |
| 完整包 | `build/releases/Lumen-Music-v0.2.2-Windows-x64.zip` |
| 增量包 | `build/releases/Lumen-Music-v0.2.1-to-v0.2.2-Windows-x64-Update.zip` |
| 对应源码 | 两包共用SOURCE.zip；BUILD-INFO.txt记录实际BuildId/工具链，SOURCE-SHA256SUMS.txt核对源文件 |
| 校验入口 | ZIP旁.zip.sha256、包内SHA256SUMS.txt及发行核对脚本 |

首次安装及v0.2.2同版本升级使用完整安装器；增量仅适用manifest声明的v0.2.1基线。[Windows指南](WINDOWS_GUIDE.md)说明安装和资料保护。GitHub提供私有源码，尚无公开二进制Release。

## 已验收与限制

Java330项通过；源码专项、完整视觉、首页分别通过125%/150%。实际安装主JAR的专项与完整视觉双缩放通过，类加载来源核对为安装目录。真实同版本升级及原资料首页、收藏、本地音乐、歌单、迷你返回和正常退出通过；捆绑JVM/Python/Rust、自有进程和端口释放分别核对。升级前保留恢复备份，业务文件保持，未播放个人曲目。

查找定位保留全部列表和播放队列，不是真正过滤。本地播放全部使用可见筛选结果，收藏播放全部使用完整收藏。唱针工程状态注入不等于真实网络缓冲复现。CSS及离屏驱动未改，复用对应输入的双离屏证据；新增控件另由本轮窗口检查覆盖。细项见[第二阶段验收](FRONTEND_STAGE2_ACCEPTANCE.md)、[演进路线](FRONTEND_STAGE2_ROADMAP.md)和[交付验收](FRONTEND_RELEASE_ACCEPTANCE.md)。

历史启动、焦点和前几轮报告保留原构建身份。本轮没有新增独立朋友电脑、原闪退者反馈、系统重启首启、性能预算或公网长听结果。第三方公开条件仍待核对，Android不属于本轮Windows冻结交付。
