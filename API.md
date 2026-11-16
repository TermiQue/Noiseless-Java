# HTTP API v1

根路径 `/api/v1/`，UTF-8 JSON。除注册、登录、健康检查外，均要求 `Authorization: Bearer <token>`。任何数据库操作都由后端从会话取得用户 ID，客户端不能指定资源归属。请求不接受任意服务器文件路径。

成功：`{"ok":true,"value":...}`。错误：`{"ok":false,"code":"CONFLICT","error":"..."}`。401 未登录，403 禁止，404 不存在（包含其他用户的资源），409 版本冲突，413 请求过大，429 限流，503 AI 未配置/服务器繁忙。附件下载成功直接返回字节。

文章使用原 `noiseless-1` / `bilingual-reader-1` JSON。`file` 为新服务生成的 UUID，`revision` 为递增整数，不沿用旧 Electron 的 SHA-256 字符串。客户端必须保存读取到的 revision 并在写入/删除时原样传回。未提供 revision 不等于强制覆盖。备份的 `data` 为内容，文章读写返回的 `article` 为内容。

## 账号

| 方法 | 路径 | 请求 / 返回 |
| --- | --- | --- |
| POST | `/auth/register` | `{email,password,name}` → `{token,expires,user}`；密码 10–200 字符 |
| POST | `/auth/login` | `{email,password}` → `{token,expires,user}` |
| GET | `/auth/me` | `{id,email,name,created}` |
| POST | `/auth/logout` | `{}`；撤销本次令牌 |
| POST | `/auth/password` | `{oldPassword,password}`；撤销此账号所有令牌 |
| GET | `/health`（无 `/api/v1` 前缀） | `{ok:true,service:"noiseless-java"}` |

注册与登录限制来源地址请求数（每分钟分别 5/20 次），同时最多处理 4 个密码请求。普通已认证请求每账号每分钟 300 次。不信任来自公网的转发 IP 头；Compose 反代下入口限流会按代理连接来源聚合。

## 文章与分类

| 方法 | 路径 | 请求 / 返回 |
| --- | --- | --- |
| GET | `/library` | 原契约 Catalog：`articles,library,directory,warnings` |
| GET | `/articles` | `[{id,data,revision,updated},...]` |
| POST | `/articles` | 文章 JSON 或 `{article}` → `{file,article,revision}` |
| GET | `/articles/{id}` | `{file,article,revision}` |
| GET | `/articles?file={id}` | 同上，兼容原契约读取入口 |
| PUT | `/articles/{id}` | `{article,revision}` → 更新后的文章 |
| POST | `/articles/body` | `{file,paragraphs,revision}` → 更新后的文章 |
| DELETE | `/articles/{id}` | `{revision}`；软删除，清理分类和排序引用 |
| GET | `/articles/{id}/history` | `[{revision,data,created}]`；data 为历史 JSON 字符串 |
| GET | `/trash` | 当前账号软删除的文章快照 |
| POST | `/trash/{id}/restore` | `{}`；恢复文章，revision 继续递增，防止旧客户端覆盖 |
| POST | `/library/actions` | 下列操作 → 更新后的 Library |

LibraryAction：

```json
{"action":"create-folder","name":"2026 CET-4"}
{"action":"rename-folder","id":"folder-id","name":"新名称"}
{"action":"remove-folder","id":"folder-id"}
{"action":"move-folder","id":"folder-id","before":"other-folder-id"}
{"action":"update-article","file":"article-id","name":"显示名称","folder":"folder-id","tags":["green"]}
{"action":"move-article","file":"article-id","folder":"folder-id","after":"other-article-id"}
{"action":"delete-article","file":"article-id","revision":2}
```

`before`/`after` 互斥。文章排序目标须在同一文件夹；`folder:""` 表示根目录。移除文件夹把文章移回根目录。分类操作在数据库事务内串行执行，文章写入使用乐观锁。颜色为 red/orange/yellow/green/blue/purple/gray。

## 单词本与词库

| 方法 | 路径 | 请求 / 返回 |
| --- | --- | --- |
| GET | `/vocabulary` | `{version:1,words:[{id,word,addedAt,sources}]}` |
| POST | `/vocabulary/add` | `{file,word}`；服务端取来源文章中的释义快照，来源去重 |
| POST | `/vocabulary/remove` | `{id}`；仅移除收藏 |
| GET | `/lexicon?word=reading` | `{word,entry,source}`；source 为 user/offline/null |
| GET | `/lexicon/user` | 当前账号用户词库文档及 revision |
| PUT | `/lexicon` | `{word,entry,revision}`；新词 revision=0，更新需当前版本 |

标准词条包含 `headword,phonetics:{uk,us},senses:[{pos,translations}],tags,collocations,morphemes,relatedWords`。AI 语境查词走任务接口。用户词库覆盖基础词库，不写入文章 JSON；收藏移除不删词库。

## 附件

| 方法 | 路径 | 请求 / 返回 |
| --- | --- | --- |
| POST | `/files` | `{name,mime,data}`，data 为不带 data-URL 前缀的 Base64 → `{id,name,mime,size}` |
| GET | `/files` | 当前账号附件元信息（不包含字节） |
| GET | `/files/{id}` | 认证后下载原始字节，Content-Disposition 为 attachment |
| DELETE | `/files/{id}` | `{}`；清理附件 |

单文件 10,000,000 字节、账号合计 100,000,000 字节。上传 JSON 上限 15MB；正文编辑上限 2MB。已上传附件不会随整理窗口关闭而删除，可通过附件管理清理。文章整理最多引用 10 个附件。

## AI 与聊天

`GET /ai/settings` 返回 `{configured,protocol,models}`，不包含密钥。模型由运营方配置的允许列表决定，不向客户端开放供应商地址写入。

