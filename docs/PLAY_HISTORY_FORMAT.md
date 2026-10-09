# 有效听歌记录 v1

本文件定义第二阶段听歌记录的判定与磁盘格式。服务端核心实现见 `service/lumen_service/history.py`；接入播放器事件与 API 后才会实际采集记录。

## 判定

- `durationSeconds` 为 1–59 的短歌：同一次播放会话累计有效播放达到曲目时长的 **90%**，记一次。
- `durationSeconds` 为 60 秒及以上的长歌：同一次播放会话累计有效播放达到 **30 秒**，记一次。
- 时长为 0 表示未知；先不记入记录，待获得实际时长后创建新的会话。
- 同一次播放会话最多记一次。重播须创建新会话；同一曲目不同分 P 使用不同曲目 ID，分别记录。
- 有效秒数同时受单调时钟和播放器位置增长约束，每次最多增加二者较小值。暂停、重复采样、回退、拖动不增加时间；播放器报告 seek 时重置采样锚点，未报告但位置跳跃超出墙钟时间 0.25 秒时视作 seek。调用方应在开始、暂停、恢复、拖动、切歌、播放结束及播放期间定期提交采样。关闭会话后不得复用。

## `history.json`

UTF-8 JSON，顶层 `version: 1` 与 `entries` 数组。每个曲目 ID 仅保留一项：

```json
{
  "version": 1,
  "entries": [
    {
      "track": {
        "id": "bilibili:BV1abc:p2",
        "title": "第二 P",
        "artist": "演唱者",
        "durationSeconds": 180,
        "source": "bilibili",
        "bvid": "BV1abc",
        "page": 2,
        "mediaUri": null,
        "coverUri": null
      },
      "playCount": 1,
      "lastPlayedAt": "2026-09-25T00:00:00Z",
      "totalQualifiedSeconds": 30.0
    }
  ]
}
```

`lastPlayedAt` 使用含时区 ISO 8601 时间；写入统一转为 UTC。`totalQualifiedSeconds` 仅累加达到阈值的会话有效时间。写入使用同目录临时文件、`fsync`、原子替换，并将上一个有效状态保存至 `history.json.bak`。不支持版本或损坏文件会报错并保留原件；运行中遭外部修改时拒绝覆盖。
