# 本机接口契约 v1

v0.2.2 新增歌单封面与说明、备注可选日期字段，继续使用现有 v1 数据与备份结构。最新安装与运行实测结果见 [v0.2.2 验收](V022_ACCEPTANCE.md)。

Python 服务基础地址：`http://127.0.0.1:8765`。元数据和错误响应为 UTF-8 XML；音频响应为 MP4 字节流。默认数据源为 B 站视频搜索，可通过 `--provider demo` 切换离线演示。Rust Axum 音频侧车默认监听 `http://127.0.0.1:8766`，转发下面的取流请求；Python 仍负责音频地址解析。

## 本地优先构建的能力边界

`--release-profile local-first` 时，`GET /api/health` 返回 `provider="local"` 及 `releaseProfile="local-first"`；full默认仍保留原health形状。没有运行时API可将该构建切回full。所有接口仍只监听回环地址。

在线tracks/collections、recommendations、radio及在线收藏夹导入返回HTTP 503、`LOCAL_FIRST_UNAVAILABLE`；GET/HEAD音轨在访问代理前被拒绝。这是该profile的固定能力限制，不表示应自动重试。`/api/lyrics` 保留LRCLIB，网易云补充transport返回合法空候选，不产生请求。曲库、历史、元数据整理、已有在线条目备份/恢复保留；保存在线条目不意味着允许播放它。桌面另在队列与最终音源处拒绝非file URI，禁HTTP(S)封面及线上预载。

策略和来源依据见[在线范围](ONLINE_SOURCE_SCOPE.md)，不同profile的默认目录和端口见[开发说明](DEVELOPMENT.md)。私有候选尚未完成GUI、真实音频或系统重启验收。

## full构建的原有请求

| 请求 | 状态 | 响应 |
| --- | --- | --- |
| `GET /api/health` | 200 | `<health status="ok" provider="bilibili" />` 或 `provider="demo"` |
| `GET /api/tracks?q=关键词&kind=all\|single\|collection&rerank=true\|false` | 200 | `<tracks>` 内含零至多个 `<track>`；`kind` 默认为 `all`，`rerank` 默认为 `true` |
| `GET /api/tracks?q=BV号或标准视频链接` | 200 | 展开该视频全部分 P，按页码排序 |
| `GET /api/collections/{bvid}/tracks` | 200 | 展开普通搜索中选中的合集，返回按页码排序的全部 `<track>` |
| `GET /api/recommendations?hint=...` | 200 | 空 hint 且无有效 AI 配置时取音乐热门榜；否则检索真实候选，本地排序后可选 AI 重排。hint 最多 160 字符、仅作用于本次；根元素含 `intents`、`tasteSource`、`failedIntents` |
| `POST /api/radio` | 200 | 生成最多 20 条私人电台候选；返回 `<radio status="ready\|insufficient" message="..." intents="...">` |
| `GET /api/radio/feedback` | 200 | 返回持久反馈快照 `<feedback>` |
| `POST /api/radio/feedback` | 200 | 写入一条反馈，返回完整反馈快照 |
| `DELETE /api/radio/feedback/{resourceId}` | 200 | 恢复该资源反馈，返回完整反馈快照 |
| `GET /api/ai-settings` | 200 | 当前 AI 设置 `<ai-settings enabled provider model baseUrl keyConfigured />`，不返回密钥 |
| `POST /api/ai-settings` | 200 | 保存 AI 设置，返回同格式状态；无效配置返回 400/`AI_SETTINGS_INVALID` |
| `GET /api/history` | 200 | 版本化的有效听歌记录 XML |
| `POST /api/listening` | 200 | 服务端采样结果 `<listening ... />`，达到有效阈值时记录一次 |
| `GET /api/diary?zone=Asia/Shanghai` | 200 | 本机最近播放 `<diary>`：统计、回顾、备注与歌词收藏 |
| `POST /api/diary/notes?zone=Asia/Shanghai` | 200 | 保存或删除私人备注，返回更新后的日记 |
| `POST /api/diary/lyrics?zone=Asia/Shanghai` | 200 | 收藏歌词片段，返回更新后的日记 |
| `DELETE /api/diary/lyrics/{id}?zone=Asia/Shanghai` | 200 | 删除歌词收藏，返回更新后的日记 |
| `HEAD /api/tracks/{id}/stream` | 200 | `audio/mp4`、完整 `Content-Length`、`Accept-Ranges: bytes` |
| `GET /api/tracks/{id}/stream` | 200 | 完整 AAC-LC/MP4 音频 |
| `GET /api/tracks/{id}/stream` + `Range: bytes=...` | 206 | 指定字节段，含 `Content-Range` |
| 演示源的取流请求 | 501 | XML 错误，`code="NOT_IMPLEMENTED"` |
| 未知路径 | 404 | XML 错误 |
| 上游超时或错误 | 504 或 502 | `<error code="UPSTREAM_*" message="..." />` |

