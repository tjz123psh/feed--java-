# Feed-Java

基于 **Java 21 + Spring Boot + Kafka + Redis + MySQL** 构建的高性能短视频 Feed 系统，包含完整的前后端实现。

---

## 🌟 功能特性

- **多维 Feed 流**：关注流（Timeline）、热门推荐、最新发布与标签流。
- **互动与媒体**：视频分块上传、HTTP 206 流式播放、评论、点赞与关注。
- **实时通知**：基于 SSE（Server-Sent Events）实现点赞与关注消息的即时推流。
- **高并发设计**：多级缓存（Caffeine + Redis）、本地消息表 (Outbox) + Kafka 异步削峰、接口限流防护。

---

## 🏗️ 系统架构

系统采用读写分离与异步解耦设计，分为两大服务进程及支撑中间件：

- **API 核心服务 (`:8081`)**：提供 RESTful 接口、JWT 鉴权与限流拦截，负责 SSE 连接管理及多级缓存读端聚合。
- **Worker 消费服务**：监听 Kafka 业务 Topic，异步处理点赞、评论、关注落库，完成计数聚合与热度值计算。
- **存储与中间件**：
  - **MySQL 8**：核心业务数据持久化与本地事务消息表 (Outbox)。
  - **Redis 7**：分布式多级缓存、分布式锁与接口限流。
  - **Kafka**：核心写事件削峰填谷与异步解耦。

---

## 🚀 快速开始

### 1. 启动基础设施
```bash
# 启动 MySQL、Redis、Kafka
cd deploy && docker compose up -d && cd ..

# 初始化 Kafka Topic（中间件就绪后执行）
bash deploy/create-topics.sh
```

### 2. 启动后端服务
分别在两个终端中启动核心 API 与 Worker 进程：
```bash
# 终端 1：启动 API 核心服务 (:8081)
./mvnw spring-boot:run

# 终端 2：启动 Worker 消费服务
./mvnw spring-boot:run -Dmain.class=io.github.p_feed.WorkerApplication
```

### 3. 启动前端
```bash
cd frontend
npm install
npm run dev
# 浏览器访问 http://127.0.0.1:5173
```

---

## 📚 详细文档

- [系统详细设计与阶段演进](docs/README.md)
- [Redis 缓存设计与键分布](docs/cache-map.md)
