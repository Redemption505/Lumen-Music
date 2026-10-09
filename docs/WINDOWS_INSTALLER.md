# Windows 安装与卸载

完整包中的 `LumenMusic-版本-Setup.exe` 使用 Windows Installer，为当前用户安装。中文向导提供完整路径输入和 Windows 标准文件夹选择窗口。升级默认沿用已登记的安装目录；命令行显式传入的 `INSTALLDIR` 优先。

安装完成后，安装文件夹及开始菜单的 **Lumen Music** 文件夹均有 **卸载 Lumen Music** 快捷方式。快捷方式调用系统 `msiexec.exe /x {已安装产品代码}`，使用 Windows Installer 的本机缓存；删除下载的 Setup.exe 后仍能卸载。Windows“设置 → 应用”也可卸载。卸载移除程序及快捷方式，保留 `%LOCALAPPDATA%\Lumen Music` 与 `%APPDATA%\Lumen Music` 中的个人资料。

同一版本的不同修复构建使用各自的 MSI 产品代码，从而通过升级流程替换旧构建；同一构建保留相同代码，可再次执行维护。升级移除旧程序的操作发生在 Windows Installer 回滚事务内。安装包版本仍为项目版本，应用内构建号用于区分修复构建。

## 构建

使用项目已声明的 JDK 23、WiX 3.14.1 与现有 MSVC x64 编译工具链。安装动作编译为原生 x64 DLL，并静态链接 C++ 运行时；用户无需额外安装 .NET 或 Visual C++ 运行时。文件夹窗口由 Windows Shell 的 `IFileDialog` 提供。升级动作按安装包的 UpgradeCode 查询已安装同族产品，从 Windows Installer 登记读取其实际安装目录，适用于各旧版本及自选目录。

先构建并测试 app-image，再传入该目录和相同构建号：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/package-windows.ps1 -Type exe -AppHome <已测试的LumenMusic目录> -BuildId <构建号>
```

`scripts/installer/prepare-resources.ps1` 生成本次构建的资源、自定义动作及产品代码。`release/installer` 保存有效的 WiX 向导模板；完整发行包的对应源码包含这些文件。

`scripts/test-windows-installer.ps1 -Msi <安装包内的MSI> -BuildId <构建号> -Report <报告路径>` 以只读方式核对 MSI 身份、升级顺序、目录选择动作与卸载入口。它不会执行安装或卸载，不能替代实际安装版界面和卸载验收。实际卸载测试应使用独立测试账户或已明确授权的目标，并核对个人资料保持不变。

`scripts/test-installer-lifecycle.ps1 -Jpackage <JDK23的jpackage.exe>` 生成独立 QA 产品族、菜单、注册表及项目内安装目录，使用小型合成载荷执行真实的首次安装、同版本升级与卸载。升级交易不指定目录，以验收旧自选路径的自动发现；卸载前移走下载 EXE 和源 MSI，再调用已安装卸载快捷方式的目标与参数。脚本先检查 MSI 身份及注册表范围，只清理本次 QA 产品，并保存正式安装登记前后快照、MSI 日志与结果。它不启动播放器或操作个人资料，也不验证向导的人工点击流程。