`q` 使用 UTF-8 URL 编码。B 站数据源的空关键词返回空 `<tracks />`；演示数据源的空关键词返回全部三首曲目，非空时按标题或作者过滤。无匹配返回空 `<tracks />`，上游失败不会伪装成空结果。普通关键词结果默认按本地相关性重排；`rerank=false` 保留数据源顺序。`rerank` 只接受一个小写 `true` 或 `false`，空值、重复参数或其他值返回 400/`INVALID_RERANK`，不会调用上游。BV 直达在两种设置下均保留分 P 顺序。评分依据唯一关键词覆盖率、精确/短语匹配、时长、版本和上游位次，见[排序规则](DISCOVERY_RANKING.md)。查询明确搜索合集或 `kind=collection` 时解除时长倾向。

单曲/合集按实际分 P 数判断。搜索接口当前通常不提供分 P 数，筛选时至多读取当前顺序前 12 条视频的分 P 列表；响应根元素的 `unresolvedCount` 表示未能核实的候选数量。筛选值无效返回 400/`INVALID_KIND`。

字段示意（非实际固定数据）：

```xml
<tracks>
  <track id="bilibili:BVxxxx:p1" title="视频标题" artist="UP 主"
         durationSeconds="180" source="bilibili" bvid="BVxxxx" page="1"
         coverUri="https://example.com/cover.jpg" partCount="1" />
</tracks>
```

`id` 为数据源内标识，`durationSeconds` 为非负整数秒，`source` 标明来源。真实结果提供 `bvid` 与 `page`，并在可用时提供 `coverUri` 与 `partCount`；关键词搜索结果是视频第一页身份，选中多 P 合集后桌面端调用 `/api/collections/{bvid}/tracks` 展开并入队。BV 直达直接展开所有分 P。演示曲目 `id` 为 `demo-1` 等，`source="demo"`，不带 BV 号。歌词可由桌面端读取本地 LRC，或经下述歌词接口检索；音频直链不会暴露在元数据接口。

可选 `viewCount` 是上游提供的非负整数播放量；没有可靠值时省略，不根据排名或缩写猜测。曲库、听歌记录及桌面快照保留已知值，旧数据没有该字段仍可读取。

`{bvid}` 为 BV 号；演示数据源请求合集展开返回 501/`NOT_IMPLEMENTED`。`{id}` 可写 `bilibili:BV号:p页码`，冒号可以编码为 `%3A`。仅支持单段字节 Range；无效 Range 返回 416。音频不可用、上游失效或超时返回非 200 XML；HEAD 失败还提供 `X-Lumen-Error-Code`，供桌面端显示原因。Python 服务短暂缓存解析出的 CDN 地址，过期时重新解析，并尝试备用地址。Axum 侧车在相同取流路径转发 HEAD、GET 与 Range 响应头和字节流，另有 `GET /health` 供启动器检查。

