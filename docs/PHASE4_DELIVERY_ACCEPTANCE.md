# 第四阶段 C：独立使用与交付整理验收

本页本地证据与归档位于未随源码上传的 `build/`；不是 GitHub 下载入口。

日期：2026-10-09。结论：**C 的本地交付整理、推荐开发入口与安装目录选择的关键交互补验已完成。独立电脑试用、原闪退反馈者复测及公开许可未决项仍保留。** C 验收结束时安装与发行仍是 `lumen-0.2.2-20261007.4`；后续候选、真实安装及归档状态以 [D 候选验收](PHASE4_RELEASE_CANDIDATE.md) 为准。

本轮承接[第四阶段](../第四阶段.md) C，完成 §3.7、§3.8 的准备和 §4 试用材料，不自动执行 D 的正式候选构建/安装同步或 E 的 Git/外部发布。没有新增依赖、读取真实个人曲库/密钥/日志、联系朋友或上传资料。

## 1. 已交付内容

| 对象 | 当前结果 |
| --- | --- |
| [README](../README.md) | 分开安装使用与源码开发；统一特色及五主题说明；提供两张当前源码真实窗口截图，使用隔离合成曲库，无虚构下载链接 |
| [Windows 使用指南](WINDOWS_GUIDE.md) | 安装、同版本不同构建升级、增量包限制、永久卸载入口、文件夹导入/标签/歌词、备份范围及打不开应用时的反馈方法 |
| [开发与复现](DEVELOPMENT.md) | 明确 JDK/Python/Rust/VLC 与 MSVC/WiX 前提、参数/环境变量、基础检查和 CI/Windows 验收分工 |
| [更新说明模板](../release/v0.2.2/UPDATE-README.txt) | 修正旧 `.3` 与“修复安装”说法，补齐 `.4` 已交付变化；旧归档内文案仍需 D 重打包同步 |
| [朋友试用说明](FRIEND_TRIAL.md)、[反馈模板](FRIEND_FEEDBACK.md) | 提供可转发任务与缺陷闭环表；候选身份留待真实构建后填写，未发送、未伪造朋友结果 |
| [公开范围](PUBLIC_SCOPE.md)、[第三方核对](THIRD_PARTY_REVIEW.md) | 区分开发仓库、SOURCE 与附件；扩展忽略范围，检查候选内容和旧 SOURCE；记录实际接口、素材、组件及未决条件 |

推荐入口不再自动使用私人解释器路径、个人样本或历史最新镜像。新增 `check.ps1` 按组件检查并管理隔离 demo 服务；场景入口必须明确选择 `AppHome` 或 `RuntimeHome`。五个推荐脚本保存为 UTF-8 BOM，修复 Windows PowerShell 5.1 误读中文导致的解析失败。历史镜像专用脚本的前提另列，不把它们当作新开发者的基础入口。

## 2. 推荐入口与实际窗口验证

| 检查 | 实际结果与边界 |
| --- | --- |
| PowerShell 7.6.5 基础 demo | `check → desktop → SmokeTest` 实际服务协议通过，资料与环境隔离并清理自有服务 |
| Windows PowerShell 5.1 基础 demo | 编码修复后，实际同一推荐入口离线通过；五脚本解析及参数发现通过，文本语义保持不变 |
| Rust 基础入口 | 现有锁文件离线检查：7 通过、1 个辅助测试忽略；没有升级依赖 |
| Python 服务入口及参数 | 环境变量指定完整 Python 后实际健康检查通过；7 项缺失/冲突参数拒绝、启动参数传递通过 |
| Maven/JNA 与打包清单 | 实际依赖图和旧包清单核对；普通 JNA 与 JPMS JNA 均含核心类，不能以共存推断故障，也未盲目删包 |
| 显式 RuntimeHome 个人首页 | 当前 classes、现有 VLC/依赖、独立合成资料；空曲库、继续播放、五主题两种窗口尺寸、长常驻、布局备份往返及新应用暂停恢复全部断言通过 |

首页使用 `Normal` 测试配置；实际系统输出缩放为 1.5，窗口逻辑尺寸为 1280×800 / 860×600，不将名称当作 100% 显示证据。README 截图来自该次运行，仅复制已检查的两张主题首页图，没有访问个人曲库或网络音源。

本轮不因文档/入口修改重跑 B 全量测试。[B 验收](PHASE4_CORE_ACCEPTANCE.md)的 Java 320 通过、Python 362 通过/2 跳过及 FLAC、拖动、启动/退出/桌宠证据继续保留；它们不能证明旧安装已包含修复、远程 CI 已执行或朋友已通过。

