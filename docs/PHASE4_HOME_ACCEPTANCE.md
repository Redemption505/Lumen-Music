# 第四阶段 §3.2：个人首页验收

本页本地证据与归档位于未随源码上传的 `build/`；不是 GitHub 下载入口。

## 结论与交付范围

2026-10-07，个人首页已完成实现及本机数据、真实窗口与播放验收。使用说明见[个人首页](PERSONAL_HOME.md)。本轮功能已纳入 v0.2.2，最终发行 JAR 的两档 DPI 回归与安装交付见[综合验收](V022_ACCEPTANCE.md)。以下保留 §3.2 当轮证据，未新增依赖。

| 需求 | 当前结果 |
| --- | --- |
| 继续听 | 复用原有队列与位置恢复；启动不自动发声，明确点击才续听；播放中入口显示状态，不切换暂停 |
| 我的常驻 | 最多六个普通/智能歌单，支持添加、移除和手动排序；打开对应歌单只导航 |
| 今日重逢 | 从真实日记选择老收藏重听、初遇或最近听过的曲目，日期与理由来自真实记录；无历史提供导入/收藏入口 |
| 想发现一点 | 沿用私人电台和本次引导、推荐生成/刷新；浏览与生成候选保持音频和队列 |
| 调整与备份 | 四区排序、常驻顺序保存到版本化曲库字段，纳入 ZIP 预检/恢复；失败保留草稿，取消不写入 |
| 小窗口与主题 | 双列/单列自动切换，纵向滚动可达所有卡片；长歌单名省略并保留完整提示，操作按钮不被挤压 |

主要实现：[首页组件](../desktop/src/main/java/com/lumenmusic/PersonalHomePane.java)、[应用集成](../desktop/src/main/java/com/lumenmusic/LumenFxApp.java)、[真实重逢选择](../desktop/src/main/java/com/lumenmusic/HomeReunion.java)、[首页客户端](../desktop/src/main/java/com/lumenmusic/HomeClient.java)、[偏好契约](../service/lumen_service/home.py)。数据结构见[备份格式](BACKUP_FORMAT.md)与[接口契约](API.md)。

## 数据与逻辑验证

环境为本机 Windows，JDK 23.0.2（编译目标 Java 17）、JavaFX 21.0.12、Maven 3.9.11。Python 全量使用本机既有测试解释器，窗口脚本使用现有捆绑 Python 3.13.15 与本地 demo 服务。

- Java 完整回归 **263/263**，54 个测试套件，无失败、错误或跳过；新增首页 HTTP 客户端 3 项及重逢选择 4 项。覆盖排序与 XML 转义、错误响应与版本拒绝、日期时区、旧历史不编造初遇日期。随后显示与控件宽度修正已增量编译并由最终窗口验收覆盖。
- Python 全量运行 **317 项：315 通过，2 跳过**。两项是原有真实 B 站公网测试，未启用 `LUMEN_RUN_LIVE_TESTS=1`；不计为通过。全量之后的撤销快照微调由首页 6、曲库 11、版本 HTTP 6 项增量覆盖，23 项全部通过。主代理再次运行首页 6 项并保存日志。
- 偏好写入保留其他曲库字段与现有版本撤销快照；普通/智能歌单改名保留引用，删除同步取消对应常驻。
- 无效版本、缺失/重复区域、超过六个或重复/不存在的常驻原子拒绝；模拟文件写入失败后文件与内存状态不变。
- 旧曲库默认补齐；ZIP 导出、恢复保留偏好，无效首页结构不覆盖当前数据或已有备份。

## 真实窗口与播放验证

[个人首页脚本](../scripts/personal-home-smoke.ps1)在实际输出缩放 **1.25 / 1.5** 下分别退出 0。每档均启动真实 JavaFX 应用与隔离 Python HTTP 服务，使用合成 WAV、两个收藏、200 曲目普通歌单及智能歌单；未读取或改写真实个人曲库。