Java 搜索连接超时 3 秒、请求超时 40 秒。在线播放先经 HEAD 检查，再由 VLC 直接读取代理 URI；Axum 的 Range 响应支持在线起播和可用资源的远距离拖动。若 VLC 拖动后实际位置未到达目标，桌面端将完整音轨下载到本机会话文件，交给 JavaFX 恢复进度；应用关闭后清理。用户可另行启用持久音频缓存，设置容量、查看占用和清空；默认关闭，启用后后台保存完整音轨，不阻塞 VLC 起播。搜索失败不改变当前播放队列。

## 个人曲库

曲库请求与响应均为 UTF-8 XML。下表除收藏夹批量导入返回导入统计外，成功的写操作返回完整 `<library version="1">` 快照，含 `<favorites>` 与 `<playlists>`；首页偏好和版本目录使用各自专用响应。失败返回 `<error code="..." message="..." />`，不会把损坏文件重置为空。曲目 `<track>` 使用搜索接口字段，另有可选 `mediaUri`，以便保存本地文件；可选 `coverUri` 也会保存。可选属性缺省与空字符串等价：`bvid`、`mediaUri`、`coverUri`、`partCount`、`viewCount` 与片段字段留空按未提供处理，`page="0"` 同样按未提供处理。客户端应当省略这些属性而不是输出空值。

| 请求 | 作用 |
| --- | --- |
| `GET /api/library` | 读取收藏与歌单 |
| `POST /api/library/favorites`，请求体 `<track ... />` | 收藏曲目，按 `id` 去重 |
| `PATCH /api/library/tracks/{segmentId}`，请求体 `<track ... />` | 编辑片段标题、歌手或起止毫秒，一次更新收藏和所有歌单中的同 ID 曲目 |
| `DELETE /api/library/tracks/{segmentId}` | 从收藏和所有歌单中删除片段 |
| `DELETE /api/library/favorites/{trackId}` | 取消收藏 |
| `POST /api/library/favorites/move`，请求体 `<move from="0" to="1" />` | 调整收藏顺序 |
| `POST /api/library/playlists`，请求体 `<playlist name="名称" />` | 新建歌单 |
| `POST /api/library/playlists/import`，请求体 `<import url="公开收藏夹链接" />` | 导入 B 站公开收藏夹，返回导入统计 |
| `POST /api/library/playlists/move`，请求体 `<move from="0" to="1" />` | 调整歌单顺序 |
| `PATCH /api/library/playlists/{playlistId}`，请求体 `<playlist name="新名称">...</playlist>` | 更新普通歌单名称、说明或封面，字段可单独提交 |
| `DELETE /api/library/playlists/{playlistId}` | 删除歌单 |
| `POST /api/library/playlists/{playlistId}/tracks`，请求体 `<track ... />` | 加入曲目，按 `id` 去重 |
| `DELETE /api/library/playlists/{playlistId}/tracks/{trackId}` | 移除曲目 |
| `POST /api/library/playlists/{playlistId}/move`，请求体 `<move from="0" to="1" />` | 调整歌单曲目顺序 |

曲目与歌单 ID 放在路径中时按 UTF-8 URL 编码。收藏和歌单保存曲目元数据，不保存音频字节；播放 B 站收藏仍按 BV/分 P 重新解析音轨。服务启动时若发现曲库文件损坏或版本不兼容，曲库端点返回 `LIBRARY_DAMAGED`，搜索端点保持可用。默认路径与覆盖方式见根目录 README。

### 歌单封面与说明

自 v0.2.2 起，普通歌单可保存可选 `description` 和 `coverDataUri`。XML 使用文本子元素，名称仍使用可选 `name` 属性；旧的仅重命名请求继续有效。例如：

```xml
<playlist>
  <description>夜晚听的歌</description>
  <coverDataUri>data:image/png;base64,实际图片的base64内容</coverDataUri>
</playlist>
```

