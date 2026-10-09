# 开发与复现

本文说明源码启动和工程检查。Windows 10/11 x64 是当前桌面交付目标；Linux CI 检查工程行为，不证明 Linux 桌面发行、Windows 安装器或真实音频播放可用。安装用户无需配置本文的开发工具，使用交付包自带的 Java、Python、VLC 与 Rust 侧车。

## 1. 先决条件

| 用途 | 已有要求 | 说明 |
| --- | --- | --- |
| Java 编译/桌面 | 完整 JDK，推荐 21；`JAVA_HOME` 指向 JDK 根目录，或 `PATH` 能找到 `java`/`javac` | POM 编译目标为 17，JavaFX 为 21.0.12。Linux CI 用 JDK 21；既有 Windows 发行运行时为 23.0.2，不能把编译目标、CI JDK 与发行运行时混为一谈 |
| Maven | 使用 `desktop/mvnw.cmd`（Windows）或 `desktop/mvnw`（Linux） | Wrapper 3.3.4 固定 Maven 3.9.11；无需另装 Maven。首次需要下载已声明的 Maven/依赖，后续可用缓存离线检查 |
| Python 服务 | 完整 Python 3.10+，通过 `-Python`、`LUMEN_PYTHON` 或 `PATH` 指定 | 仅标准库，无 pip 依赖。Windows 的商店执行别名不一定是可用解释器。发行用嵌入式 Python 的 `._pth` 隔离模块搜索，不能直接当作通用源码解释器 |
| Rust 侧车 | 已安装的 Rust/Cargo 工具链 | `Cargo.lock` 纳入源码，检查/构建使用 `--locked`；目前没有 `rust-toolchain.toml`，工具链版本未固定 |
| Windows 本地播放 | x64 VLC 的 `libvlc.dll`、`libvlccore.dll` 与完整 `plugins/` | 设置 `LUMEN_VLC_HOME` 为该目录。协议及单元检查无需 VLC；不能用协议通过替代 FLAC/播放界面验收 |
| EXE 安装器构建 | 有 `jpackage` 的 JDK、WiX 3.14.1 的 `candle.exe`/`light.exe`、MSVC x64 C++17 与 Windows SDK | 自定义目录选择动作通过 Visual Studio Installer 的 `vswhere.exe` 找工具链，以 `/MT` 构建；仅 WiX 不足以构建当前安装器 |

启动和检查脚本不自动安装工具。缺失时提供路径或按项目既有清单准备环境；不要通过换版本掩盖问题。打包另用固定版本和 SHA-256 的 Python 3.13.15 / VLC 3.0.23 官方归档，详见现有 `prepare-python-runtime.ps1` 和 `package-windows.ps1`。打包会跳过 Maven 测试，不是验收入口。

Windows 示例均在项目根目录运行。本轮已分别在 PowerShell 7.6.5 和 Windows PowerShell 5.1.26100.9444 运行隔离 demo 协议入口。五个推荐脚本保存为 UTF-8 BOM，以免 PowerShell 5.1 按本地编码误读中文；编辑时应保留该编码。其他窗口、媒体及安装流程仍按各自验收范围记录，不因基础入口通过而扩大结论。下面的路径占位符须换成当前机器的真实输入：

```powershell
$env:JAVA_HOME = '<JDK 根目录>'
$env:LUMEN_PYTHON = '<完整 Python 的 python.exe>'
# 听本地文件时再设置：
$env:LUMEN_VLC_HOME = '<x64 VLC 目录>'
```

## 2. 从源码启动

首次准备当前依赖与 classes，不依赖仓库中的历史 `build/` 文件：

```powershell
./desktop/mvnw.cmd -B -ntp -f desktop/pom.xml test-compile
cargo build --manifest-path stream-proxy/Cargo.toml --release --locked
```

Rust release 输出位于 `stream-proxy/target/release/`，桌面启动器会寻找该文件并管理其生命周期。侧车暂不可用时会提示并使用已有 Python 音频代理；完整侧车验收仍须构建 Rust。首次 Wrapper 调用前应配置 `JAVA_HOME`；`desktop.ps1` / `check.ps1` 也可从 `PATH` 解析完整 JDK，并在调用期间设置 `JAVA_HOME`。

开发启动默认使用正常用户数据位置。验证新行为时，请在专用终端准备独立资料，再启动应用；不要用现有曲库测试写入、恢复或故障注入：

