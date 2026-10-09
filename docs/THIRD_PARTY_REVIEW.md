# 第三方来源与公开前核对

现行资料访问基线：2026-10-08；本地材料更新：2026-10-09。**实际来源已定位，公开使用与部分组件再分发材料仍有未决项，候选保持私下验证。** D 已交付 `.2` 内测候选，E 已整理匹配该镜像与缓存校验和的本地材料，见[材料准备](PHASE4_RELEASE_MATERIALS.md)。用户本轮暂不推送。源码及隐私范围见[公开范围](PUBLIC_SCOPE.md)；组件许可证、接口条款与媒体内容权利分别判断，程序可播放不能代替授权。

## 1. 音源、歌词、封面与 AI 的实际调用

| 来源/对象 | 实际方式与发送数据 | 本项目拟分发内容 | 状态与公开前条件 |
| --- | --- | --- | --- |
| B 站搜索/榜单/详情/分 P/公开收藏夹 | `providers.py` 请求搜索、ranking、view、pagelist、nav；游客 `buvid3/4` 与 WBI；`favorite_import.py` 读取游客可见收藏夹。发送关键词、BV/页码或收藏夹ID以及正常HTTP元数据，当前无需用户登录cookie | 接口适配源码与桌面功能，用户运行时获取视频元数据；不在默认源码候选附真实响应、私人收藏夹或游客会话 | 官方协议包含自动获取服务/内容/数据的书面许可条件。未找到本项目取得平台许可的证据，接口用途边界未解决 |
| B 站取流与缓存 | `streaming.py` 请求 `x/player/wbi/playurl`，取DASH AAC音轨，Python代理及Rust侧车转发Range；桌面会话缓存和默认关闭的持久缓存可保存完整音轨 | 播放、拖动与缓存代码；不公开音轨缓存或不明权利样本 | 需要分别确认平台接口使用、独立音频播放、临时/持久缓存边界与具体音源权利；游客能取流不是许可证据，不引入访问限制绕过方案 |
| LRCLIB 歌词 | `lyrics.py` 请求 `https://lrclib.net/api/search`，发送曲名/歌手等检索条件，返回同步歌词；应用匹配后本机缓存，用户可手选/校准 | 调用/匹配代码、人工合成歌词fixture；不默认分发检索所得歌词缓存 | 作者仓库明确提供机器友好API，仓库为MIT；这是服务器软件许可，不能推断所有歌词文本也为MIT。具体歌词展示、缓存和再分发条件未核实 |
| 网易云补充歌词 | 同文件请求 `https://music.163.com/api/search/get` 与 `/api/song/lyric`；发送曲名/歌手或歌曲ID，加网易云Referer；没有在项目中引入该站SDK | 调用代码；不附抓取歌词数据库 | 官方现行服务条款正文本轮未能取得，未找到上述端点对第三方用途的授权材料；不得将匿名HTTP响应作为授权。服务条款、歌词权利与缓存边界均待明确 |
| 本地歌词/内嵌歌词 | `LocalAudioMetadata` 与LRC读取；用户自选文件、本机保存 | 读取代码与合成fixture，不附用户文件 | 使用者选择文件不产生发布者公开权利。示例/截图若含真实歌词须另有展示许可 |
| 视频封面 | `providers.py` 读取搜索/详情 `pic`；`CoverCache` 从网络加载并保存成功封面；歌单可组合最多4张已有曲目封面 | 展示/缓存/拼图代码，不附实际封面缓存或真实截图 | 音源许可不自动覆盖封面、人物肖像/商标。公开截图和封面样本须逐一确认来源与允许用途 |
| 自选封面/背景、本地内嵌封面 | 用户选择图片/背景或读取本地音频内嵌图；自选歌单封面可嵌入曲库/备份 | 处理代码；个人图片/备份不默认公开 | 不扫描真实个人图片；公开演示使用自产或已获准材料 |
| 工程图标与桌宠 | 资源树含 `icons/lumen.svg`、`lumen-256.png`、`lumen.ico`；桌宠/缺图符号主要由Java绘制。SVG可读，但未找到PNG/ICO原始生成过程或独立作者/素材授权记录 | 这些资源随JAR/安装器进入交付；拟纳入源码候选 | 需要确认项目图标与绘制素材的创作/授权来源及三种格式对应关系。代码存在本身不能证明素材权利，当前不宣称已取得素材许可 |
| 可选AI | `ai_intent.py` 向用户配置的HTTPS Chat Completions地址发送模型及有限提示数据，使用Bearer密钥；生成检索词或重排真实候选，输出不作为音源URL | 可配置的兼容协议客户端；不内置公共账号/密钥，不收集发布者云账号 | 按用户自主选择的实际服务核对其条款/隐私。未指定服务不假定某一家供应商；公开说明必须准确标明实际发送数据，不能声称全程本地 |