未提供的字段保持不变；调用曲库方法或桌面客户端时传 `null` 表示不提交该字段，XML 不使用字面量 `null`。空 `<description />` 或 `<coverDataUri />` 清除相应内容；名称不可清空。必须至少提交一个字段，子元素不能重复、带属性或嵌套其他元素。说明最多 280 个 Unicode 码点，保留换行。封面仅接受有效且 MIME 匹配的 PNG/JPEG base64 data URI，解码后最多 512 KiB；不接受外部图片地址或本地路径。

带 `coverDataUri` 子元素的歌单 PATCH 请求体上限为 704 KiB；不含封面的该 PATCH 仍为 64 KiB，其他曲库端点原有大小限制不变。成功返回原有完整 `<library version="1">`，非空元数据作为对应 `<playlist>` 的文本子元素返回；缺省或空值按空说明、自动曲目拼图处理。曲库与 ZIP 备份继续使用 v1 结构，旧文件缺少字段时仍可读取。桌面端选择图片时的缩放和操作见[收藏与记忆](COLLECTION_MEMORY.md)。

### 片段曲目

片段曲目沿用 v1 曲库格式，`id="segment:<小写 UUID>"` 是独立身份，同一原曲可保存多个片段。`sourceTrackId` 为原曲 ID；`segmentStartMillis`、`segmentEndMillis` 为原音频中的起止毫秒；`sourceDurationSeconds` 为原曲总秒数，`0` 表示未知。四个字段必须同时提供，起点非负、终点大于起点；原曲时长已知（大于 0）时终点不能超过原曲时长。`durationSeconds` 必须等于向上取整后的片段秒数。`source`、`bvid`、`page`、`mediaUri` 保持原始音频引用。PATCH 可只提供要修改的标题、歌手或起止时间，也可提交完整 `<track>`；完整请求中的身份和来源字段须与已存数据相同，时长须与新范围一致。普通曲目不能使用这两个片段专用端点。片段流请求 `/api/tracks/{segmentId}/stream` 使用保存的 `sourceTrackId` 解析原音频；曲库、听歌记录 XML 和 ZIP 备份保留片段字段。

### 首页偏好

`GET` / `HEAD /api/library/home` 读取首页偏好；`POST /api/library/home` 原子保存完整偏好。成功返回 200，GET/POST 请求和响应使用相同的 XML 结构：

```xml
<home version="1">
  <sections>
    <section id="continue"/><section id="pinned"/>
    <section id="reunion"/><section id="discover"/>
  </sections>
  <pinned><pin kind="playlist" id="实际普通歌单ID"/><pin kind="smart" id="实际智能歌单ID"/></pinned>
</home>
```

区域必须恰好包含四种 ID 各一次，排列为显示顺序；常驻最多六个，`kind` 为 `playlist` 或 `smart`，ID 必须属于对应类型且不能重复。无效请求返回 400，不修改当前偏好；写入失败保留原文件与内存状态。偏好保存不改变收藏、收听历史或版本撤销快照。删除歌单同步去掉对应常驻，改名保持 ID。字段与备份行为见[备份格式](BACKUP_FORMAT.md)，界面行为见[个人首页](PERSONAL_HOME.md)。

### 歌曲版本目录

版本目录独立于收藏与歌单，通过可选 `songs` 字段保存在同一个 v1 曲库 JSON 中；旧 v1 文件无需迁移即可读取。每首歌有独立 `id`、`title`、`note`、`preferredVersionId` 和至少一个版本。版本有独立 `id`、`label`、`performer` 与完整的 `StoredTrack` 资源。歌曲和版本 ID 由服务生成；资源沿用原有 `<track id="...">` 身份，供歌词、缓存、音源和片段识别使用。同一资源 ID 在整个版本目录中只允许出现一次，不自动合并相似歌曲。版本目录写入以相同的曲库文件原子持久化，ZIP 备份包含目录与一次撤销快照。