`POST /chats` 接收 `{file,revision}`，返回保存的 `{id,data,revision,updated}`。data 包含固定 `snapshot` 和 `messages`；新建会话不会调用 AI。`GET /chats` 列出自己的会话，`GET /chats/{id}` 读取，`DELETE /chats/{id}` 接收 `{revision}` 删除会话。

`POST /ai/jobs` 创建任务，立即返回 `{id,data,revision,updated}`。请求示例：

```json
{"kind":"chat","sessionId":"chat-id","text":"分析第一段","quote":"可选引用","model":"allowed-model"}
{"kind":"chat","sessionId":"chat-id","retry":true,"model":"same-model"}
{"kind":"generate","type":"reading","text":"原文和参考答案","attachmentIds":["file-id"]}
{"kind":"resolve","file":"article-id","word":"book","context":"A good book opens a world."}
{"kind":"rewrite","file":"article-id","text":"把第一段中文译文改为……"}
```

`retry:true` 只重试服务端已保存的上一轮问题，不接受客户端替换旧问题。失败/取消的轮次不写入正式聊天历史，可重新提交原请求。正文和引用各最多 8000 字符。聊天上下文来自快照，客户端不能提供 system 或历史角色数组。

`GET /ai/jobs/{id}` 读取状态。data.status 为 queued/running/complete/failed/cancelled；生成中可有 `output` 和 `outputChars`，来自实际供应商 SSE，约每 250ms 合并一次保存，桌面每 900ms 拉取。支持 Chat Completions、Responses、Messages 和 Gemini 的原生流事件；没有正常结束标记的断流不会视为成功。兼容返回完整 JSON 的上游。

完成结果 data.result：

- chat：`{text,sessionId}`，服务端保存这一轮历史。
- generate：`{article}` 草稿，不自动入库；用户确认后通过 POST /articles 保存。
- rewrite：`{article,file,revision}` 草稿，不自动改文；确认后通过 PUT /articles/{file} 保存，revision 仍需匹配。
- resolve：`{translation,entry}`，校验词条和真实文章语境后写入用户词库和版本缓存。

`POST /ai/jobs/{id}/cancel` 接收 `{}`，取消 Future 并关闭正在读取的上游流；保留已接收的部分输出，未完成结果不入库。文章整理、修改和查词还会验证完整 JSON/文章格式。

## 导入导出

`GET /export` 返回：

```json
{"format":"noiseless-cloud-backup-1","articles":[{"id":"old-id","data":{}}],"library":{"folders":[],"articles":{},"order":[]},"vocabulary":[],"lexicon":[]}
```

实际 data 为完整文章。`POST /import` 接受同结构，事务内创建新文章 UUID、映射分类和收藏来源，返回 `{imported,files:{旧ID:新ID}}`。不覆盖已有文章，任一步验证失败回滚整批。不包含附件、账号密码和聊天；完整服务器快照使用 backup CLI。

基础技术参考：[JDK HttpServer](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html)、[SQLite WAL](https://www.sqlite.org/wal.html)、[OpenAI Chat API](https://developers.openai.com/api/reference/resources/chat)、[Messages streaming](https://platform.claude.com/docs/en/build-with-claude/streaming)、[Gemini generation](https://ai.google.dev/api/generate-content)。

## 个人供应商与聊天工具

运营方配置和每用户配置可同时存在。GET `/ai/settings` 额外返回 `profiles,defaultProfileId,selections`；selections 为 `{profileId,model,label,searchSupported}`。`profileId:"server"` 表示运营方连接。AI 任务可以传 `profileId`，任务开始后固定连接，不受随后修改默认项影响。

| 方法 | 路径 | 请求 / 返回 |
| --- | --- | --- |
| GET | `/ai/profiles` | 个人配置卡片，包含 hasKey，不含密钥或密文 |
| PUT | `/ai/profiles` | `{id?,name,provider?,baseUrl,protocol,apiKey?,models:["id"],manualModel,makeDefault?}` |
| DELETE | `/ai/profiles/{id}` | `{}`；只能删除自己的配置，删除默认项回退 server |
| POST | `/ai/models` | `{id?,baseUrl,protocol,apiKey?}` → 接口返回的模型 ID 列表 |
| POST | `/ai/default` | `{profileId}` → settings |

`manualModel:false` 的保存会实际验证模型列表；手动 ID 不伪装成已验证。空 apiKey 只在原地址、原协议复用已有密钥。个人供应商密钥用 AES-256-GCM 加密，主密钥保存在数据目录 `ai-master.key`（0600），需和数据库一起备份。云端自定义主机须由运营方加入 `NOISELESS_AI_ALLOWED_HOSTS`；桌面本地实例允许回环 AI 服务。

聊天任务的 `webSearch:true` 仅允许 DeepSeek 官方接口与 DeepSeek 模型，调用官方 `/v1/tools/search` 后再生成回答；任务可有 `searchStatus,sources`，结果和历史消息可有 sources。搜索失败会报错，取消会中止请求。sources 为经过 URL 协议校验的来源列表。

明确修改请求会向模型提供 `update_article`，参数兼容原项目：`title?、subtitle?、paragraphs:[{index:1,en?,zh?}]?、questions:[{number,...需修改字段}]?、dictionary:{word:{...词条字段}}?`。只允许一次合并修改，段落从 1 开始，题号须已存在，整篇文章须重新校验。聊天版本与云端版本不一致时拒绝保存；不完整流、超长参数、不支持工具或无明确修改意图均不写入。成功后 result.articleEdit 为 `{file,revision,fields}`，聊天快照同步更新；已经提交保存的结果不会被后到的取消请求伪装成未执行。
