# docker/

依赖编排文件在仓库根目录 `docker-compose.yml`（MySQL 8 / Redis 7 / RabbitMQ 3.13-management）。

- 起停与数据卷说明见根 README「快速开始」
- 口令只来自 `.env`（`cp .env.example .env`），Compose 强制要求，仓库无明文默认密码
- schema 初始化：Compose **不挂任何 initdb 脚本**，空数据卷直接起 MySQL；表由应用启动时 Flyway 建（`db/migration`，深化 A6）。存量卷/老卷升级见根 README「存量库迁移」
- 健康检查已配置（`docker compose ps` 等 healthy），跳转/统计链路的依赖即这三件套
