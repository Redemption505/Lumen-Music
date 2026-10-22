# 自动视觉焦点验收

更新：2026-10-22。本轮修复测试驱动的焦点前置条件与迷你窗基准采样，未修改产品 Java/CSS、依赖、数据格式或安装目录。目标仍是 v0.2.2 / `lumen-0.2.2-20261022.1`，生产 JAR SHA-256 `15a59075079570a04836b0f83f73b9eab1cbcecc207220ae199a5336853ccc53`。

## 结论与依据

原断言只检查 `Scene.getFocusOwner()`，随后把边框像素不变解释为样式缺陷；未检查 `Window.isFocused()` 与 `Node.isFocused()`。这三个状态不同：[OpenJFX 21 Scene 文档](https://openjfx.io/javadoc/21/javafx.graphics/javafx/scene/Scene.html#focusOwnerProperty())明确说明，窗口未激活时 Scene 的焦点所有者仍可存在；[Node 文档](https://openjfx.io/javadoc/21/javafx.graphics/javafx/scene/Node.html#focusedProperty())要求可见且激活的窗口才能产生实际输入焦点。

受控实验使用同一生产 JAR、同一主题与控件：窗口激活时原像素判定得到 592 个边界变化像素；显示本测试拥有的另一窗口后，Tab 仍把 Scene 的焦点所有者移到目标，但目标 `isFocused=false`，边界变化为 0；关闭该窗口并重新激活后恢复 592。没有修改产品 CSS。这确认了测试前置条件遗漏，能够复现原错误；旧失败日志没有保存窗口激活状态，不能反推当时是哪一个窗口抢占焦点，也不能断言已经证明旧运行的唯一原因。

复验另捕获迷你窗基准颜色错误：从返回按钮切换焦点后，旧测试在 `applyCss()` 前读取边框，可能读到上一状态。现先确认按钮未聚焦并刷新 CSS，再读取基准，不以旧计算样式作为对照。

## 修复与回归

- 首页和迷你窗共用测试辅助 `FocusPixelAssertions`。采样前要求窗口可见/激活、起始控件实际聚焦、目标未聚焦；通过 JavaFX Tab/Shift+Tab 事件遍历后，要求目标同时拥有 Scene 焦点及实际输入焦点，再刷新 CSS/布局并比较快照。
- 窗口激活只请求一次，观察限时 3 秒；未获得焦点或采样时再次失焦明确失败，不使用置顶、反复抢焦点或重跑掩盖干扰。以状态观察替代固定 100/120ms 睡眠；没有提高产品启动超时或原场景 watchdog。
- 保持原 **边界至少 20 个变化像素**、截图尺寸及控件边界不变断言；首页文字可读性、Tab 目标、迷你返回后的播放器实例/加载版本/队列/播放状态均继续检查。
- 增加两个真实负向场景：拥有的测试窗口使主窗口失焦时，要求 `FOCUS_WINDOW_INACTIVE`；目标实际聚焦但测试临时隐藏边框时，必须按 `FOCUS_BORDER_MISSING` 失败。恢复原样式后才继续，不能用强制 focused 伪类代替真实聚焦证明。
- 新增定向入口 `VisualFocusSmokeLauncher`，复用完整驱动中的实际控件、合成音频与断言；它不代替四页面完整矩阵。

这里的键盘遍历是 `fireEvent(KeyEvent)` 驱动的 JavaFX 路径，不是硬件键盘输入或新一轮原生安装验收。之前实际安装版的原生结果保留其日期和范围。

## 实际结果

本机 Windows、现有 JDK 23.0.2 / JavaFX 21.0.12，显式 `-AppHome` 加载上述生产 JAR；四组最终运行均核对类来源。

| 检查 | 125% | 150% |
| --- | --- | --- |
| 焦点定向场景 | 退出 0 | 退出 0 |
| 完整视觉场景 | 退出 0 | 退出 0 |
| 首页深/浅色实际边界变化 | 各 608 像素 | 各 600 像素 |
| 迷你返回：五主题、540/460 宽 | 每组 292 像素 | 每组 290 像素 |
| 失焦负向 / 隐藏边框负向 | 均按预期拒绝 | 均按预期拒绝 |

完整场景覆盖五主题 × 四页面 × 两尺寸 × 两缩放，共 80 组页面组合；另覆盖每档五主题 × 两尺寸的迷你返回。原有本地 WAV 播放、浏览/沉浸/返回、空歌单、缺封面/歌词、减少动态、队列及播放保持检查全部完成。六份本轮运行资料（含两份失败）对应的自有进程与服务端口事后均无残留；未清空、切换或修改用户个人资料。

原失败记录继续保留：一份完整场景在到达焦点检查前发生封面角标像素失败，随后截图显示角标完整，根因未确认；未修改其阈值，只为将来复现补角标数量与 opacity 诊断。另一份定向场景捕获迷你基准 CSS 尚未刷新，修复后两档定向及完整场景通过。成功结果不删除或覆盖失败样本，也不证明其他潜在时序问题已全部消失。

本地私有证据为 `build/focus-pixels-20261022/`；最终定向与完整场景分别在 `build/visual-language-smoke/focus-targeted-final-20261022/`、`build/visual-language-smoke/focus-full-final-20261022/`。原始日志、截图和测试资料不进入 Git 或 SOURCE。

## 复现与交付边界

先按[开发说明](DEVELOPMENT.md)准备现有工具并重新编译测试。以下命令会打开隔离窗口并播放合成音频，应安排独占桌面：

```powershell
./desktop/mvnw.cmd -B -ntp -f desktop/pom.xml test-compile
# 焦点定向回归；目录名须包含 visual-language-smoke，以满足隔离保护
./scripts/playback-scene-smoke.ps1 -AppHome '<目标镜像>' -Java '<完整JDK的java.exe>' -SmokeLauncher com.lumenmusic.VisualFocusSmokeLauncher -ArtifactDirectory visual-language-smoke/focus -Profile All
# 四页面与焦点完整回归
./scripts/visual-language-smoke.ps1 -AppHome '<目标镜像>' -Java '<完整JDK的java.exe>' -Profile All
```

源码和验收文档同步原私有 GitHub；对应提交 CI 另验，不将 CI 当作 GUI 检查。当前应用、安装器和发行包没有重建，既有包内 SOURCE 仍是上一轮冻结快照，不包含本轮新测试驱动及文档；产品源码与二进制没有改变。没有新增朋友复测、系统重启首启、性能预算或公开发行结论。
