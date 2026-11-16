# Noiseless Java

Java 21 原生桌面端和可独立部署的单机后端。桌面使用 Swing / Java2D，不依赖 Electron、Node、JavaScript 或 WebView；服务端使用 JDK HttpServer、HttpClient、JDBC 和 SQLite。Jackson 处理 JSON，CommonMark 处理聊天 Markdown。SQLite JDBC 包含各平台本地库，因此“原生 Java”指客户端和业务代码均用 Java 实现，并非零第三方依赖。

界面参照原 `apps/desktop`：44px 图标栏、202px 可调整的文章目录、白色双语阅读区、右侧工具标签、浅灰顶栏、绿色强调色，沿用正文 18px / 译文 14px 和无衬线字体。本仓库独立维护 Java 版本，包含桌面端、后端、部署脚本与测试。

## 构建与启动

开发需要 JDK 21+、curl、tar。首次构建下载 Maven 和固定版本依赖；后续可使用缓存。macOS/Linux：

```sh
git clone https://github.com/TermiQue/Noiseless-Java.git
cd Noiseless-Java
./mvnw verify
java -jar target/noiseless.jar
```

首次启动自动导入随包示例和分类，之后不会重新导入已删除的示例。默认打开本地模式，使用 `~/.noiseless-java/local/` 内的 SQLite，离线可阅读、管理文章和查词。客户端在随机回环端口启动本地服务，为本地模式创建独立随机密码账号；凭据文件在 POSIX 系统中以 `0600` 保存，在 Windows NTFS 中使用当前用户专属 ACL。不读取旧 Electron 的密钥或修改旧资料。

只启动后端：

```sh
NOISELESS_DATA_DIR=./data java -jar target/noiseless.jar server
```

默认监听 `127.0.0.1:8080`，健康检查为 `GET /health`。服务器无需图形环境。桌面连接云端：左下角设置 → 登录 / 注册云端账号，或：

```sh
java -jar target/noiseless.jar desktop --cloud https://reader.example.com
```

云端会话令牌仅保存在客户端内存。切换账号会关闭旧账号的弹窗、取消当前客户端的 AI 任务，清理阅读视图，避免串用账号资料。本地模式与云端模式使用独立资料库；不会自动覆盖或双向合并。

Windows 10/11（x64）使用 JDK 21+，无需预装 Maven：

```powershell
.\mvnw.cmd verify
java --enable-native-access=ALL-UNNAMED -jar .\target\noiseless.jar
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\package-desktop.ps1 -SkipBuild
```

生成 `target\native\Noiseless\Noiseless.exe` 和 `target\native\Noiseless-windows.zip`。解压完整 ZIP 后双击 EXE；包内自带 Java 运行时，使用者不需要安装 JDK。Windows 构建、连接服务器、数据目录和故障排查见 [WINDOWS.md](WINDOWS.md)。

原生应用打包（使用当前系统 JDK 的 jpackage）：

```sh
./scripts/package-desktop.sh
```

产物在 `target/native/`，包含运行时，目标机无需安装 JDK。当前机器生成 macOS `.app`；Windows/Linux 需在对应系统构建。未配置发行签名、公证或自动更新。

## 已实现功能

- 阅读、听力、选词填空、长篇匹配、翻译、写作六种题型；原文/译文开关、选项或文本作答、参考答案及来源、短语、文章词典覆盖。作答只在本次窗口中保留。
- 文章 JSON 导入、目录整体迁移、全文编辑、版本冲突保护、最近 30 个编辑历史、软删除和回收站恢复。编辑保留扩展字段；冲突时不清空编辑草稿。
- 文件夹、重命名、颜色标签、目录顺序 API，原生右键菜单管理分类；左右分栏调整、目录收起、文章前进后退。
- 按文章保留工具标签、聊天草稿、作答和阅读视图。查看新版本时重建该文章视图；旧版聊天内容保存在服务器，可从阅读设置 → 历史聊天重新打开。
- 单词本按首次加入顺序保存，词形忽略大小写和弯引号差异，合并来源。删除来源文章不删除收藏，并标注不可用来源。
- 随包附带原项目 770,238 条离线词库，首次查询校验 SHA-256 并解压，后续只读查询。支持变形索引、搭配、词根词缀；用户词库按账号隔离并优先于基础库。
- 邮箱/密码注册、登录、查看账号、退出、修改密码。PBKDF2-HMAC-SHA256（600,000 次、随机盐）保存密码；会话为随机 256 位令牌，数据库只存 SHA-256，7 天有效。修改密码撤销所有会话。
- 云端 AI 多轮聊天、选区引用、实际流式输出、停止和重试；聊天上下文绑定文章快照。生成文章和按明确要求修改文章都先展示可编辑 JSON 草稿，再由用户确认保存，接受原有文章校验和版本冲突检查。
- AI 语境查词校验上下文属于文章；标准词条和语境义验证后写入用户云词库，缓存绑定文章版本和上下文。单词本与词库独立。
- 图片/PDF/文档/文本附件上传、下载、清理；文件内容保存在 SQLite BLOB，不需要对象存储。单个附件 10MB、每账号附件合计 100MB，一次整理最多 10 个附件。不同协议可接受的文件类型见下表。
- 云端文章/分类/单词本/用户词库导出和原子导入；服务器完整数据库一致性备份。