- 新用户无虚构继续队列或回听曲目，导入/收藏入口可用，启动不播放。
- 第二个应用实例恢复两首队列与 18.5 秒位置，仍保持暂停；明确点击续听后真实音频从保存位置播放。
- 普通常驻打开其 200 曲目页，智能常驻选中自己的智能歌单；生成推荐、打开电台及调整首页期间，播放器对象、加载序号、队列/来源/循环方式保持，播放时钟继续前进。
- 调整取消不写入；添加、区域与常驻排序保存成功。真实 HTTP 500 注入后窗口、草稿和重试按钮保留，服务偏好不变；恢复服务后重试成功。
- 五主题 × 两尺寸（1280×800 / 860×600）× 两缩放，共 **20 组首页布局检查**，上下滚动截图均保留；卡片宽度、核心入口、常驻长标题、深浅主题小窗口调整页通过。首页未渲染完整 200 曲目列表。
- HTTP ZIP 导出 → 修改偏好 → 恢复，区域和常驻顺序完全一致。
- 只有明确回听改变队列；正常退出后再创建新的应用实例与窗口，恢复偏好、队列和暂停位置，启动仍不播放。

[原视觉脚本](../scripts/visual-language-smoke.ps1)重新执行 **5 主题 × 4 页面 × 2 尺寸 × 2 缩放 = 80 组**，两档均退出 0。发现区检查改为先定位真实滚动视口；原有字形与对比度、完整封面、焦点、禁用、空状态、减少动态及歌单/沉浸往返的播放连续性检查全部保留并通过。

[私人电台脚本](../scripts/radio-smoke.ps1)使用真实 demo HTTP 服务与原生音频退出 0：反馈/移除后候选不返回、中立音频播放至九成及结束不写历史、手动指定下一首优先于电台预览、单曲循环不消耗候选、关闭自动续播保持手动队列，以及普通电台保持当前收听会话均通过。深浅主题窄窗口截图保留；Java `RadioSessionTest` 的 7 项规则测试也包含于上述完整回归。

## 可复查证据

| 验证 | 日志或结果 |
| --- | --- |
| 首页最终 125% | stdout（本地路径：`build/personal-home-smoke/normal-6c70f86e070844b686e24dacb33a425a/desktop.stdout.log`） |
| 首页最终 150% | stdout（本地路径：`build/personal-home-smoke/hidpi-1.5-52797010286e4abcb43850b14a9a0ef5/desktop.stdout.log`） |
| 原视觉回归 125% | stdout（本地路径：`build/visual-language-smoke/normal-a748afdba4bf44ddaa0e7ebbff70beac/desktop.stdout.log`） |
| 原视觉回归 150% | stdout（本地路径：`build/visual-language-smoke/hidpi-1.5-0fe7db8132ee419bbe3310421a80027d/desktop.stdout.log`） |
| 私人电台回归 | stdout（本地路径：`build/radio-smoke/d21729d80d894484bf27600e6e1aa72c/desktop.stdout.log`） |
| Python 首页最终 6 项 | 日志（本地路径：`build/personal-home-tests.log`） |
| Java HTTP / 重逢 | HomeClientTest（本地证据/归档：`desktop/target/surefire-reports/com.lumenmusic.HomeClientTest.txt`，不随源码上传）、HomeReunionTest（本地证据/归档：`desktop/target/surefire-reports/com.lumenmusic.HomeReunionTest.txt`，不随源码上传） |

截图：Lumen 首页（本地路径：`build/personal-home-smoke/normal-6c70f86e070844b686e24dacb33a425a/lumen-home-top-1280x800.png`）、静默恢复（本地路径：`build/personal-home-smoke/normal-6c70f86e070844b686e24dacb33a425a/paused-restored.png`）、空首页（本地路径：`build/personal-home-smoke/hidpi-1.5-52797010286e4abcb43850b14a9a0ef5/new-listener.png`）、浅色小窗口调整（本地路径：`build/personal-home-smoke/hidpi-1.5-52797010286e4abcb43850b14a9a0ef5/light-customize-small.png`）。截图标题与超长歌单名为合成验收内容。

源码 CSS 与被测 `target/classes/lumen-theme.css` 的 SHA-256 相同：`b7f59e704fc1aa580ae88fb53051959bf2ff99d5b086d1de5ffa5d6d4d8e893d`。

## 实际边界

当轮验收使用源码构建，后续已随 v0.2.2 同步安装与发行。未重跑公网 B 站取流、长期收听、其他桌面平台或安卓；推荐算法和音频引擎没有改动。朋友在没有讲解时的可发现性测试尚待 §4 试用反馈，自动验收只证明入口可用及运行规则正确。

布局和常驻进入 ZIP；原有本机播放会话文件仍不在 ZIP 中。源文件失效的本地音乐仍需重新关联。收藏陈列与记忆的本轮桌面范围已完成[实现和验收](PHASE4_COLLECTION_ACCEPTANCE.md)。
