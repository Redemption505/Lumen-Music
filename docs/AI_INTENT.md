# 可选 AI 检索意图

`service/lumen_service/ai_intent.py` 只负责把本地兴趣词转换成最多四条 B 站检索词。推荐模块仍须用真实数据源搜索候选、按本地规则重排，并检查曲目能否播放。模型返回的内容不能直接作为曲目、URL 或播放地址。

调用示例：

```python
from lumen_service.ai_intent import generate_search_intents

# 兴趣词由收藏、歌单和有效听歌记录提取并按相关程度排序。
intent = generate_search_intents(["周杰伦", "晴天", "华语流行"])
for query in intent.queries:
    # 使用现有真实搜索数据源取候选，再做本地重排。
    ...
```

`IntentResult.queries` 为不可变元组，`source` 是 `"ai"` 或 `"fallback"`。无兴趣词时返回空元组，调用方应显示尚无推荐依据的首页状态。未配置模型、配置无效、请求失败或响应不合规时，用相同兴趣词生成确定性的“关键词 音乐”查询。

桌面侧栏的“AI 推荐设置”可保存启用状态、模型、完整 HTTPS Chat Completions 地址和密钥。非敏感配置存入个人数据目录的 `ai-settings.json` 并随 ZIP 备份；密钥单独存入 `ai-secret.json`，不会被备份、API 读回或写入曲库。设置保存后刷新首页推荐即可生效。未建立设置文件时，仍可使用以下环境变量：

| 变量 | 含义 |
| --- | --- |
| `LUMEN_AI_CHAT_COMPLETIONS_URL` | OpenAI-compatible HTTPS `/chat/completions` 完整地址 |
| `LUMEN_AI_MODEL` | 模型名称 |
| `LUMEN_AI_API_KEY` | API 密钥 |

密钥绑定输入时的完整服务地址。更换地址或恢复含有其他地址的备份后，需要为该地址重新输入密钥；已有密钥仍保留，但不会自动发送到新地址。环境密钥也只用于与 `LUMEN_AI_CHAT_COMPLETIONS_URL` 相同的目标。旧版独立密钥在合法旧配置下固定其目标；旧配置损坏或没有可确认的地址时保留密钥，暂停采用，直到用户重新输入。

配置与密钥组合保存使用同目录的临时文件和 `.ai-settings-recovery.json` 恢复记录。任一替换失败时恢复旧文件；中断或回滚受阻后，下次读取先重试恢复。检测到不属于本次提交的外部文件修改时保留现场并报错。恢复记录与密钥文件一样包含凭据，只留在个人数据目录，不进入可移植备份或诊断包；若提示恢复未完成，恢复该目录的文件访问后重试，不删除恢复记录来绕过错误。

本机 HTTP 的 POST/PATCH/DELETE 要求正确的回环 Host 与端口；有 Origin 时必须与本机服务来源一致。POST/PATCH 使用 `application/xml` 或 `text/xml`，现有无 Origin 的桌面客户端与无请求体 DELETE 可继续使用。该检查约束本机写入入口，不是账户认证系统。

模型、地址与密钥必须齐全才发送请求；设置文件中的“关闭”优先于环境变量。请求使用 Python 标准库，无新增依赖；超时默认 5 秒、最大 15 秒，响应最多 16 KiB。最多传递 12 个、每个不超过 64 字符的简短兴趣词；含路径分隔符或冒号的词会被丢弃。不要向组件传文件路径、播放 URL 或完整曲库记录。响应只接受 `{"queries": ["..."]}` JSON，去重并限制每条查询长度。调用方可通过 `config` 和 `transport` 注入配置与传输，便于离线测试。

`GET /api/recommendations` 已将此组件接入“兴趣词 → 意图 → 真实候选 → 本地重排”闭环。无 AI 配置时端点使用本地检索词；有配置时仅将模型输出当成检索词，不接受模型提供的曲目或播放 URL。
