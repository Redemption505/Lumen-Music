# 第四阶段 §3.1：视觉统一验收

## 结论与交付范围

2026-10-07，首页、普通歌单、沉浸播放和设置的视觉统一已完成源码实现与本机验收。五档主题保持，未新增依赖。使用规则见[视觉语言](VISUAL_LANGUAGE.md)。

本轮功能已纳入 v0.2.2；个人首页及本轮桌面收藏记忆也已完成。三节最终发行 JAR 的两档 DPI 回归与安装交付见[综合验收](V022_ACCEPTANCE.md)，安卓仍按[第四阶段](../第四阶段.md)后续章节实施。以下记录 §3.1 当轮结果。

## 实现结果

| 范围 | 当前行为 |
| --- | --- |
| 颜色与层级 | 五主题共用背景、面板、文字、焦点、收藏、错误及弹层角色；Lumen 为暖墨/暖金，浅色为暖白；封面背景限制饱和度和亮度 |
| 封面 | 列表、底栏和沉浸页完整展示原始比例；圆角作用于画框，统一音乐符号占位，加载/失败说明进入悬停提示 |
| 曲目身份 | 保留用户歌名、版本演唱者和标签；B 站上传者标为 UP 主，BV/P 与片段信息保留在来源提示；修复窄行覆盖演唱者及恢复时标题不一致 |
| 操作状态 | 列表、底栏、沉浸收藏颜色与提示一致；焦点边框可见且按钮尺寸不变，禁用按钮不执行操作；菜单使用当前主题和强调色 |
| 页面与状态 | 统一首页/歌单/设置标题，歌单就近反馈、长文换行；空歌单和无歌词有操作说明；设置分为外观、陪伴等现有功能组 |
| 动效与连续性 | 封面画框 220ms、封面色调约 240ms；减少动态即时生效；展开/返回保留播放器、队列及浏览位置 |

主要实现：[页面与封面](../desktop/src/main/java/com/lumenmusic/LumenFxApp.java)、[主题](../desktop/src/main/java/com/lumenmusic/ThemeController.java)、[样式](../desktop/src/main/resources/lumen-theme.css)、[过渡](../desktop/src/main/java/com/lumenmusic/ArtworkTransition.java)。

## 验证结果

环境为本机 Windows，JDK 23.0.2（编译目标 Java 17）、JavaFX 21.0.12、Maven 3.9.11。

- Java 完整回归 **256/256**，52 个测试套件，无失败、错误或跳过；包含新增的两项[主题对比度测试](../desktop/src/test/java/com/lumenmusic/ThemePaletteTest.java)。最后的收藏与焦点修改已增量编译，并由以下最终窗口验收覆盖。
- 真实窗口执行 **5 主题 × 4 页面 × 2 尺寸 × 2 缩放 = 80 组**。尺寸为 1280×800 和 860×600；实际输出缩放为 1.25 与 1.5。检查长中英文标题、核心控件边界、关键文字实际字形及对比度、更多弹层位置。
- 竖向测试封面的四角使用独立颜色标记，列表、底栏及沉浸画框的实际像素全部保留。
- 已收藏样本的列表、底栏、沉浸红心色与“取消收藏”提示一致；未收藏样本显示不同颜色及“收藏当前”。深浅主题 Tab 遍历可达操作，焦点边界可见且按钮尺寸稳定；禁用来源操作不分发事件、不改变播放及队列。
- 真实本地 WAV 播放中，从歌单进入沉浸再返回，播放器对象、音频加载序号、队列、歌单页和列表滚动位置保持，播放时钟继续推进。
- 空歌单、无封面、无歌词在小窗口可见；减少动态开关立即冻结动态背景，队列/沉浸导航与音频继续可用。
- 已有播放场景在系统 125% 缩放下回归通过：歌词居中、约五秒闲置淡出及指针恢复、封面切换与动画清理、专注完成/取消、睡眠取消/完成，以及五主题两尺寸场景。

验收数据均为隔离曲库、合成 WAV 与本机 HTTP 封面；没有写入真实个人资料。

## 可复查证据

| 验收 | 最终日志 |
| --- | --- |
| 视觉窗口 125% | [stdout](../build/visual-language-smoke/normal-859f6bbe369a4d0aa1886b40490a3f39/desktop.stdout.log) |
| 视觉窗口 150% | [stdout](../build/visual-language-smoke/hidpi-1.5-d7a65b02b04242cc955d53f9b95bffc1/desktop.stdout.log) |
| 播放场景 125% | [stdout](../build/playback-scene-smoke/normal-e03c20868d16443c9c4bae2a6759e1c6/desktop.stdout.log) |
| Java 主题测试 | [结果](../desktop/target/surefire-reports/com.lumenmusic.ThemePaletteTest.txt) |

截图：[Lumen 沉浸页](../build/visual-language-smoke/normal-859f6bbe369a4d0aa1886b40490a3f39/lumen-immersive-1280x800.png)、[浅色小窗口设置](../build/visual-language-smoke/normal-859f6bbe369a4d0aa1886b40490a3f39/light-settings-860x600.png)、[深色键盘焦点](../build/visual-language-smoke/normal-859f6bbe369a4d0aa1886b40490a3f39/dark-keyboard-focus.png)、[空歌单](../build/visual-language-smoke/normal-859f6bbe369a4d0aa1886b40490a3f39/empty-playlist-small.png)。封面的强烈角标用于检查完整性，不代表正式视觉素材。

窗口检查实现为 [VisualLanguageSmoke](../desktop/src/test/java/com/lumenmusic/VisualLanguageSmoke.java)，入口为 [visual-language-smoke.ps1](../scripts/visual-language-smoke.ps1)。源码 CSS 与被测 `target/classes/lumen-theme.css` 的 SHA-256 一致：`969d11df629d781a962148943f51262e8832be97eb65138c809a00f9f6f09a61`。

## 实际边界

当轮验证源码构建，后续已随 v0.2.2 同步安装与发行；未重跑公网 B 站取流、真实断网及长时间听感验收，已有播放失败/封面重试链路沿用 [v0.2.1](V021_ACCEPTANCE.md)。§3.1 当轮未变更 Python、Rust 与音频引擎，后续 Python 元数据变更已在 v0.2.2 运行全套回归。其他平台及安卓尚未验收。
