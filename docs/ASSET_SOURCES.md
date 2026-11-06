# 项目素材来源

核对日期：2026-11-06。范围为 Windows 桌面源码及文档中的项目素材，不包含个人封面、背景、音乐、歌词或运行缓存。

维护者在本轮明确确认：Lumen 图标（SVG/PNG/ICO）和源码绘制桌宠均由自己或此前 AI 在本项目中创作，未引入不明授权素材。此为维护者提供的创作来源确认；Git 首次出现和哈希另用于识别版本，不以它们代替来源证明。项目自制素材随仓库按根[GPLv3 许可](../LICENSE.txt)分发，第三方组件保留各自许可。

| 素材 | 来源、使用与版本核对 |
| --- | --- |
| [lumen.svg](../desktop/src/main/resources/icons/lumen.svg) | 项目图标矢量源，几何图形与渐变，无外部图像引用。当前 SHA-256：`56e73f72117bf4bc875c63fc1b181713646368fb0d1da10fb654984ac28b6e7d` |
| [lumen-256.png](../desktop/src/main/resources/icons/lumen-256.png) | 项目图标光栅格式；SHA-256：`9194d5098253f6e45d97131ba2a909604a1d1963789d06a42d96a52aa7784744`。没有找到可复现导出脚本，不宣称已复现 SVG→PNG 转换 |
| [lumen.ico](../desktop/src/main/resources/icons/lumen.ico) | Windows 窗口和安装图标；SHA-256：`e034cb0fbcc22f573f3ffc9d0c6c06ba1640a9ac0e5dbcc363f38b1a447e19e4`。转换步骤未在仓库保存，不将缺少脚本视为侵权结论 |
| [PetCompanion.java](../desktop/src/main/java/com/lumenmusic/PetCompanion.java) | 猫、幽灵、狗由 JavaFX 基础形状绘制；未引用独立桌宠图片。创作来源同上，具体实现受源码许可约束 |
| [深色首页截图](assets/home-lumen.png)、[浅色首页截图](assets/home-light.png) | 实际应用窗口、隔离合成曲库，通用占位封面和演示标题；不使用私人曲库或真实歌词。截图首次出现及后续 10 个历史 PNG 版本的隐私检查见[公开准备](PUBLIC_PRERELEASE_READINESS.md)，当前截图不证明独立电脑已验收 |
| 安装资源 | `release/installer/` 当前只有 WiX 模板，无额外图片；实际使用上述项目图标 |

三个图标首次进入现有 Git 历史为 `e3f917b`；本轮确认不推断历史仓库之前的转换工具。具体哈希和历史图像证据保存在不上传的本地审查目录。

后续新增截图或替换素材时记录来源和允许用途；真实音源的封面/歌词、用户自选图片与备份不因本页的项目创作确认而获得公开授权。当前包内第三方源码和原生组件的许可另见[第三方核对](THIRD_PARTY_REVIEW.md)。