| 请求 | 请求体与作用 |
| --- | --- |
| `GET /api/library/versions` | 读取完整版本目录 |
| `POST /api/library/versions` | `<song title="..." note="..."><version label="..." performer="..."><track .../></version>...</song>`；至少一个版本，首版本成为默认首选 |
| `PATCH /api/library/versions/{songId}` | `<song title="..." note="..." preferredVersionId="..."/>`；属性可单独提交，首选 ID 必须属于该歌曲 |
| `POST /api/library/versions/{songId}/versions` | `<version label="..." performer="..."><track .../></version>`；加入一个未归属资源 |
| `PATCH /api/library/versions/{songId}/versions/{versionId}` | `<version label="..." performer="..."/>`；仅编辑版本名称和表演者，属性可单独提交 |
| `POST /api/library/versions/{songId}/merge` | `<merge songId="其他歌曲 ID"/>`；明确合并两个现有分组，保留目标歌曲标题和首选版本，追加来源版本及不同的非空备注 |
| `DELETE /api/library/versions/{songId}/versions/{versionId}` | 从目录拆出版本；不改收藏或歌单，最后一个版本拆出后删除空歌曲 |
| `POST /api/library/versions/{songId}/versions/{versionId}/replace` | `<track .../>`；换用不同且未归属的新资源 ID，并替换收藏与所有歌单中的旧资源，保留原位置且去重 |
| `POST /api/library/versions/undo` | 撤销最近一次版本目录变更，包括替换影响的收藏与歌单；无可撤销操作返回 400 |

这些端点成功时都返回 `<catalog><song id="..." title="..." note="..." preferredVersionId="..."><version id="..." label="..." performer="..."><track .../></version></song></catalog>`，其中 `<track>` 属性与曲库接口一致。版本目录只保留一次撤销；撤销后不能再次撤销。其间若发生其他曲库修改，撤销失效，以免覆盖后续收藏或歌单操作。`GET /api/library` 仍保持原有 `<library>` 响应结构。

