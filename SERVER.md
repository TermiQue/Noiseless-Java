# 服务器测试部署

2026-11-16 已部署到 `ubuntu@159.75.1.175`。本轮按用户选择使用模拟模型，不调用真实付费 AI。

## 桌面连接

服务监听服务器回环地址 `127.0.0.1:18088`，通过 SSH 隧道连接：

```sh
ssh -N -L 127.0.0.1:18088:127.0.0.1:18088 ubuntu@159.75.1.175
```

保持这个终端运行，桌面左下角设置 → 账号与云服务 → 登录 / 注册，服务地址填 `http://127.0.0.1:18088`。可以创建自己的测试账号。此 HTTP 连接经 SSH 加密传输；未开放公网明文业务端口。后续直接公网访问可使用仓库已有的域名 HTTPS / Caddy 配置。

```sh
curl http://127.0.0.1:18088/health
```

预期返回 `service=noiseless-java, version=1.1.0`。模型选择为 `服务器 / mock-noiseless`；回复中明确标记模拟模型。

## 服务器目录与进程

| 项目 | 路径 / 名称 |
| --- | --- |
| 发布 JAR / Dockerfile | `/srv/noiseless-java/releases/` |
| 账号、文章、附件、词库、聊天 | `/srv/noiseless-java/data/noiseless.db` |
| 个人 AI 配置加密主密钥 | `/srv/noiseless-java/data/ai-master.key`，权限 0600 |
| 测试环境配置 | `/srv/noiseless-java/testing/testing.env` |
| 后端容器 | `noiseless-java-test`，自动重启，内存 768MB，1 CPU |
| 模拟模型 | `noiseless-mock-ai.service`，监听 `127.0.0.1:18089` |
| 一致性快照 | `/srv/noiseless-java/data/verification-*.db` |
| 测试结果 | `/srv/noiseless-java/testing/*-report.json` |

其他容器 `snowluma`、`astrbot` 保持运行，没有改动它们的配置或数据。

```sh
ssh ubuntu@159.75.1.175 'sudo docker ps --filter name=noiseless-java-test'
ssh ubuntu@159.75.1.175 'sudo docker logs --tail 80 noiseless-java-test'
ssh ubuntu@159.75.1.175 'sudo systemctl status noiseless-mock-ai --no-pager'
```

部署脚本（固定测试环境、模拟模型）：

```sh
./scripts/deploy-ssh.sh ubuntu@159.75.1.175
```

脚本构建、上传 JAR、重建专用镜像、替换专用容器，并执行接口回归；保留数据目录。首次运行依赖远端 Docker 能拉取 `eclipse-temurin:21-jre`。不改变其他服务，不安装宿主机 JDK，不启动对象存储。

## 已执行验证

- [43 项服务器接口检查](docs/verification/server-smoke.json)：账号隔离、文章/附件、分类排序、冲突历史、词库、六种题型生成、流式 Markdown、取消、修改预览、聊天工具改文与冲突保护、个人供应商隔离、导出导入和回收站。
- [6 项重启检查](docs/verification/server-restart.json)：账号、文章、附件、单词本、聊天都跨容器重启保留。
- [6 项备份恢复检查](docs/verification/server-restore.json)：将一致性快照复制到独立目录，带主密钥启动临时恢复实例，在不同回环端口验证相同数据后停止并移除临时容器。
- [macOS 原生界面联调](docs/verification/desktop-ui.json)：云端登录、文章读取、模拟流式聊天与聊天改文保存，以及目录拖动、编辑、侧栏和附件选择。
- 后端 Docker healthcheck 为 healthy。模拟服务为 active。模拟接口没有转发到任何真实模型。

远端回归脚本创建专用合成账号与资料，不读取或使用用户真实资料。测试账号凭据保存在服务器 `testing/smoke-account.json`（0600），不会写入报告或源码。

## 备份和切换真实模型

```sh
ssh ubuntu@159.75.1.175 'sudo docker exec noiseless-java-test java -jar /app/noiseless.jar backup /data/noiseless-new-backup.db'
```

复制这个快照和 `ai-master.key` 到备份位置；恢复操作先停止目标服务，避免旧 WAL/SHM 与快照混用。

切换真实模型时，将正式配置保存在服务器私有环境文件，更新容器 `--env-file`，或在桌面添加自己的供应商。`deploy-ssh.sh` 会恢复模拟测试配置，不能用作保留正式 AI 配置的生产发布脚本。正式配置字段与域名 HTTPS 部署见 README。
