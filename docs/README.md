# 文档导航

按使用、开发与验证目的选择入口。项目概览和源码启动见 [仓库首页](../README.md)，版本变化见 [CHANGELOG](../CHANGELOG.md)。

当前 Windows 内测候选为 **v0.2.2**，构建身份与校验方式以 [候选说明](PHASE4_RELEASE_CANDIDATE.md) 为准。GitHub 尚无二进制 Release；文档中的 `build/` 路径是未随源码上传的本地材料，不是下载入口。独立朋友电脑和原首次启动闪退者复测仍待完成。

## 使用者

| 需要了解 | 文档 |
| --- | --- |
| 安装、开始听歌、升级与资料位置 | [Windows 使用指南](WINDOWS_GUIDE.md) |
| 安装目录、同版本升级与卸载 | [Windows 安装与卸载](WINDOWS_INSTALLER.md) |
| 首页、继续听与常驻歌单 | [个人首页](PERSONAL_HOME.md) |
| 歌单封面、说明、版本偏好与最近播放 | [收藏与记忆](COLLECTION_MEMORY.md)、[歌曲版本](SONG_VERSIONS.md)、[最近播放与桌宠](MUSIC_DIARY.md) |
| 普通播放、沉浸页、迷你窗口与桌宠 | [播放场景](PLAYBACK_SCENES.md)、[队列与导航](QUEUE_NAVIGATION.md)、[连续播放](CONTINUOUS_PLAYBACK.md) |
| 整理与精听 | [智能歌单](SMART_PLAYLISTS.md)、[片段批量整理](SEGMENT_BATCH.md)、[精听与练习](PRACTICE.md) |
| 推荐与可选 AI | [私人电台](PRIVATE_RADIO.md)、[AI 检索意图与设置](AI_INTENT.md) |
| 数据备份与本地更新 | [备份格式](BACKUP_FORMAT.md)、[本地更新与诊断](UPDATES_FEEDBACK.md)；当前包身份先查 [候选说明](PHASE4_RELEASE_CANDIDATE.md) |
| 试用与问题反馈 | [朋友试用说明](FRIEND_TRIAL.md)、[朋友反馈模板](FRIEND_FEEDBACK.md)、[GitHub Issue 表单](https://github.com/Redemption505/Lumen-Music/issues/new/choose) |

## 开发者

| 需要了解 | 文档 |
| --- | --- |
| 工具要求、源码启动与检查命令 | [开发与复现](DEVELOPMENT.md) |
| 贡献流程与仓库约定 | [贡献指南](../CONTRIBUTING.md)、[仓库工作规范](../AGENTS.md) |
| 模块职责与跨语言协议 | [架构与职责](ARCHITECTURE.md)、[本机接口契约](API.md) |
| 备份结构与有效听歌判定 | [备份格式](BACKUP_FORMAT.md)、[听歌记录格式](PLAY_HISTORY_FORMAT.md) |
| 搜索排序、推荐与 AI 边界 | [内容发现](DISCOVERY_RANKING.md)、[私人电台](PRIVATE_RADIO.md)、[AI 检索意图](AI_INTENT.md) |
| 主题、布局与动效规则 | [视觉语言](VISUAL_LANGUAGE.md)、[个人首页](PERSONAL_HOME.md) |
| 源码、素材与第三方材料范围 | [源码范围](PUBLIC_SCOPE.md)、[第三方来源核对](THIRD_PARTY_REVIEW.md) |

功能文档描述现有行为；修改接口、数据格式或安装方式时，同时更新对应说明。源码检查、CI、实际窗口、音频与安装版验收各有范围，具体入口见开发文档。

## 验证与规划

验收记录只证明注明的日期、构建、环境和操作；不能把旧记录当作当前源码或新安装包已重新通过。先看当前候选与验证汇总，再按需要追溯原记录。

| 用途 | 文档与范围 |
| --- | --- |
| 当前候选身份、已验证与待验证项 | [Windows 内测候选](PHASE4_RELEASE_CANDIDATE.md)、[验证结果汇总](VALIDATION.md) |
| 前端优化、样式修复与安装交付范围 | [前端交付验收](FRONTEND_RELEASE_ACCEPTANCE.md)、[第二阶段前端](FRONTEND_PHASE2_ACCEPTANCE.md)、[第三阶段前端](FRONTEND_PHASE3_ACCEPTANCE.md)、[第四阶段前端](FRONTEND_PHASE4_ACCEPTANCE.md)、[第二阶段路线](FRONTEND_STAGE2_ROADMAP.md)、[第二阶段验收](FRONTEND_STAGE2_ACCEPTANCE.md)、[自动焦点专项](FOCUS_VISUAL_ACCEPTANCE.md) |
| 启动采样方法、已有观测与缺口 | [Windows 启动验收](STARTUP_ACCEPTANCE.md) |
| 当前阶段目标与后续安排 | [第四阶段](../第四阶段.md)；已完成桌面范围、后置事项与待验证项分别说明 |
| 第四阶段审计与专项验收 | [审计范围](PHASE4_AUDIT_SCOPE.md)、[核心修复](PHASE4_CORE_ACCEPTANCE.md)、[稳定性](PHASE4_STABILITY_ACCEPTANCE.md)、[视觉](PHASE4_VISUAL_ACCEPTANCE.md)、[首页](PHASE4_HOME_ACCEPTANCE.md)、[收藏记忆](PHASE4_COLLECTION_ACCEPTANCE.md)、[交付](PHASE4_DELIVERY_ACCEPTANCE.md) |
| 源码交付与第三方材料边界 | [GitHub 源码准备](PHASE4_PUBLIC_PREPARATION.md)、[第三方材料准备](PHASE4_RELEASE_MATERIALS.md)；材料收集不等于公开二进制分发条件已完成 |
| 早期 v0.2.2 构建的验收追溯 | [首次综合验收](V022_ACCEPTANCE.md)、[滚动/片段/退出修复](V022_FIXES_ACCEPTANCE.md)、[歌词与交互反馈](V022_FRIENDS_ACCEPTANCE.md)、[安装与本地音乐](V022_INSTALL_LOCAL_ACCEPTANCE.md) |
| 更早阶段与版本记录 | [早期开发路线](ROADMAP.md)、[第二阶段](PHASE2.md)、[第三阶段基础](PHASE3.md)、[第三阶段拓展](PHASE3_EXPANSION.md)、[第三阶段验收](PHASE3_ACCEPTANCE.md)、[v0.2.1 验收](V021_ACCEPTANCE.md) |

早期路线和阶段文档中的“当前”“尚未实现”等表述对应其原阶段。现行能力从仓库首页及上方使用文档查阅；朋友反馈模板为空不代表已取得独立复测结果。