公开收藏夹导入接受 `https://www.bilibili.com/medialist/detail/ml...`、`https://www.bilibili.com/list/ml...`、`https://space.bilibili.com/{用户ID}/favlist?fid=...` 或完整数字 media ID。服务以游客身份分页读取公开收藏夹，按页面顺序导入视频首页身份；失效、非视频和元数据不完整的条目计入 `skippedCount`，重复 BV 计入 `duplicateCount`。最多导入 200 首有效且不重复的歌曲，尚有未读取内容时 `truncated="true"`。全部所需页面获取成功后才原子写入新歌单，整体网络预算 120 秒，超时不写入半成品。成功响应示例：`<import playlistId="..." name="歌单名" importedCount="12" skippedCount="2" duplicateCount="1" truncated="false" />`；客户端再 `GET /api/library` 获取完整曲库。无权读取返回 403/`FAVORITE_INACCESSIBLE`，无效链接返回 400/`INVALID_FAVORITE_URL`，上游数据异常返回 502/`UPSTREAM_INVALID_RESPONSE`，这些错误不会创建半成品歌单。接口字段以 [B 站收藏夹列表 API 记录](https://github.com/pskdje/bilibili-API-collect/blob/main/docs/fav/list.md) 为参考，并在运行时检查响应结构。

## 听歌记录与推荐

播放采样请求体为 UTF-8 XML，例如：

```xml
<sample sessionId="UUID" positionSeconds="12.5" playbackRate="1.25" elapsedSeconds="10" playing="true" seeked="false" finished="false">
  <track id="bilibili:BVxxxx:p1" title="曲名" artist="作者" durationSeconds="240"
         source="bilibili" bvid="BVxxxx" page="1" partCount="1" />
</sample>
```

`playing`、`seeked`、`finished` 必须是 `true` 或 `false`。同一曲目的采样共用 `sessionId`；暂停、拖动与结束分别发送状态，服务用单调时钟核算有效播放，达到阈值后最多写入一次。响应包含 `qualified`、`recorded`、`playedSeconds`、`thresholdSeconds` 等属性。`GET /api/history` 返回 `<history version="1">` 内的 `<entry playCount="..." lastPlayedAt="..." totalQualifiedSeconds="...">`。历史文件损坏时这些端点明确报错，普通搜索保持可用。

本机桌面端还发送 `elapsedSeconds`：由单调时钟生成的本会话采样时刻，包含暂停经过时间，在异步入队前捕获，必须为有限非负数且不能倒退。服务以采样生成时刻核算相邻观察区间，避免 HTTP 排队或处理延迟把连续播放误判为跳转；暂停仍由播放状态排除。旧客户端省略该字段时使用服务端单调接收时钟。同一会话不能切换这两种时钟来源。

`playbackRate` 为有限正数，省略时为 `1`。服务使用上一次采样的速率，把媒体推进秒数除以速率，并以相邻单调采样时刻之间的经过时间为上限，累计实际收听时间 `playedSeconds`。只有满足连续播放条件的正常媒体推进才累计为 `mediaPlayedSeconds`，该资格累计不因请求接收时间的小幅抖动逐次截短；暂停、卡顿、拖动和异常跳转不增加这两项。速率变化时应立即采样实际位置及新速率，明确变更边界。A-B 循环先采样跳转前位置，再以 `seeked="true"` 采样跳回位置，沿用当前会话，各轮正常播放可以累计且只入库一次。60 秒以下曲目按已听媒体推进达到曲目时长的 90% 判断资格，片段使用精确毫秒长度；其他曲目按实际收听达到 30 秒判断。`thresholdSeconds` 对应各自的资格判据，历史 `totalQualifiedSeconds` 始终累计入账时的实际收听时间。例如 10 秒短曲以 1.5 倍速播放到结尾可以入账，实际累计约 6.667 秒。

采样可附带 `playlistId` 和 `playlistName`；服务按会话开始时的实际曲库快照验证归属，名称以曲库为准，不按歌曲交集推测播放来源。响应新增 `justRecorded`，仅首次成功入库为 `true`；`revisitOldFavorite` 仅在该次达标且符合真实收藏时间和 30 天重听间隔时为 `true`。会话 ID 已入库后，服务重启也不重复计数。历史 JSON v1 新增可选 `events` 与条目的 `firstPlayedAt`；旧文件的未知首次日期不补全。原 `/api/history` XML 结构不变，详细回顾通过日记端点读取。

### 最近播放

日记 `zone` 默认 `Asia/Shanghai`，桌面端传本机 `ZoneId`；无效时区返回 400/`DIARY_INVALID`。未安装时区数据库的 Windows Python 仍支持 `Asia/Shanghai` 和 UTC。根元素 `<diary zone="..." generatedAt="...">` 包含：

- `<summary totalPlays="..." thisMonthPlays="..." sourceCount="..." trackCount="..." songCount="..." unknownFirstCount="..."/>`。
- 四个 `<section id="weekly-first|monthly-playlists|reheard|recent" title="..." count="...">`，条目 `<entry>` 附实际日期、可用的 `songId` 或歌单来源与完整 `<track>`。旧累计条目的最近日期标记 `legacy="true"`；`count` 是截断前实际总数。
- `<notes><note trackId="..." createdAt="可选UTC时间" updatedAt="可选UTC时间"><text>...</text><track .../></note></notes>`。
- `<lyricFavorites><lyric id="..." trackId="..." positionMillis="..." createdAt="..."><text>...</text><track .../></lyric></lyricFavorites>`。

备注请求为 `<note trackId="..."><text>...</text><track .../></note>`，空文本删除备注。歌词请求为 `<lyric trackId="..." positionMillis="..."><text>...</text><track .../></lyric>`；文本不能为空，位置非负，同资源/位置/文本重复提交不产生第二条。`trackId` 必须与内层资源 ID 一致，可保存未收藏资源。DELETE 的歌词 ID 按 UTF-8 URL 编码。私人内容保存在曲库 v1 的可选 `diaryNotes`、`lyricFavorites`，写入与读取使用同一数据锁及原子保存，不进入推荐画像。日期和统计语义见[最近播放](MUSIC_DIARY.md)。

自 v0.2.2 起，备注响应和曲库 `diaryNotes` 可带 `createdAt`、`updatedAt`，均为带时区并规范化到 UTC 的 ISO 8601 时间。日期由服务生成：新备注写入真实创建和更新时间；修改保留创建时间并更新修改时间；相同文本及资源元数据重复保存不改变日期。旧备注缺少日期时保持缺省，编辑后仅增加真实 `updatedAt`，不会补造 `createdAt`。这些可选日期随原有 v1 ZIP 备份保存，客户端应将缺省日期显示为未知。

推荐响应仍是 `<tracks>`，每个 `<track>` 与搜索接口同格式。`tasteSource` 为 `ai`、`fallback`、`cold-start` 或 `popular`，描述检索词来源，并非重排成功与否；所有曲目均来自 Provider。无有效 AI 配置且没有 hint 时返回音乐热门榜前 20 条并保留榜单顺序。hint 仅引导本次、不保存为长期画像；非榜单候选按收藏、歌单及有效记录在本机排序。启用且配置 AI 时，向配置地址发送收藏/歌单派生摘要与本次 hint，生成检索词；再发送最多 60 条真实候选的 `id/title/artist/durationSeconds`，仅接受已有 ID 的重排。原始听歌记录、播放时间/次数、本地路径和播放 URL 不发送给 AI。意图失败继续本地检索，重排失败保留本地顺序。详见[第三阶段功能](PHASE3.md)。

AI 设置保存请求为 UTF-8 XML，例如 `<ai-settings enabled="true" model="模型名" baseUrl="https://服务地址/v1/chat/completions" apiKey="新密钥" />`。`apiKey` 可省略，表示保留已有密钥；响应只返回 `keyConfigured` 布尔值，不回传密钥。非敏感值保存在 `ai-settings.json`，密钥保存在独立的 `ai-secret.json`；启用 AI 时需提供模型名及 HTTPS Chat Completions 地址。设置文件进入备份，密钥文件不进入备份。

### 私人电台

`POST /api/radio` 请求体为 UTF-8 XML。`mode` 必须是 `familiar`（偏向已收藏、听过的资源）或 `discover`（偏向未收藏、未听过的资源）；`neutral` 必须是小写 `true` 或 `false`，省略时为 `false`。`hint` 最多 160 字符，仅用于本次检索。`seed` 可包含多首完整 `<track>`，适用于从歌单继续播放；`exclude` 用资源 ID 排除近期队列曲目。一次最多 500 首种子、2000 个排除 ID，请求体最多 1 MiB。客户端应把当前队列和已经播放的近期资源传入 `exclude`。例如：

```xml
<radio mode="discover" hint="安静的钢琴" neutral="false">
  <seed><track id="bilibili:BVxxxx:p1" title="曲名" artist="UP 主" durationSeconds="180" source="bilibili" bvid="BVxxxx" page="1" /></seed>
  <exclude id="bilibili:BVyyyy:p1" />
</radio>
```

成功响应根元素含 `status`、`message`、`intents` 和 `failedIntents`。`ready` 表示至少 10 首候选；少于 10 首时为 `insufficient`，`message` 说明实际数量，可仍返回部分曲目。`item reason` 只陈述可核对的本地依据，内层 `<track>` 为完整资源元数据。种子和 `exclude` 资源不会出现在结果中。候选只来自当前 Provider 的实际搜索/热门榜结果或已有本地曲库资源，模型不能创建歌曲或 URL。空种子、空提示且无口味时使用 Provider 的音乐热门榜；有提示时不以不匹配的热门榜填充。上游全失败且无本地候选时返回 502，上游部分失败在 `failedIntents` 中列出。已知版本目录的表演者用于减少相邻同表演者；其他资源按元数据作者减少连续出现，不把 B 站 UP 主当作已确认歌手。

`neutral="true"` 不读取长期收藏/歌单/历史口味排序或持久反馈，也不修改任何长期数据；只使用本次 `seed`、`hint` 和 `exclude` 检索及过滤。普通模式使用收藏、有效听歌记录、显式反馈和最近 7 天内最多 10 条有效播放作本地排序。当前资源属于版本目录中的同一首歌时，`tired` 会暂时排除该组的全部版本；没有已建立的版本分组时只排除所提交资源。

反馈请求体为 `<feedback kind="tired|version|skip"><track ... /></feedback>`；`track` 使用曲库接口的完整资源字段，允许携带本地 `mediaUri`。`tired` 从提交时起 7 天内临时排除，`until` 为 UTC ISO 8601 时间；`version` 只降权该资源，不影响同歌其他版本；`skip` 是弱信号，不永久排除，且不会覆盖已有 `tired` 或 `version`。后两者的 `until` 为空字符串。显式提交 `tired` 或 `version` 可互相替换。资源 ID 放在 DELETE 路径中时须做 UTF-8 URL 编码，本地 ID 可以包含编码后的 `/`。无效 XML、种类或曲目字段返回 400。反馈保存在曲库 v1 JSON 的可选 `radioFeedback` 数组，使用原有原子写、损坏文件保护和 ZIP 备份；普通反馈写入会使尚未使用的版本目录撤销失效，忽略被强反馈覆盖的 `skip` 不会写盘。关闭、重启或备份恢复后反馈仍存在。

启用 AI 时，电台沿用现有意图生成和真实候选 ID 重排。向 AI 发送的长期摘要仅来自收藏/歌单；中立模式摘要为空。只发送已筛除本地路径/媒体地址的 Provider 候选 `id/title/artist/durationSeconds`，最多 20 首；不会发送原始听歌记录、播放时间、曲库备注、本地路径或可播放 URL。AI 无配置、响应无效或调用失败时使用本地检索与排序；其排序不能越过排除项或持久反馈规则。

## 备份与歌词

| 请求 | 响应与作用 |
| --- | --- |
| `POST /api/backup/export`，`<backup path="绝对路径.zip" />` | 导出 ZIP，返回 `<backup-preview favorites="..." playlists="..." history="..." backgroundBytes="..." members="..." />` |
| `POST /api/backup/inspect`，同上 | 只读检查 ZIP 内容与校验值，返回相同预览 |
| `POST /api/backup/restore`，同上 | 完整预检后恢复并重新载入运行中曲库与听歌记录；损坏 ZIP 返回 400/`BACKUP_INVALID` |
| `GET /api/lyrics?title=曲名&artist=歌手&duration=秒` | `<lyrics autoId="可空">` 下返回 `<candidate id title artist duration score reason><lrc>同步歌词</lrc></candidate>` |

备份路径指服务所在机器的本地文件。导出默认包含曲库，已有听歌记录文件时一并包含；如果数据目录存在 `ai-settings.json` 或 `background.png/jpg/jpeg/webp`，也纳入 ZIP。AI 密钥不会写入备份。界面恢复前先调用预检并展示范围，确认后再恢复；服务再次校验归档并暂停曲库及历史写入，成功后重建内存 Store。未包含的可选文件保持原样，详见[备份格式](BACKUP_FORMAT.md)。

歌词候选来自 LRCLIB。桌面端优先恢复已保存的手动匹配；无记录时同名本地 LRC 优先。只有曲名、歌手和时长均精确匹配且候选同步歌词不冲突时才填充 `autoId`；其他候选仍返回供手动选择。无有效时间标记的歌词不会作为候选。外部歌词服务不可用时返回明确错误，既有歌词不被清空。桌面端支持 ±0.5 秒偏移与全文浏览。歌词数据源说明见[第三步验收](PHASE2_LIBRARY_LYRICS.md)。
