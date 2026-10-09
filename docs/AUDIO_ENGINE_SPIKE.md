# 音频引擎实验与选型

实验日期：2026-09-23。本实验验证 Java 端能否承担实际播放控制，并检查当前 JDK 对压缩音频的支持。后续已将 JavaFX Media 接入桌面播放器与在线音频。

## 输入和运行方式

在项目根目录执行：

```powershell
./scripts/audio-experiment.ps1
```

脚本编译 Java 源码，然后生成两段 22,050 Hz、16 位、单声道、各 2 秒的低音量 PCM WAV 测试音，放在被 `.gitignore` 忽略的 `build/audio-experiment/`。测试调用真正的 Java Sound `Clip` 输出设备，不使用模拟播放器。

另用 MDN 文档的 [MP3 示例](https://developer.mozilla.org/zh-CN/docs/Web/HTML/Reference/Elements/audio) 做了格式探测。测试文件下载到 `build/audio-experiment/mdn-t-rex-roar.mp3`，大小 39,868 字节，SHA-256 为 `41191D0727073BF848BCC8F0BD851D71A0B0058E901ABB1C1B236AD327BDA52E`。该文件不纳入源码交付。

```powershell
java -cp build/classes com.lumenmusic.CompressedFormatProbe build/audio-experiment/mdn-t-rex-roar.mp3
```

## 实测结果

运行环境：Windows，本机 OpenJDK 23.0.2，编译目标为 Java 17。

| 项目 | 结果 |
| --- | --- |
| Java Sound 获取 `Clip` | 成功，输出线可打开 |
| PCM WAV 加载和播放 | 成功，播放位置推进 |
| 暂停与恢复 | 成功，暂停期间位置不推进，恢复后继续推进 |
| 切换不同 WAV | 成功，新音频从头开始 |
| 停止 | 成功，停止后位置回到零 |
| 关闭及重新打开设备 | 成功 |
| 真实 MP3 解码 | 失败：`File of unsupported format` |

自动化无法确认人的听觉感受。本轮验证的是输出线、运行状态和播放时钟，短音已实际送入本机音频设备。若要确认物理扬声器或耳机听感，需要本机用户试听。

## 结论与下一步

`ClipAudioPlayer` 证明 Java 侧播放控制与设备生命周期可以工作，也可用于本地 PCM WAV 测试。它会将整个音频载入 Clip，不适合作为长曲目或在线流的最终实现。当前 JDK 对真实 MP3 样本无法解码；AAC/M4A 与 B 站真实流尚未取得样本验证，不能推断它们已经可用。

后续已引入 JavaFX Media 21.0.12。其[官方支持格式文档](https://openjfx.io/javadoc/21/javafx.media/javafx/scene/media/package-summary.html)列出 MP3、AAC、MP4/M4A 和 FILE、HTTP、HTTPS 来源。本机进一步验证了 MDN MP3、FFmpeg AAC/MP4 样本及通过 HTTPS 直接读取 MDN MP3；加载、播放、暂停、恢复、切换与释放均通过。测试音频只保存在被忽略的 `build/` 目录，不随源码分发。

AAC/MP4 样本来自 [FFmpeg 测试库](https://samples.ffmpeg.org/A-codecs/AAC/ct_faac.mp4)，大小 297,800 字节，SHA-256 为 `78EB88A991E12162A386D0033B0DC40AAF6E29A5C15889B2107C5D14D2A05788`。JavaFX 返回约 19.97 秒时长并成功播放。HTTP MP3 示例来自 [MDN](https://developer.mozilla.org/zh-CN/docs/Web/HTML/Reference/Elements/audio)，JavaFX 返回约 2.07 秒时长并成功播放。

`JavaFxAudioPlayer` 已接入 JavaFX 桌面的本地文件播放。上述测试不等同于 B 站音频：真实资源的编码、请求头、直链过期和进度拖动仍需验证。自动化不能确认人耳听感，需要用户在自己的输出设备上试听。
