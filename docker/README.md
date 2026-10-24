# docker/

依赖编排文件在仓库根目录 `docker-compose.yml`（MySQL 8 / Redis 7 / RabbitMQ 3.13-management）。

- 起停与数据卷说明见根 README「快速开始」
- 口令只来自 `.env`（`cp .env.example .env`），Compose 强制要求，仓库无明文默认密码
- schema 初始化：新数据卷由挂载的 `src/main/resources/db/schema.sql` 自动建表；存量卷用 `bash scripts/migrate.sh` 幂等补表
- 健康检查已配置（`docker compose ps` 等 healthy），跳转/统计链路的依赖即这三件套
