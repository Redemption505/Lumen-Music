# 第四阶段 §3.3：收藏陈列与听歌记忆验收

2026-10-07，本轮桌面范围已实现，并在 v0.2.2 最终发行 JAR 上完成两档缩放验收。使用方法见[收藏与记忆](COLLECTION_MEMORY.md)，3.1～3.3 综合结果及安装交付见[v0.2.2 验收](V022_ACCEPTANCE.md)。纪念卡图片导出按原规划排在安卓核心播放之后，本次未实现。

## 实现与结果

| 功能 | 验收结果 |
| --- | --- |
| 歌单陈列 | 普通歌单页显示自选封面、短说明或已有曲目封面拼图；自选图和各拼图保留完整比例；无图使用统一占位 |
| 封面与说明编辑 | 本地图片真实读取、缩放、预览和保存；取消不写入；实际 HTTP 500 后草稿保持，可重试；清除封面后重启恢复拼图 |
| 版本展示 | 手动偏好置前，显示“我常听的版本（偏好）”与其他版本；说明和演唱者来自保存的个人标注，实际片段保留来源与范围 |
| 日期阅读 | 备注和歌词用完整换行卡片展示；按日记时区分组与筛选；旧备注创建日期保持未知，真实编辑时间单独展示 |
| 保存失败 | 私人备注在刷新、切换曲目后保留本次运行中的草稿；失败歌词保留原资源、原文、原位置，重试后准确写入 |
| 播放规则 | 编辑、保存、偏好和浏览保持实际音频实例、加载版本、队列及递增位置；明确播放版本或歌词才改变播放，歌词从保存的 4200ms 回听，片段使用原资源 |
| 持久化与备份 | 内嵌封面、说明、备注日期、歌词文本/位置、版本标注与偏好通过真实 HTTP ZIP 往返和新应用实例重启验证 |

五种主题 × 1280×800 / 860×600 × 歌单、封面编辑、版本、备注、歌词五种视图 × 125% / 150%，共 100 组窗口组合通过控件边界、实际滚动视口可达性检查，保留绘制完成后的截图。主代理检查了歌单、版本卡片和日记的代表截图。

## 数据与逻辑验证

Java 最终整合全套 274/274 通过，包含封面完整比例和透明度、客户端 XML 元数据、日期时区与未知值、偏好版本排序。Python 全套运行 327 项，325 通过、2 项既有 B 站公网测试默认跳过；随后真实 JPEG 测试夹具补充后的新增 9 项通过。不同运行的数量不相加。

新增数据验证覆盖旧字段缺省、280 字与图片大小/格式限制、PATCH 字段部分提交与清除、真实主文件替换失败保持内存和磁盘、改名/常驻引用、版本资源替换与撤销保留元数据、无效日期拒绝、重复备注不改变时间、旧备注不补造创建日期及私人内容不进入推荐。旧 ZIP v1 结构保持，封面字节直接随曲库保存。

## 可复查证据

| 检查 | 证据 |
| --- | --- |
| 最终 JAR 125% | [窗口日志](../build/collection-memory-smoke/normal-00ef3cfd83124d61adace95f0e041fa7/desktop.stdout.log)、[类加载](../build/collection-memory-smoke/normal-00ef3cfd83124d61adace95f0e041fa7/class-load.log) |
| 最终 JAR 150% | [窗口日志](../build/collection-memory-smoke/hidpi-1.5-e0900d8c099a47ad83f7d2881a4e8ff3/desktop.stdout.log)、[类加载](../build/collection-memory-smoke/hidpi-1.5-e0900d8c099a47ad83f7d2881a4e8ff3/class-load.log) |
| 数据回归 | [Python 日志](../build/v022-data-tests.log)、[Java 封面测试](../desktop/target/surefire-reports/com.lumenmusic.PlaylistCoverDataTest.txt) |
| 可恢复内容 | [隔离 ZIP](../build/collection-memory-smoke/normal-00ef3cfd83124d61adace95f0e041fa7/collection-memory-roundtrip.zip) |

截图：[自动拼图](../build/collection-memory-smoke/normal-00ef3cfd83124d61adace95f0e041fa7/automatic-collage.png)、[歌单](../build/collection-memory-smoke/normal-00ef3cfd83124d61adace95f0e041fa7/playlist-lumen-1280.png)、[偏好版本](../build/collection-memory-smoke/normal-00ef3cfd83124d61adace95f0e041fa7/versions-lumen-1280.png)、[150% 浅色歌词](../build/collection-memory-smoke/hidpi-1.5-e0900d8c099a47ad83f7d2881a4e8ff3/lyrics-light-860.png)。标题、历史日期、备注、歌词、封面和 WAV 均为明确的合成测试资料。

重跑：`./scripts/collection-memory-smoke.ps1 -AppHome '最终镜像或安装目录' -Profile All`。传入 AppHome 后，生产 JAR 先于测试入口，验证类来源及 SHA-256，不使用 `target/classes`。捆绑运行时没有命令行 java.exe，窗口驱动使用已有 JDK；实际安装器启动的捆绑 JVM 验收另见综合报告。

## 实际边界

已验证选择文件后的真实解码、预览、保存及取消；系统文件选择对话框本身未自动操作。未保存草稿仅在本次应用运行中保留，关闭后不恢复。没有进行朋友独立试用、公网长听、其他平台或安卓验收。

测试直接在播放期间调用 HTTP 恢复备份，会重建历史采样协调器；旧的已入账会话随后被原有去重规则以 HTTP 400 拒绝。导出 ZIP 与测试结束后的历史均为 3 个事件，会话 ID 完全一致，未重复入账。此处验证备份内容及重启，不作为新增收听计数验收。取消后台歌词查询产生的连接重置也不作为歌词收藏失败；失败草稿测试使用另设的真实 HTTP 500 服务。