AI实际提示由：最多12个兴趣词、收藏/歌单抽取的歌手/曲名/音乐标签摘要、最多160字符的本次引导，以及重排时最多60个候选的ID/标题/歌手/时长构成。代码不会将原始完整听歌历史、日记、备注、个人备份或本地音频正文加入该请求体；候选标题、摘要和用户引导仍可能包含用户输入的信息，不能声称天然匿名。模型/密钥与目标地址是用户选择；AI关闭或无有效配置时使用本地检索/排序路径。授权头不进入提示词，本机密钥和组合恢复文件属于私密资料；本轮未读取真实配置或密钥。

代码定位：[providers.py](../service/lumen_service/providers.py)、[streaming.py](../service/lumen_service/streaming.py)、[favorite_import.py](../service/lumen_service/favorite_import.py)、[lyrics.py](../service/lumen_service/lyrics.py)、[ai_intent.py](../service/lumen_service/ai_intent.py)、[CoverCache.java](../desktop/src/main/java/com/lumenmusic/CoverCache.java)、[PlaylistArtwork.java](../desktop/src/main/java/com/lumenmusic/PlaylistArtwork.java)、[LocalAudioMetadata.java](../desktop/src/main/java/com/lumenmusic/LocalAudioMetadata.java)。

## 2. 官方现行资料核对

下表均为2026-10-08的访问结果。网页可能随后更新；访问日不是协议生效日。只将直接可读取的一手来源用于事实判断，抓取失败/空正文按未决记录，不使用第三方转载代替现行原文，也不将其他产品协议套用到本项目。