```powershell
$devState = Join-Path (Get-Location) ('build/dev-profile/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $devState -Force | Out-Null
$env:LOCALAPPDATA = $devState
$env:APPDATA = Join-Path $devState 'roaming'
$env:LUMEN_LIBRARY_PATH = Join-Path $devState 'library.json'
$env:LUMEN_HISTORY_PATH = Join-Path $devState 'history.json'
$env:LUMEN_PREFERENCES_PATH = Join-Path $devState 'preferences.properties'
$env:LUMEN_AI_SETTINGS_PATH = Join-Path $devState 'ai-settings.json'
./scripts/launch.ps1 -Provider demo -Python $env:LUMEN_PYTHON -JavaHome $env:JAVA_HOME
```

`demo` 使用虚构元数据与项目演示音频，不访问在线音源。`launch.ps1` 检查服务端口兼容性，启动桌面后由应用后台管理 Python 与 Rust；关闭桌面会释放它拥有的子进程。默认服务端口 8765，可通过 `-Port` 改变；Rust 默认端口 8766，由 `LUMEN_STREAM_URL` 控制。端口已有其他程序占用时应改用空闲端口，不终止未知进程。

只检查服务/协议时，可在两个终端运行（使用同一端口）：

```powershell
# 终端一：前台运行，Ctrl+C 关闭
./scripts/service.ps1 -Provider demo -Port 8765 -Python $env:LUMEN_PYTHON

# 终端二：编译当前测试并验证 Java ↔ Python
./scripts/desktop.ps1 -Smoke -ServiceUrl http://127.0.0.1:8765 -JavaHome $env:JAVA_HOME
```

`desktop.ps1` 不带 smoke 参数时运行桌面主入口；主入口仍会管理服务。不要将 `-Smoke` 与 `-UiSmoke` 混同：前者不打开播放窗口，只检查 HTTP/XML 搜索与队列行为。`-UiSmoke` 必须显式提供 `-SamplePath '<允许使用的本地音频>'`，不会再猜测历史媒体文件。`-RealSmoke`、`-OnlineSmoke`、`-JaySmoke` 是联网专用入口，不属于基础离线复现。

## 3. 基本检查入口

```powershell
# 全部工程检查：Python、Rust、Java、demo协议
./scripts/check.ps1 -Python $env:LUMEN_PYTHON -JavaHome $env:JAVA_HOME

# 单个组件；可选 Java / Python / Rust / Demo
./scripts/check.ps1 -Component Demo -Python $env:LUMEN_PYTHON -JavaHome $env:JAVA_HOME

# 已缓存 Maven 和 Cargo 依赖时显式离线；首次不要加此参数
./scripts/check.ps1 -Component Rust -Offline
```

`check.ps1` 不需要已有安装包、VLC、旧音频或旧 `build/`。它在 `build/check/<唯一目录>/` 写日志与独立状态，临时清除 AI 配置环境中的密钥/地址并恢复调用前环境；`-ArtifactDirectory` 可指定检查资料根目录。demo 服务使用新分配的本机端口，只停止该次检查创建的进程。失败时保留日志并返回错误。`-Offline` 对 Maven/Cargo 生效；它不代替第一次准备依赖，也不关闭本机 demo HTTP。

Linux/macOS 开发终端可直接运行与现有 CI 一致的基础命令。桌面平台支持仍以真实发行/运行验收为准：

```bash
PYTHONPATH=service python -m unittest discover -s service/tests -v
cargo test --manifest-path stream-proxy/Cargo.toml --locked
chmod +x desktop/mvnw
./desktop/mvnw -B -ntp -f desktop/pom.xml verify
```

demo 集成需要另外启动 `PYTHONPATH=service python -m lumen_service --provider demo --port 8765`，确认 `/api/health` 的 `status="ok"`、`provider="demo"` 后运行：

```bash
./desktop/mvnw -B -ntp -f desktop/pom.xml test-compile \
  org.codehaus.mojo:exec-maven-plugin:3.6.2:exec \
  -Dexec.executable=java -Dexec.classpathScope=test \
  '-Dexec.args=-cp %classpath com.lumenmusic.SmokeTest'
```

基础 Maven `verify` 排除 `SmokeTest` 与 `FxDesktopSmokeTest`，因此 demo 协议另跑；它也不等于全部场景 launcher 已运行。Python 部分联网测试按现有条件跳过，查看实际 `OK` / `skipped`，不要把跳过写成通过。

## 4. Windows 场景与历史 fixture

共用的 `scripts/playback-scene-smoke.ps1` 只使用显式目标，输入有两种语义：

