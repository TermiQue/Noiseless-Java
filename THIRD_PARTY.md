# 第三方依赖与数据

本目录复用原项目随附的文章种子与离线词库，不重新爬取数据。

| 组件 | 版本 / 来源 | 许可证 |
| --- | --- | --- |
| Jackson Databind / Core / Annotations | Maven 固定 Databind 2.23.0，传递版本由其 POM 决定 | Apache-2.0 |
| SQLite JDBC | org.xerial:sqlite-jdbc:3.54.1.0 | Apache-2.0 / BSD-2-Clause；SQLite 本体公有领域 |
| SLF4J NOP | 2.0.17 | MIT |
| CommonMark Java / GFM tables | 0.32.0 | BSD-2-Clause |
| MP3SPI / JLayer / Tritonus Share | 1.9.5.4 / 1.0.1.4 / 0.3.7.4（com.googlecode.soundlibs） | LGPL-2.1，详见随包源码头 |
| JUnit Jupiter（仅测试） | 5.13.4 | EPL-2.0 |
| ECDICT 数据 | skywind3000/ECDICT，commit 39cfb9672c0906b63569e0453c2e6abfb9ab182a | MIT |
| morphemes 数据 | colingoldberg/morphemes，commit 846aa473cb27916f2c3acedb52d98f3a2e2a6572 | MIT |

词库条数 770,238。原始数据、压缩包和解压数据库的校验信息在 `src/main/resources/lexicon/manifest.json`。完整词库许可证随资源保留：

- [ECDICT-LICENSE.txt](src/main/resources/lexicon/ECDICT-LICENSE.txt)
- [morphemes-LICENSE.txt](src/main/resources/lexicon/morphemes-LICENSE.txt)

项目地址：[Jackson](https://github.com/FasterXML/jackson-databind)、[SQLite JDBC](https://github.com/xerial/sqlite-jdbc)、[CommonMark Java](https://github.com/commonmark/commonmark-java)、[ECDICT](https://github.com/skywind3000/ECDICT)、[morphemes](https://github.com/colingoldberg/morphemes)。

桌面图标以 Java2D 绘制常见线条符号；配色与布局来自本仓库既有 Noiseless UI。Java 运行时的许可随 jpackage 生成的 runtime 保留。

Windows/Linux 的 MP3 支持使用未修改的 [Soundlibs](https://github.com/pdudits/soundlibs) 组件。LGPL 许可证保存在 `src/main/resources/licenses/LGPL-2.1.txt`；三个固定版本的原始源码归档连同版权声明随 JAR 放在 `licenses/audio-sources/` 下。可以用 Maven 替换依赖版本并重新打包，以调试或修改这些组件；不限制 LGPL 允许的相关修改与调试。`NOTICE-audio.txt` 列出对应源包下载地址。

测试音频 `src/test/resources/audio/tone.mp3` 是本项目用 FFmpeg 合成的 440Hz、0.15 秒单声道正弦信号，没有第三方录音内容。