| 官方来源 | 本轮可确认的事实 | 对本项目的解释/限制 |
| --- | --- | --- |
| [B站用户使用协议](https://www.bilibili.com/blackboard/protocal/licence.html) | 官方搜索索引可读§5.6(3)、§8.5～8.6；直接页面正文抽取为空。§5.6(3)要求自动程序/脚本/软件获取平台服务、内容、数据前取得明确书面许可；§8.5涉及自制/独占内容的转载、传播及提供观看服务 | **事实：**条款存在上述条件。**本项目推断：**当前直接API请求与音轨代理需对照确认授权范围。尚无许可文件或适用例外证据；不能据此单独裁定侵权，也不能将未知写为已获准 |
| [网易云官方服务条款入口](https://st.music.163.com/official-terms/service)；[旧网页入口](https://music.163.com/html/web2/service.html) | 两个官方入口在本轮浏览中不可读取；搜索未取得可核对的现行条款正文 | 不引述未取得的条文；官方端点授权、歌词内容与缓存/再分发条件待补。接口返回成功只证明技术可达 |
| [LRCLIB官方API文档](https://lrclib.net/docs)；[作者维护的项目仓库](https://github.com/tranxuanthang/lrclib)与[许可证](https://raw.githubusercontent.com/tranxuanthang/lrclib/main/LICENSE) | 官网文档抽取为空；作者仓库介绍确认免费且面向机器的API，服务器源码为MIT | 服务器许可与歌词文本权利不同；本轮没有取得覆盖歌词展示/缓存/再分发的内容许可 |
| [GNU官方GPL FAQ](https://www.gnu.org/licenses/gpl-faq.en.html#AnonFTPAndSendSources)；本地GPLv3全文 | GNU FAQ说明网络提供二进制时源码应同样便于取得且对应相同版本；本地GPLv3第1、6节定义对应源码和交付方式。官方GPL全文网页两次超时 | GPL不能为平台音源/歌词提供授权。对应源码并非整个个人开发目录；需包含实际要求的源码、接口及构建/安装控制材料，排除个人秘密。最终义务按具体组件及交付方式核对 |
| [OpenJFX官方许可证](https://github.com/openjdk/jfx/blob/jfx21/LICENSE) | GPLv2附Classpath例外，例外以指定文件标注为条件 | 不能把例外推广成全部原生库与素材免于保留许可；必须核对实际Windows组件与对应材料 |
| [JNA 5.13.0官方许可证](https://raw.githubusercontent.com/java-native-access/jna/5.13.0/LICENSE)、[5.14.0](https://github.com/java-native-access/jna/blob/5.14.0/LICENSE) | 可选择Apache-2.0或LGPL-2.1及之后版本 | 当前包保留JAR内 `META-INF/LICENSE`，但该文本主要说明选项与链接；选择分发方案后保留适用全文与实际NOTICE/原生归属材料 |
| [vlcj作者仓库](https://github.com/caprica/vlcj#license) | GPLv3或之后版本 | 精确4.11.0/4.8.3以已缓存发布POM和附带源码JAR为证；不能只依据当前开发分支证明旧版本材料完全一致 |
| [VideoLAN官方法律说明](https://images.videolan.org/legal.html)、[libVLC介绍](https://images.videolan.org/vlc/libvlc.html)、[模块改许可说明](https://images.videolan.org/press/lgpl-modules.html) | libVLC标为LGPL2.1；整体VLC与部分插件仍有GPL组件；可按许可证再分发，官方说明源码包括随附底层库 | 本项目复制全插件目录，不能将整个目录统一声明为LGPL。需对实际3.0.23构建插件/底层库和源码材料对应核对 |
| [Python官方许可说明](https://docs.python.org/3.13/license.html) | PSF License v2并有内嵌组件各自条款；要求保留相关许可/版权，修改Python时说明修改 | 在线3.13分支现显示3.13.16，不能代替随包3.13.15精确文本。实际3.13.15的 `app/python/LICENSE.txt` 与源码归档作为版本证据 |

## 3. 实际打包组件与再分发材料

下表保留 C 的 `.4` 实际组件核对基线，证据见[打包组件清单](../build/phase4-delivery-c/public/packaged-notices.json)和[Windows普通依赖图](../build/phase4-delivery-c/public/rust-windows-normal.txt)。当前 D `.2` 的镜像、JAR/SOURCE身份、JDK/VLC缓存匹配及98个普通图crate的本地材料由[E 材料准备](PHASE4_RELEASE_MATERIALS.md)补充；已收集正文与仍缺对应来源分别登记，没有用 C 的旧包清单冒充新归档。

| 组件 | 实际存在/许可证依据 | 已有材料 | 未决与补齐门槛 |
| --- | --- | --- | --- |
| Lumen应用 | GPLv3；根 `LICENSE.txt`、发行声明与 `release/licenses/GPL-3.0.txt` | SOURCE含项目源码、构建脚本、BUILD-INFO/哈希；完整/更新包附GPL全文 | 新候选源码/资源变化后重新构建、重新组装对应SOURCE；验证实际字节和构建身份，不以旧包覆盖新结果 |
| Java运行时23.0.2 | `runtime/release`确认版本；`runtime/legal/*`为模块许可/附加条款，GPLv2及Classpath例外需按实际模块看待 | 镜像保留 `runtime/legal/` | 当前声明没有精确JDK来源与对应源码取得方法，`runtime/release`未显示发行供应商字段；需补构建来源、相同版本源及模块第三方材料。官方OpenJDK许可URL本轮403，未将其当已核对文本 |
| JavaFX21.0.12 | 四个Windows JAR：base/graphics/controls/media；OpenJFX GPLv2及Classpath例外 | 声明提供四模块源码JAR的下载规则 | 四JAR未发现license/copying/notice命名成员，当前SOURCE未附这些源码；需核对精确版本许可全文、原生组件/底层库材料及相同版本源码，不只给通用许可链接 |
| Python3.13.15 | 官方embedded runtime；服务仅标准库，无新增pip依赖 | `app/python/LICENSE.txt`；SOURCE/third-party附 `Python-3.13.15.tgz`；脚本固定官方来源与SHA256 | 保留所有实际embedded组件许可。当前用项目 `_pth` 定位服务目录，应说明配置替换，不能声明运行时未做任何改动 |
| VLC3.0.23 | 两DLL与全插件目录；实际368个文件，其中插件365个文件；根COPYING为GPLv2，libVLC核心与插件需分别识别 | `app/vlc/COPYING.txt`，声明链接同版 `vlc-3.0.23.tar.xz` | SOURCE没有该VLC源归档或底层库源/构建材料；未逐个插件确认许可与native库归属。需要按实际Windows构建完善源码与适用许可/NOTICE方案；不能把全部插件当LGPL或把一条VLC源码URL视作完整解决 |
| JNA | 普通jna/jna-platform5.13.0和JPMS5.14.0，共四JAR，双许可 | 各JAR `META-INF/LICENSE`，声明列jna/jna-jpms源URL | 声明还应精确覆盖platform两种构件；核对选用许可全文、NOTICE和内含native代码条件。两套JAR用途由依赖/运行链路核对，许可证审阅不以共存认定故障 |
| vlcj4.11.0、vlcj-natives4.8.3 | 两JAR；发布元数据GPLv3，作者当前说明GPLv3或以后 | SOURCE/third-party附两个sources.jar，发布根GPL全文 | 二进制JAR未发现独立许可成员；确保附带源码及版权/许可与精确版本匹配，不只依赖当前分支说明 |
| Rust侧车 | Windows normal依赖图已离线实际取得；直接依赖Axum0.8.9/Tokio1.53.1/Bytes1.12.1为MIT，Reqwest0.12.28/futures-util0.3.34/url2.5.8为MIT或Apache-2.0 | SOURCE含项目 `.rs`、Cargo.toml/Cargo.lock；声明只列6个直接依赖下载URL | 传递依赖不能省略：Windows图含ring的Apache-2.0 **AND** ISC、ICU的Unicode-3.0、webpki-roots的CDLA-Permissive-2.0等；须保留选择方案与实际版权/NOTICE/全文，不把所有crate统一说成MIT/Apache |

Rust锁文件有143个外部crate条目，本机缓存可读120份元数据，23份未缓存；这个数字包含build/dev/其他平台，**不等于143个都进入Windows二进制**。本轮进一步执行 `cargo tree --locked --offline --target x86_64-pc-windows-msvc -e normal` 成功，实际Windows普通依赖图与锁文件区分保存。仍未把每份适用LICENSE/NOTICE正文汇总进发行包，未检查sidecar二进制链接来源的完整证明，也未进行漏洞数据库审计；不宣称组件再分发检查全部通过。

现有第三方声明源在[release/v0.1.0/THIRD-PARTY-NOTICES.txt](../release/v0.1.0/THIRD-PARTY-NOTICES.txt)，完整打包按当前版本替换抬头；不存在独立 `release/v0.2.2/THIRD-PARTY-NOTICES.txt` 模板。两份既有SOURCE各439成员，third-party只包含Python、vlcj与vlcj-natives三个源码归档；新清单不能被说成已经加入旧包。

## 4. 未决事项与公开判据

| 未决点 | 完成需要的具体证据/处置 | 当前状态 |
| --- | --- | --- |
| B站平台与音源 | 针对实际API、音轨代理/缓存用途的适用许可或允许范围，以及公开演示音源权利；若不能明确，审阅后决定限制具体功能/展示范围 | 无本项目授权证据；私下候选继续验证，不默认公开完整在线功能 |
| 两歌词来源 | 取得网易云现行原文与端点用途边界；确认LRCLIB歌词内容展示/缓存/再分发条件，不能用服务器MIT代替 | 条款取得/内容权利未完成 |
| 图标、封面与公开截图 | 项目图标作者/生成来源与三格式对应说明；所有公开截图/示例媒体逐项记录自产或授权依据 | 未读取私人素材；未确认图标生成记录 |
| AI使用说明 | 依据实际请求准确说明向用户所选服务发送有限摘要/候选/引导；对应供应商由用户自主选择，不强制收集云账号 | 发送字段已源码核对；供应商相关条款取决于实际选择 |
| 二进制组件材料 | 依最终Windows图与native插件补齐精确版本的源码取得方案、适用全文、版权与NOTICE；Java/JavaFX/VLC/Rust的缺口不能用统一GPL文本覆盖 | 主要组件已有材料，完整性未验收；向朋友提供二进制也须按实际再分发方式检查 |
| 范围与新归档 | 扫描最终候选与SOURCE；修正文档版本关系，重组装所有受影响归档并计算真实哈希 | D `.2` 归档/对应SOURCE已核对；E开发仓库后续内容单独扫描，未重新发行二进制 |

以上是具体对象的公开门槛，不是新增假想平台的审批清单。未指定公开平台就保留本地私下候选；不会默认要求注册账号、上传材料、联系服务方或改变用户功能。若需要功能限制或授权询问，应先给出对象和影响，按用户决定执行。