| 参数 | 加载生产代码 | 必需输入 |
| --- | --- | --- |
| `-RuntimeHome '<运行时目录>'` | 当前 `desktop/target/classes` | 该目录的依赖 JAR 与 `vlc/`；兼容直接 JAR 目录，或有 `app/` 子目录的镜像根目录。Python 从 `-Python` / `LUMEN_PYTHON` / `PATH` 解析 |
| `-AppHome '<目标镜像或安装目录>'` | 目标 `app/LumenMusic.cfg` 指定的生产 JAR | 目标自身的依赖、VLC、service；默认目标 `app/python/python.exe`，可显式覆盖。记录生产 JAR 哈希并检查类加载来源 |

不要同时传两个参数。`RuntimeHome` 即使借用历史镜像的依赖，也只是当前源码验收，不能证明该镜像已包含新修复；`AppHome` 才验证目标 JAR，但仍需当前源码编译的测试 driver。脚本检查驱动及应用类的新旧状态，发现源码比编译类新时拒绝运行；先重新 `test-compile`，不能沿用旧类造成表面通过。这类测试不能替代安装、升级和用户交互验收。

```powershell
./desktop/mvnw.cmd -B -ntp -f desktop/pom.xml test-compile
./scripts/playback-scene-smoke.ps1 -RuntimeHome '<显式依赖与VLC目录>' `
    -Python $env:LUMEN_PYTHON -Java (Join-Path $env:JAVA_HOME 'bin/java.exe') -Profile Normal

# 推荐包装入口：显式目标镜像，不自动猜测旧build
./scripts/personal-home-smoke.ps1 -AppHome '<目标镜像根目录>' -Profile Normal
./scripts/visual-language-smoke.ps1 -AppHome '<目标镜像根目录>' -Profile All
```

`Profile Normal` 为正常缩放，`HiDpi` 为 1.5 倍，`All` 运行两者；`VisualOnly` 只覆盖视觉/布局，不能写成真实播放验收。共用脚本使用独立临时资料及本机 demo 服务；首页、收藏等包装入口复用各自合成 fixture。只有共用脚本接受 `RuntimeHome`，包装入口使用 `AppHome`。

候选长听与快速操作入口可直接从对应 SOURCE 编译测试驱动，不需要他人的 `target/test-classes`：

```powershell
./scripts/candidate-endurance-smoke.ps1 -AppHome '<目标镜像根目录>' `
    -Java (Join-Path $env:JAVA_HOME 'bin/java.exe') -Mode Local -Seconds 3600
# 在线20次切歌、10次远seek；需联网及可用来源
./scripts/candidate-endurance-smoke.ps1 -AppHome '<目标镜像根目录>' `
    -Java (Join-Path $env:JAVA_HOME 'bin/java.exe') -Mode Online -Seconds 0
./scripts/candidate-endurance-smoke.ps1 -AppHome '<目标镜像根目录>' `
    -Java (Join-Path $env:JAVA_HOME 'bin/java.exe') -Mode Online -Seconds 0 -PublicSegments
```

Java 参数必须指向有相邻 `javac` 的完整 JDK；默认 Python 使用镜像内置解释器，也可显式指定。驱动生成本地样本、隔离曲库和日志，在线结果需要按日期、目标构建与实际来源记录；网络不可用时保留失败，不能改成假在线通过。启动读取恢复可使用 `playback-scene-smoke.ps1 -SmokeLauncher com.lumenmusic.DiaryStartupSmokeLauncher`。

历史专项脚本保留其当时的复现条件，不作为干净环境的首轮入口：

- `phase3-smoke.ps1` 默认离线 Maven；缓存不完整时先按基本入口准备依赖。
- `phase3-installed-smoke`、`phase3-release-smoke`、`installed-online-smoke`、安装器/更新/个人状态专项脚本需要特定目标镜像、安装目录或合成 fixture；运行前读参数和前置检查，显式选择目标，不沿用脚本中的私人路径/历史 build 默认值。
- 更新与发行验收还需要旧/新两代镜像、完整第三方源码归档及对应构建身份；不能以基本检查成功代替这些输入。
- B 的 `phase4-local-smoke.ps1` 需要允许使用的 FLAC fixture、现有 VLC/JAR 目录及编译后的当前源码。该脚本不是首次依赖准备入口，历史结果见 [核心质量验收](PHASE4_CORE_ACCEPTANCE.md)。

## 5. Maven 图与打包用途

当前实际 Maven 图（Windows，2026-10-08）与 POM / `package-windows.ps1` 核对一致：