## 单服务器部署

后端是一个 JVM 进程，业务数据、附件和任务结果都在本机 SQLite；不需要 MySQL/PostgreSQL、Redis、消息队列、S3 或 OSS。Compose 中的 Caddy 也部署在同一台服务器，只负责 HTTPS。

```text
Java 桌面客户端 ── HTTPS ── Caddy :443 ── Java :8080
                                              │
                                              ├── /data/noiseless.db（账号、文章、附件、词库、聊天）
                                              ├── /data/lexicon/（可重建的基础词库缓存）
                                              └── AI 供应商 API / 同机推理服务
```

准备指向该服务器的域名，开放 80/443，安装 Docker 和 Compose：

```sh
cd deploy
cp .env.example .env
# 编辑 .env，填写域名和可选 AI 配置
chmod 600 .env
docker compose up -d --build
```

Caddy 自动申请和续期 HTTPS 证书。业务端口只绑定宿主机回环地址。数据保存在 `noiseless-data` Docker 卷中；重建镜像不会删除数据。AI 未配置时，文章/词库/账号服务可独立正常运行。无需另外购买数据库或文件服务。

| 环境变量 | 默认 / 用途 |
| --- | --- |
| `NOISELESS_DATA_DIR` | 本机运行 `data`；容器 `/data` |
| `NOISELESS_HOST` | 本机 `127.0.0.1`；容器 `0.0.0.0` |
| `NOISELESS_PORT` | `8080` |
| `NOISELESS_REGISTRATION` | `true`；设为 `false` 关闭新注册，不影响已有账号登录 |
| `NOISELESS_DOMAIN` | Compose HTTPS 域名，必须填写 |
| `NOISELESS_AI_BASE_URL` | `https://api.openai.com/v1`；填写协议根路径，非具体调用端点 |
| `NOISELESS_AI_PROTOCOL` | `chat` / `responses` / `anthropic` / `gemini` |
| `NOISELESS_AI_API_KEY` | 仅服务端读取，不在 HTTP 返回或桌面配置中回传 |
| `NOISELESS_AI_MODELS` | 逗号分隔的模型 ID 允许列表；桌面可临时切换，首项为默认 |

同机推理服务可使用 `http://127.0.0.1:端口/...`；Docker 中的回环地址指容器自身，若推理服务在宿主机，请使用同机 HTTPS 反代地址，或直接在宿主机运行 Java 服务。用户无法通过 API 改写供应商地址或绕过模型允许列表。

| 协议 | Base URL 示例 | 附件能力 |
| --- | --- | --- |
| `chat` | `https://api.deepseek.com` 或兼容接口 `/v1` | 文本、模型支持的 PNG/JPEG/WebP 图片 |
| `responses` | `https://api.openai.com/v1` | 文本、图片、模型支持的 PDF / Word 等文件 |
| `anthropic` | `https://api.anthropic.com/v1` | 文本、模型支持的图片和 PDF |
| `gemini` | `https://generativelanguage.googleapis.com/v1beta` | 文本、模型支持的图片和 PDF |

以上是传输协议适配，实际文件类型、上下文容量和模型 ID 以供应商为准。无可用 AI 时不会偷偷调用其他供应商。服务不包含模型权重；可连接云 API 或同机推理服务。调用供应商可能产生费用。

## 迁移现有资料

设置 → 导入资料 / 旧版迁移：

1. 选择旧 Electron 的 `library/` 目录，或原项目 `demo/`。导入 `.article.json`、`.noiseless-library.json` 和 `.noiseless-vocabulary.json`；同目录有 `user-lexicon.sqlite` 时一并读取标准词条。
2. 文章路径重新映射为云端 UUID，分类、排序、标签、收藏来源同步映射。不导入旧回收站/备份隐藏目录，不修改源目录。
3. 在本地模式迁移后，可登录云端，使用“本地资料上传云端”将本地资料追加到云端。也可把导出的 `noiseless-export.json` 通过同一导入入口恢复。

导入是原子追加：任何格式错误会回滚整个批次，已有文章不被覆盖。重复导入会产生新的文章副本。备份包与目录迁移不携带音频等二进制附件；这类文件可通过附件 API 另外上传。旧 Electron AI 密钥不迁移，服务器使用自己的供应商配置。旧版按位置缓存的 AI 语境义不迁移，首次使用时重新生成。

## 备份与恢复

完整备份使用 SQLite `VACUUM INTO`，包含已经提交到 WAL 的内容；无需停服或复制多个活动中的数据库文件。

本机：

```sh
NOISELESS_DATA_DIR=./data java -jar target/noiseless.jar backup /absolute/path/noiseless-backup.db
```