本地证据：

- 开发入口验证摘要（本地路径：`build/phase4-delivery-c/developer/verification-summary.json`）
- Windows PowerShell 5.1 实际 demo（本地路径：`build/phase4-delivery-c/developer/powershell51/demo/c78674a3c6a342ac96d4c15ab5760d3e/demo.log`）
- 当前源码个人首页运行输出（本地路径：`build/phase4-delivery-c/developer/personal-home-smoke/normal-e28c78dedd784432ac82bb1941f42ff4/desktop.stdout.log`）

## 3. 安装目录选择：新发现与定向修复

原生鼠标补验确证旧 `.4` 安装器的一个缺陷：选择新目录成功后，浏览按钮又向当前 `LumenInstallDirDlg` 发出 `NewDialog`，日志报 2856，创建了同名对话框的第二份实例。删除跳转后，安装引擎路径正确，但原有输入框仍显示旧值，说明单改安装属性不能完成界面更新。

修复保持单个目录页面和 Windows Shell 文件夹选择器：原生动作将当前/选中路径返回到 `LUMEN_CHOSEN_INSTALLDIR`，界面通过标准 SetProperty 更新 `INSTALLDIR`，再执行 SetTargetPath。取消选择先返回当前路径，避免沿用上一次选择的陈旧结果。依据为微软 [SetProperty](https://learn.microsoft.com/en-us/windows/win32/msi/setproperty-controlevent)、[PathEdit](https://learn.microsoft.com/en-us/windows/win32/msi/pathedit-control) 与 [NewDialog](https://learn.microsoft.com/en-us/windows/win32/msi/newdialog-controlevent) 的事件说明；最终判断来自下面的实际复验。

| 验证 | 结果 |
| --- | --- |
| 当前 C++/WiX 资源真实编译 | 现有 MSVC/WiX/JDK 编译合成载荷，11 项只读 MSI 结构检查通过；旧 `.4` 正确被新回归检查拒绝 |
| Windows 原生鼠标复验 | 浏览 → 从初始测试子目录选择父目录 → 输入框立即显示新路径；再次浏览从新路径开始 → 取消后路径保持正确 |
| 退出与隔离 | 取消向导后退出码 1602；无 2856、失败动作或 ExecuteAction；预览 ProductState=-1，未安装或注册产品 |

预览采用独立 ProductCode/UpgradeCode/PackageCode，移除安装执行入口与执行序列，未操作现有安装。第一份预览曾复用旧 PackageCode 而进入缓存维护页，随后纠正隔离身份；这不是产品目录错误的根因。当前通过的是**新编译资源的禁用安装预览**，不是新正式安装包的首装/升级/卸载验收。

- 旧 `.4` 错误复现日志（本地路径：`build/phase4-delivery-c/installer-ui/baeb81cb5f3e41d29fba42ecdcb0fdf7/ui-preview.log`）
- 新资源结构检查（本地路径：`build/phase4-delivery-c/installer-compiled/c18cda5008af451786c33a67c68b4e58/msi-structure.json`）
- 最终原生复验（本地路径：`build/phase4-delivery-c/installer-ui/d41a58812f8b4d16bf417aea372a9cc4/result.json`）及可见路径记录（本地路径：`build/phase4-delivery-c/installer-ui/d41a58812f8b4d16bf417aea372a9cc4/visible-paths.json`）

该缺陷发生在安装目录页面，没有证据证明它导致朋友报告的第一次启动闪退；后者继续待原反馈者复测。

## 4. 待验证条件与下一步

| 条件 | 当前状态及后续位置 |
| --- | --- |
| 没有开发工具的独立 Windows 安装与核心使用 | 未执行；D 新候选交付后按试用材料登记实际系统、构建和结果 |
| 朋友无人讲解使用、原首次启动闪退复测 | 未取得结果；模板空白保留，不以开发机通过替代 |
| B/C 源码进入真实安装与完整/增量/SOURCE 包 | 未同步；D 冻结新唯一构建号，备份、构建、安装及校验实际字节 |
| 接口、歌词/封面/素材公开边界与组件材料 | 未决项已具体登记；公开前解决或审阅明确的范围调整，候选仍私下准备 |
| Git、远程 CI、公开下载/发布 | 未执行；E 在具体仓库、可见性、范围与授权确定后处理 |

C 结束后的下一步为 D「发布候选」，其实际结果见 [D 候选验收](PHASE4_RELEASE_CANDIDATE.md)；本页保留 C 当时的验证范围。独立环境和外部反馈继续收集，未达到公开门槛时维持明确标识的内测候选。