```text
lumen-desktop 0.2.2
├─ javafx-controls / javafx-media 21.0.12
│  └─ javafx-graphics / javafx-base 21.0.12（Windows classifier 原生JAR）
├─ jna / jna-platform 5.13.0（项目直接声明）
├─ vlcj 4.11.0
│  └─ vlcj-natives 4.8.3
│     └─ jna-jpms / jna-platform-jpms 5.14.0
└─ junit-jupiter 5.11.4（仅测试）
```

项目直接使用 `com.sun.jna` 的 Windows 任务栏 COM、全局快捷键与单实例唤醒 API；VLC 播放与响度分析也使用 JNA/vlcj。另一组 JPMS 坐标来自 vlcj-natives 的实际声明，当前打包脚本将它们一起复制到启动类路径。两组 core JAR 都含 `com.sun.jna.Native` 等同名类，不能理解为各自独立加载的两套原生桥接；当前类路径会按实际加载顺序选类。既有 `.4` 镜像的 `LumenMusic.cfg` 先列普通 JNA，再列 JPMS JNA；这是启动清单的静态核对，特定运行的实际来源需检查类加载日志。坐标共存本身既不能证明故障，也不能证明已经完成依赖收敛。本轮只核对来源和用途，保留 POM 版本与打包清单；删除或统一版本需另做 JavaFX/Windows API/VLC 的对应回归。

复查实际图可使用已缓存或 Maven 解析得到的 dependency 插件：

```powershell
./desktop/mvnw.cmd -B -ntp -f desktop/pom.xml `
    org.apache.maven.plugins:maven-dependency-plugin:3.7.0:tree -Dverbose
```

Wrapper 固定分发 URL，目前未配置 `distributionSha256Sum`；Rust 固定依赖解析而未固定工具链。应记录实际版本、解析图和构建身份；不能因此宣称全工具链字节级复现、漏洞扫描或第三方许可审核已完成。

## 6. CI 与实际交付验收分工

| 检查层 | 当前覆盖 | 不由该层证明 |
| --- | --- | --- |
| `.github/workflows/verify.yml` | Ubuntu、Temurin 21、Python 3.12基础测试与隔离 demo；Windows 完整 Java 单元检查及 PowerShell/Maven Wrapper demo | 远程实际成功、Windows JavaFX 界面、VLC/FLAC 发声、安装/升级/卸载及发行归档 |
| Windows 基本 `check.ps1` | 本机的相同工程入口与隔离 demo 协议 | 安装版已更新、用户原始数据回归、朋友独立使用 |
| Windows 场景和安装专项 | 显式 classes/JAR、合成或允许使用的媒体、缩放/UI/播放、真实安装目标与数据保护 | 未运行的其他目标镜像/系统、外部朋友实际反馈 |
| 发行候选 | 冻结源码/BuildId、必要完整回归、真实安装版、完整/增量/SOURCE字节与校验和 | 外部发布成功；发布还需真实远程、可见性及相应授权 |

`python scripts/ci-demo.py --offline` 可运行与 Ubuntu 集成 job 相同的 Java/Python 协议入口；使用独立资料、动态端口、demo 身份核验及失败时自有服务清理。Windows job 复用 `check.ps1 -Component Demo`。本地准备在 Windows / Python3.12.14 / JDK23.0.2 实际验收成功路径、Maven 失败后清理和 Windows demo；远程 Ubuntu/Windows runner 与 Temurin21 的结果以 [GitHub Actions](https://github.com/Redemption505/Lumen-Music/actions) 对应提交为准，详见 [E 源码交付](PHASE4_PUBLIC_PREPARATION.md)。

历史专项脚本需要显式提供实际目标，不再猜测个人安装目录、旧镜像或冻结服务。`phase3-installed-smoke` 需要 `InstalledHome`；`phase3-release-smoke` 需要 `AppHome`、`Java`、`FrozenService`；`release-playback-matrix` 需要 `AppHome`、`Java`；queue/smart/radio/version/segment/update-feedback 的目标与解释器按各脚本参数指定。安装镜像剥离 `runtime/bin/java.exe` 时，开发验收使用显式完整 JDK 的 `java.exe`，安装用户仍使用内置 JVM。

源码范围可用 `python scripts/repository-scope.py` 复核，精确暂存后运行 `--check-index`；检查器不代替暂存或上传。第三方本地材料收集需要 Python3.11+，见 [材料准备](PHASE4_RELEASE_MATERIALS.md)。

历史 B/C 结果保留当时的范围，最终候选与实际安装的结果另见 [D 候选验收](PHASE4_RELEASE_CANDIDATE.md)。工程结构和职责见 [架构](ARCHITECTURE.md)，阶段边界见 [第四阶段](../第四阶段.md)。