Compose：

```sh
./scripts/backup.sh /absolute/path/backups
```

数据库快照包含账号密码哈希、会话、全部文章、附件、聊天、用户词库；基础词库随 JAR 附带，可自动重建。JSON 导出只包含当前账号文章、分类、单词本、用户词库，不包含附件、密码、会话和聊天；它适合跨账号迁移，不能替代服务器备份。

恢复：停止服务，把目标数据目录中的 `noiseless.db`、`noiseless.db-wal`、`noiseless.db-shm` 先移到另一个恢复前备份目录，将快照复制为 `noiseless.db`，确保运行用户可写，然后启动服务。Docker 使用 `docker compose stop app` 后在数据卷上做同样的替换；不要把旧的 WAL/SHM 与快照混用。运营方 AI 密钥另存于部署配置中，需要单独保存。个人供应商密钥的加密主密钥 `ai-master.key` 也必须与数据库快照一起备份和恢复；备份脚本将它保存为同名 `.db.key` 文件，恢复时改名为 `ai-master.key`。

## 边界与验证状态

- 这是原生桌面端和后端的独立实现，不包含新的浏览器/H5、小程序或管理后台（原项目对应目录也只有预留）。
- 整体 UI 沿用原版布局与配色；原生字体渲染、标题栏和文件选择器会受操作系统影响。未做到原 Electron 的逐像素复刻。
- 已提供文章/文件夹内部拖拽排序、跨文件夹拖动、标题悬停滚动、标签关闭、新标签页工具选择、右栏收起/铺满/Escape 还原、段落表单编辑、单词本整页视图，以及按文章保留状态。Mac 手势使用 JDK 原生 Swipe/Phase 监听，并保留 Shift+横向滚轮回退；物理触控板与 Windows/Linux 交互仍需设备回归。
- 正文编辑和 AI 整理/修改预览使用段落与答案表单，保留未编辑的扩展字段。聊天用 Swing 文本组件渲染 Markdown，支持列表、代码和表格；不执行 HTML/JavaScript，也不加载模型返回的远程图片。
- 同时支持运营方模型和每用户多供应商配置；个人密钥使用 AES-GCM 加密，主密钥为数据目录中的 `ai-master.key`。可获取接口模型、手动填写未验证 ID、设默认和临时切换。云端自定义地址须加入 `NOISELESS_AI_ALLOWED_HOSTS`，避免用户请求任意内网地址。已迁移 DeepSeek 官方联网搜索开关与来源链接，以及聊天 update_article 工具。工具仅对明确修改请求启用，按当前聊天版本原子保存并留历史；独立的 AI 修改入口仍使用草稿确认。
- macOS 下 HEIC/HEIF 通过系统 sips 转为 JPEG；其他系统需先转换。已提供原生音频播放/暂停/进度控制，macOS 通过系统 AudioConverter 解码 MP3/AAC 等；Windows/Linux 随包提供 Java MP3 解码，支持 MP3 与 PCM WAV；AAC/M4A 等其他格式需先转成 MP3/WAV。原资料相对音频地址仍需迁移为可访问的 HTTP(S) 地址。
- 邮箱是登录标识，未接入邮件验证、邮件找回密码、短信或付费额度系统。关闭公开注册可用于自用部署。
- 每账号最多并行 2 个 AI 任务、30 次 AI 启动/分钟；单聊天同一时间只回答一轮，最多保留 40 轮。服务器重启会将未完成任务标记失败；已完成聊天和资料保留。
- 当前测试覆盖文章格式、所有种子题型、多账号隔离、密码/会话撤销、并发版本冲突、原子回滚、删除恢复、分类、收藏、附件、备份、原词库、旧版迁移、AI 草稿确认、取消、语境缓存、四种协议及流中断校验。AI 使用本机模拟服务测试，没有调用真实付费模型。
- 已在本机 JDK 25 上以 Java 21 目标编译并运行测试，生成 JAR 和 macOS app-image。已在远端 Ubuntu x86_64 / Java 21 容器实机部署，并通过 43 项接口检查、6 项重启检查、6 项备份恢复检查。已完成解锁后的 macOS 原生界面与远端模拟 AI 联调，包含账号、云端文章、流式聊天、聊天改文、正文编辑、目录拖动和文件选择。物理触控板、输入法组合候选、音频设备及其他平台原生窗口尚需人工回归。

当前服务器连接和运维方式见 [SERVER.md](SERVER.md)，交互对照及测试范围见 [UI_PARITY.md](UI_PARITY.md)。

接口说明见 [API.md](API.md)。离线词库来源和许可证见 [THIRD_PARTY.md](THIRD_PARTY.md)。

Windows CI 自动运行完整 Java 测试、PowerShell/Maven 引导、便携包打包、随包运行时后端接口与重启检查，以及原生 EXE 窗口启动检查。CI 产物下载与人工验收范围见 [WINDOWS.md](WINDOWS.md)。
