# 数据备份 ZIP v1

此文档定义第二阶段的数据备份格式。备份仅保存用户自己的索引与设置，不包含 B 站音频、内部缓存或账号 Cookie。

## ZIP 布局

```text
manifest.json              必需
library.json               必需，沿用个人曲库 v1
history.json               可选，听歌记录 v1
ai-settings.json           可选，仅非敏感 AI 设置
background.png|jpg|jpeg|webp  可选，最多一张
```

`manifest.json` 为 UTF-8 JSON，根节点示例：

```json
{
  "format": "lumen-music-backup",
  "version": 1,
  "createdAt": "2026-09-25T00:00:00+00:00",
  "files": {
    "library.json": {
      "size": 58,
      "sha256": "<64 位小写十六进制 SHA-256>"
    }
  }
}
```

`files` 必须与 ZIP 中除清单自身以外的条目一一对应。`size` 是未压缩字节数；哈希覆盖完整的原始字节。各 JSON 文件均使用 UTF-8。`library.json` 的结构由 `LibraryStore` v1 定义；`history.json` 的结构由 `HistoryStore` v1 定义。

最近播放沿用此格式：私人备注 `diaryNotes`、歌词收藏 `lyricFavorites` 保存于 `library.json`，详细收听 `events` 和已知的 `firstPlayedAt` 保存于 `history.json`。新字段缺省时按旧版数据读取，不补造日期或播放来源；备份的结构校验、哈希和恢复覆盖这些字段。日记的 Markdown/文本导出仅供主动阅读或分享，不是可恢复的数据备份。

普通歌单可包含 `description`（最多 280 个 Unicode 字符）和 `coverDataUri`。两者缺省时均为空字符串；空封面表示未设置或已清除。封面必须是 `data:image/png;base64,...` 或 `data:image/jpeg;base64,...`，base64 严格校验，解码后最多 512 KiB，图片容器签名、PNG 块校验和或 JPEG 帧结构必须符合声明格式。图片字节直接内嵌在 `library.json`，因此完整 ZIP 导出、预检和恢复覆盖描述与封面，无需保留原图片文件或 file URI。

私人备注可包含真实 UTC 时间 `createdAt` 和 `updatedAt`（ISO 8601，`Z` 后缀）。新备注首次保存同时记录两者，后续修改保留创建时间、更新修改时间，同一曲目和相同正文的重复保存保持时间不变。旧备注缺少创建时间时保持未知，修改仅新增真实 `updatedAt`，不推断历史创建日期。日期随完整曲库备份与恢复保存；私人备注正文与日期不用于推荐或 AI 提示。

个人首页显式扩展 `library.json` v1 的 `homePreferences` 字段，独立标注首页偏好版本：

```json
"homePreferences": {
  "version": 1,
  "sections": ["continue", "pinned", "reunion", "discover"],
  "pinned": [{"kind": "playlist", "id": "实际歌单 ID"}]
}
```

`sections` 必须是继续听、我的常驻、重逢、发现新音乐四个区域的完整排列；`pinned` 按显示顺序保存最多六个普通（`playlist`）或智能（`smart`）歌单，ID 必须存在且不可重复。删除歌单同步移除常驻，改名保持相同 ID。旧曲库或旧备份缺少该字段时默认使用上述区域顺序和空常驻；存在但版本未知、格式无效或指向失效歌单时拒绝整个数据文件或恢复操作。导出、预检与恢复继续覆盖完整曲库，首页偏好不会改变收藏、收听历史或播放队列。

`ai-settings.json` v1 只允许 `version`、`enabled`、`provider`、`model`、`baseUrl`。导出时按白名单重新生成该文件，其他字段（包括 API Key、Token、密码）不会进入 ZIP。`baseUrl` 只接受不含 URL 用户名和密码的 HTTP(S) 地址。未来如需增加可备份设置，必须升级或显式扩展格式与验证；不能直接复制整个原配置文件。

## 导出、预检与恢复

`lumen_service.backup.export_backup(destination, library_path, *, history_path=None, ai_config_path=None, background_path=None)` 会先验证各源文件，再写临时 ZIP，最后替换目标文件。源文件无效时原有备份保持不变。返回 `BackupPreview`，包含可供未来界面展示的收藏数、歌单数、听歌记录数和背景图大小。

`lumen_service.backup.inspect_backup(source)` 是**只读预检**：它检查格式版本、条目白名单、重复条目、目录及符号链接、大小上限、SHA-256、JSON 内容及曲库与听歌记录结构。预检不会解压到文件系统，也不会修改现有用户数据。

`lumen_service.backup.restore_backup(source, library_path, *, history_path=None, ai_config_path=None, background_path=None)` 执行恢复。它先一次性读取并验证完整 ZIP；归档中含有的每个可选文件都必须提供目标路径，背景图目标扩展名需匹配归档。随后在各目标目录暂存新内容和原件副本，逐个替换。若一次替换失败，已写入文件会回滚；若回滚本身也失败，错误会说明未完成，并保留相应原件副本供人工恢复。未包含在 ZIP 中的可选数据保持原样。成功返回 `RestoreResult`，含预检信息及实际写入路径。

调用恢复时必须暂停曲库和听歌记录写入；完成后重新加载 `LibraryStore` 与 `HistoryStore`，避免内存状态与文件不一致。多个独立文件无法通过单次文件系统操作实现断电级原子性；本实现保障完整预检和进程内 I/O 失败时的回滚。

桌面使用的 HTTP 恢复入口在覆盖 AI 偏好前调用 `AiSettingsStore.preserve_credential_target()`，固定旧版独立密钥的原目标。备份仅恢复偏好，不把现有密钥绑定到备份中的新地址；旧配置损坏或没有明确目标时，密钥保留但暂停采用。直接调用上面的底层 `restore_backup` 且目标含已有 AI 密钥时，调用方须先执行同一保护步骤；底层 ZIP 恢复本身不管理凭据。`ai-secret.json` 与可能存在的 `.ai-settings-recovery.json` 均不在 ZIP 白名单中。

当前上限为 ZIP 64 MiB、单个 JSON 16 MiB、背景图 32 MiB。不接受目录、加密条目、符号链接、未知条目、多个背景图或不支持的版本。异常抛出 `BackupError`，应在界面展示而非覆盖现有文件。
