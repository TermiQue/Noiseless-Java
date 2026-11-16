# Windows 使用与构建

面向 Windows 10/11 x64。界面仍为 Swing/Java2D，与 macOS 共用文章、词库、账号和 AI 代码；Windows 使用系统标题栏、逻辑字体回退与系统文件选择器。后端可以继续使用已部署的 Ubuntu 服务，无需迁移服务器。

## 直接使用便携包

仓库的 [Windows 工作流](https://github.com/TermiQue/Noiseless-Java/actions/workflows/windows.yml) 成功后，在该次运行的 Artifacts 中下载 `Noiseless-windows`。解压 artifact 后，再解压其中的 `Noiseless-windows.zip`，运行 `Noiseless\Noiseless.exe`。

必须保留整个 `Noiseless` 目录（含 `app` 和 `runtime`），不能只复制 EXE。它自带 Java 运行时，无需安装 Java、Maven、Python 或额外音频解码器即可运行桌面端。当前提供便携应用，未提供签名安装器或自动更新；下载 CI artifact 需要 GitHub 登录。

关闭应用后，可用新版完整目录替换旧版应用目录。本地资料保存在用户目录，不在应用安装目录中。

## 从源码构建

安装 Windows x64 完整 JDK 21+（包含 `java`、`javac`、`jpackage`），设置 `JAVA_HOME` 并将 `%JAVA_HOME%\bin` 加入 PATH。Windows PowerShell 5.1 和 PowerShell 7 均可调用以下命令：

```powershell
git clone https://github.com/TermiQue/Noiseless-Java.git
cd Noiseless-Java
.\mvnw.cmd verify
java --enable-native-access=ALL-UNNAMED -jar .\target\noiseless.jar
```

`mvnw.cmd` 首次从 Maven Central 下载 Maven 3.9.11 的 ZIP，核对官方 SHA-512，再解压到项目 `.cache`；后续复用缓存。不要求安装 Maven 或修改系统执行策略。首次构建需要访问 Maven Central。

打包含运行时的应用：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\package-desktop.ps1
```

脚本默认执行 `clean verify`，之后调用当前 JDK 的 `jpackage`。已经完成构建测试时可加 `-SkipBuild`。`Bypass` 只作用于这次 PowerShell 进程。

输出：

```text
target\native\Noiseless\Noiseless.exe
target\native\Noiseless-windows.zip
```

此脚本使用 `app-image`，无需 WiX；必须在 Windows 构建 Windows 应用，macOS 的 `.app` 不能转换为 Windows EXE。关闭正在运行的旧版应用后再打包，避免文件锁影响替换。脚本先在临时目录构建，失败时保留旧包；ZIP 包含整个应用目录。

## 连接当前测试服务器

在 Windows PowerShell 中保持 SSH 隧道运行：

```powershell
ssh -N -L 127.0.0.1:18088:127.0.0.1:18088 ubuntu@159.75.1.175
```

使用你自己的 SSH 密钥/服务器登录方式；密钥不随应用分发。在桌面左下角「设置 → 账号与云服务 → 登录 / 注册」填写 `http://127.0.0.1:18088`，登录或创建测试账号。模型选择 `服务器 / mock-noiseless`。目前是模拟模型，不产生真实 AI 费用。

如果已有正式 HTTPS 服务，可直接填写其 HTTPS 地址，无需 SSH 隧道。运维方式见 [SERVER.md](SERVER.md)。

## 数据、快捷键与格式

| 项目 | Windows 行为 |
| --- | --- |
| 本地资料 | Java `user.home` 下 `.noiseless-java\local\`，通常为 `%USERPROFILE%\.noiseless-java\local\` |
| 本地账号凭据 | `.noiseless-java\local-account.json`，新文件限制为当前用户 NTFS ACL |
| 个人 AI 主密钥 | 数据目录中的 `ai-master.key`，新文件限制为当前用户 NTFS ACL |
| 前进/后退 | Alt+右/左方向键、Ctrl+] / Ctrl+[，也可点顶栏按钮 |
| 复制/粘贴/全选 | Ctrl+C / Ctrl+V / Ctrl+A |
| AI 输入 | Enter 发送，Shift+Enter 换行；输入法组合期间不发送 |
| 工具栏还原 | Escape |
| 音频 | 内置 Java MP3 解码，支持 MP3 与 PCM WAV；AAC/M4A 等需先转换 |
| HEIC/HEIF | Windows 上传前先转成 JPEG/PNG；不依赖已安装的 Windows HEIF 扩展 |
| 旧词库迁移 | 文件 URI 处理盘符、中文、空格与 `#`；源库以只读连接打开 |

首次基础查词会在本地解压随包词库，可能短暂等待；后续复用缓存。凭据应放在支持 ACL 的 NTFS 用户目录，不要把数据目录迁到不支持访问控制的文件系统。

原生窗口采用 Java DPI 缩放，建议使用系统显示缩放设置。物理触控板双指手势、中文输入法组合候选、125%/150%/200% 多屏缩放、音频设备和完整人工点击流程仍需 Windows 设备验收。

## 在 Windows 单独运行后端

```powershell
$env:NOISELESS_DATA_DIR = Join-Path $env:USERPROFILE 'Noiseless-server-data'
$env:NOISELESS_HOST = '127.0.0.1'
$env:NOISELESS_PORT = '8080'
java --enable-native-access=ALL-UNNAMED -jar .\target\noiseless.jar server
```

从另一个终端检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/health
```

这只是前台启动，未安装为 Windows Service。正式单服务器部署仍建议按 README 的 Linux/Docker/HTTPS 方案。关闭服务后再替换 JAR；数据和主密钥另行保留。备份命令和数据库恢复规则见 README。

## 自动验证与故障排查

Windows CI 使用 Temurin JDK 21、Windows PowerShell 5.1 和带空格的仓库路径，执行：

1. `mvnw.cmd verify`：包含跨平台后端、凭据权限、MP3 解码及中文/特殊字符路径测试。
2. `package-desktop.ps1 -SkipBuild`：构建 EXE、运行时与 ZIP。
3. `test-windows-package.ps1`：使用随包 Java 运行后端，执行 43 项模拟 AI/账号/文章/词库接口检查、6 项重启检查，再启动原生 EXE，确认窗口和隔离资料目录初始化；测试结束停止进程、还原配置并清理临时凭据。

完整报告保存在 workflow 的 `windows-test-reports` artifact。CI 窗口启动验证不能替代输入法、音频输出及触控板人工验收。运行集成脚本需要 Python 3；普通使用和打包不需要。

| 问题 | 处理 |
| --- | --- |
| 找不到 `java` / `jpackage` | 安装完整 JDK 21+，核对 `JAVA_HOME` 和 PATH，重新打开终端 |
| Maven 下载失败 | 检查 Maven Central 连通性；脚本会校验下载文件，不使用校验失败的缓存 |
| 应用目录无法替换 | 先关闭 Noiseless，确认没有进程占用旧目录 |
| 云端连接失败 | 保持 SSH 隧道运行，并确认服务地址和健康检查正确 |
| 音频无法播放 | 确认是可访问的 HTTP(S) MP3/PCM WAV 地址、音频设备正常；其他格式先转换 |
| 窗口或字体显示异常 | 先使用 Windows 推荐缩放比例；附上系统/JDK 版本和缩放设置用于设备回归 |

构建工具参考：[Maven Windows](https://maven.apache.org/guides/getting-started/windows-prerequisites.html)、[JDK 21 jpackage](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html)。
