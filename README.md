# admin

基于 Java 17 和 Spring Boot 3.2.1 的后端项目，包含用户管理、实时聊天，以及 Groovy 脚本、Kafka、数据处理等实验性模块。聊天前端使用原生 HTML、CSS 和 JavaScript，由 Spring Boot 直接提供静态资源，无需单独构建前端。

## 主要功能

- **用户管理**：用户列表、单个查询、创建、部分更新及单个／批量删除。
- **实时聊天**：公共聊天室创建与解散、员工搜索、一对一私聊、文字消息、历史分页、消息确认和断线恢复。
- **图片消息**：JPEG、PNG、GIF、WebP 上传，缩略图和原图查看；文件存入 MinIO 私有桶，通过应用接口校验访问权限。
- **登录与会话**：复用 `user` 表账号，使用 Spring Security、BCrypt 和 CSRF；浏览器窗口可分别登录不同账号。
- **运行观测**：聊天审计、Actuator 健康检查和 Micrometer 指标。
- **工具与示例**：Groovy 脚本执行、Kafka 消费、Redis／Redisson、MongoDB 配置、Excel／Word 处理、GeoTools 地理数据处理、JEXL／SpEL 表达式及 ANTLR FlatBuffers Schema 解析。

## 技术栈

| 部分 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.2.1、Spring Cloud 2023.0.1 |
| 数据访问 | MyBatis-Plus 3.5.6、MySQL、Flyway |
| 认证与实时通信 | Spring Security、HTTP Session、WebSocket／STOMP |
| 图片存储 | MinIO Java SDK 8.5.17、ImageIO、TwelveMonkeys WebP |
| 其他集成 | Redis／Redisson、Kafka、MongoDB、Elasticsearch、OpenFeign |
| API 文档 | Springdoc OpenAPI、Knife4j 4.3.0 |
| 测试 | JUnit、Spring Boot Test、H2、Node.js Test Runner、Playwright |

依赖版本以 [pom.xml](pom.xml) 和 [package.json](package.json) 为准。Node.js 只用于前端测试和浏览器验收，启动后端不需要执行 `npm install`。

## 项目结构

```text
admin/
├── pom.xml                         # Maven 依赖与构建配置
├── mvnw / mvnw.cmd                  # Maven Wrapper
├── package.json                    # 前端测试命令
├── playwright.config.cjs            # 浏览器验收配置
├── scripts/run-chat-e2e.cjs         # 启动隔离的聊天应用和 MinIO
├── src/main/
│   ├── antlr4/                     # FlatBuffers 语法定义
│   ├── java/com/hpj/admin/
│   │   ├── AdminApplication.java   # Spring Boot 启动入口
│   │   ├── controller/             # 用户、脚本、Kafka 与聊天接口
│   │   ├── chat/                   # 聊天业务、图片存储与认证
│   │   ├── entity/                 # 数据实体
│   │   ├── mapper/                 # MyBatis Mapper
│   │   ├── service/                # 通用数据服务
│   │   ├── common/                 # 通用配置、异常、校验与序列化
│   │   ├── flatbuffers/            # Schema 解析器及模型
│   │   └── util/                   # 文件、文档、表达式等工具
│   ├── java/db/migration/          # Flyway Java 迁移
│   └── resources/
│       ├── application.yml         # 默认应用配置
│       ├── db/migration/           # Flyway SQL 迁移
│       └── static/chat/            # 聊天页面、登录页和前端脚本
├── src/test/                       # Java、JavaScript、浏览器测试及测试资源
├── docs/                           # 架构与功能说明
└── .github/workflows/chat.yml      # 聊天回归工作流
```

## 本地启动

### 1. 准备环境

- **JDK 17**：将 `JAVA_HOME` 指向 JDK 安装目录。
- **Maven**：可直接使用仓库中的 Wrapper，其版本为 3.6.3；首次执行需要下载 Maven 和依赖。GeoTools 依赖使用 `pom.xml` 中配置的 OSGeo 仓库。
- **MySQL 8.0**：保存用户及聊天数据；CI 使用 MySQL 8.0.36。
- **Redis**：完整应用包含无条件创建的 Redisson 客户端，本地启动应准备 Redis。
- **Elasticsearch 配置**：默认启用 HTTPS 和 PEM 信任证书，启动前需提供有效的本机 CA 文件路径。
- **MinIO**：使用图片功能时准备；文字聊天不要求 MinIO 在线。

Kafka、MongoDB 等用于相应集成或示例。它们不参与聊天消息的持久化与 STOMP 推送，但完整应用仍会加载仓库中的相关配置。

### 2. 创建数据库并覆盖配置

在本地 MySQL 创建空数据库，例如：

```sql
CREATE DATABASE admin_dev CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

默认配置位于 [application.yml](src/main/resources/application.yml)，数据库指向本机 `test` 库。下面以 Windows PowerShell 为例，覆盖为自己的开发环境；先将占位值替换为实际配置：

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://127.0.0.1:3306/admin_dev?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:SPRING_DATASOURCE_USERNAME = '<数据库用户名>'
$env:SPRING_DATASOURCE_PASSWORD = '<数据库密码>'
$env:ELASTICSEARCH_URIS = 'https://localhost:9200'
$env:ELASTICSEARCH_USERNAME = '<Elasticsearch 用户名>'
$env:ELASTICSEARCH_PASSWORD = '<Elasticsearch 密码>'
$env:ELASTICSEARCH_CA_CERT = 'file:C:/path/to/http_ca.crt'
```

Linux／macOS 使用 `export NAME='value'` 设置同名环境变量。

Redis 默认配置为 `127.0.0.1:6379`。注意 [RedissonConfiguration.java](src/main/java/com/hpj/admin/config/RedissonConfiguration.java) 直接调用 `Redisson.create()`，未显式读取 `spring.data.redis`；改用远程 Redis 或启用认证时，还需要调整该 Bean 的连接配置。

Flyway 会在启动时执行 SQL 和 Java 迁移，创建 `user`、聊天会话、参与者、消息、附件及审计表。新环境使用空数据库即可，无需手动执行 `table.sql`。接入已有业务库时，应先核对现有表结构与 Flyway 历史，避免重复建表。

### 3. 启动应用

Windows PowerShell：

```powershell
.\mvnw.cmd spring-boot:run
```

Linux／macOS：

```bash
bash mvnw spring-boot:run
```

也可以在 IDE 中使用 JDK 17 运行 `com.hpj.admin.AdminApplication`。

| 入口 | 默认地址 |
| --- | --- |
| 聊天页面 | [实时聊天](http://localhost:8090/chat/index.html?mode=live) |
| 登录页面 | [登录](http://localhost:8090/chat/login.html) |
| 界面演示 | [演示模式](http://localhost:8090/chat/index.html?mode=demo) |
| Swagger UI | [API 文档](http://localhost:8090/swagger-ui.html) |
| Knife4j | [Knife4j 文档](http://localhost:8090/doc.html) |
| OpenAPI JSON | [接口定义](http://localhost:8090/v3/api-docs) |
| 健康检查 | [Actuator Health](http://127.0.0.1:9091/actuator/health) |
| 指标目录 | [Actuator Metrics](http://127.0.0.1:9091/actuator/metrics) |

`/chat` 和 `/chat/` 会重定向至聊天页面。`mode=live` 使用真实后端；`mode=demo` 仅展示本地演示数据。默认 `auto` 模式只有在接口返回 404 时才允许回退演示，认证失败或网络错误不会自动切换。前端配置见 [config.js](src/main/resources/static/chat/config.js)。

### 4. 准备聊天账号

项目没有预置登录账号，也没有聊天注册接口。登录复用 `user` 表，要求用户名唯一、`status = TRUE`，且密码为 BCrypt 哈希。

可在已加载项目依赖的 Java 环境中生成哈希：

```java
new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
        .encode("替换为自己的登录密码");
```

然后在迁移完成后的开发库插入账号。将 `<BCrypt 哈希>` 替换为上一步的结果，并选择未占用的正整数 ID：

```sql
INSERT INTO `user` (`id`, `username`, `password`, `status`, `sex`)
VALUES (1001, 'alice', '<BCrypt 哈希>', TRUE, NULL);
```

现有用户管理接口不会自动将密码编码为 BCrypt，因此直接通过该接口保存明文密码不能用于默认聊天登录。历史 DES 编码账号可通过 `CHAT_ALLOW_LEGACY_DES_PASSWORDS=true` 临时启用兼容，成功登录后会自动升级为 BCrypt。

### 5. 配置图片存储（可选）

预先在 MinIO 创建私有桶 `chat-attachments`，为应用账号授予该桶对象的读、写、删除权限，然后配置：

```powershell
$env:CHAT_ATTACHMENT_ENDPOINT = 'http://localhost:9000'
$env:CHAT_ATTACHMENT_BUCKET = 'chat-attachments'
$env:CHAT_ATTACHMENT_ACCESS_KEY = '<MinIO Access Key>'
$env:CHAT_ATTACHMENT_SECRET_KEY = '<MinIO Secret Key>'
```

应用不会自动创建桶。图片通过后端上传和下载，不需要向浏览器公开桶或存储凭据。支持 JPEG、PNG、GIF、WebP，单文件上限 10 MiB、4000 万像素；缩略图最长边为 480 像素。存储未配置或不可用时，图片操作返回 503。

## 常用配置

| 环境变量 | 默认值／行为 |
| --- | --- |
| `SERVER_PORT` | `8090`，应用 HTTP 端口 |
| `SPRING_DATASOURCE_URL` | 本机 MySQL 的 `test` 数据库 |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | 覆盖数据库账号与密码 |
| `ELASTICSEARCH_URIS` | `https://localhost:9200` |
| `ELASTICSEARCH_USERNAME` / `ELASTICSEARCH_PASSWORD` | 覆盖 Elasticsearch 凭据 |
| `ELASTICSEARCH_CA_CERT` | 仓库默认是本机绝对路径，需替换为有效的 CA 文件 |
| `CHAT_ENABLED` | `true`；设为 `false` 时关闭聊天 API 和 WebSocket |
| `CHAT_ALLOWED_ORIGINS` | 空，只允许同源 WebSocket；可填逗号分隔的精确 Origin，不支持 `*` |
| `CHAT_ALLOW_LEGACY_DES_PASSWORDS` | `false`，是否允许旧密码登录并升级 |
| `CHAT_PROTECT_LEGACY_ENDPOINTS` | `false`，是否对旧敏感接口要求 `ROLE_ADMIN`；仅在聊天启用时生效 |
| `CHAT_SESSION_COOKIE_SECURE` | `false`；HTTPS 部署可设为 `true` |
| `CHAT_ATTACHMENT_ENDPOINT` | `http://localhost:9000` |
| `CHAT_ATTACHMENT_BUCKET` | `chat-attachments` |
| `CHAT_ATTACHMENT_ACCESS_KEY` / `CHAT_ATTACHMENT_SECRET_KEY` | 配置文件提供本地开发默认凭据，部署时替换 |
| `CHAT_ATTACHMENT_SECURE` | `false`；设为 `true` 时将 HTTP 存储地址升级为 HTTPS |
| `CHAT_ATTACHMENT_UNATTACHED_RETENTION` | `24h`，未关联消息的附件保留期 |
| `CHAT_METRICS_PORT` | `9091`，管理端口仅绑定 `127.0.0.1` |

窗口会话默认 30 分钟超时，保存在当前进程内存中，服务重启后需要重新登录。当前 STOMP 使用内存消息代理；多实例部署需要同时接入共享 Session 仓库和消息代理。`CHAT_ALLOWED_ORIGINS` 只影响 WebSocket，不会开启 REST 跨域。

旧的用户、Groovy、Kafka 等接口默认放行。当前聊天账号只具有 `ROLE_CHAT_USER`，启用 `CHAT_PROTECT_LEGACY_ENDPOINTS` 前需要补齐管理员角色授权。Groovy 接口会执行提交的脚本，Kafka 示例控制器还包含写死的连接地址；对外部署前需要按实际用途调整这些入口。

## 接口概览

聊天 HTTP 接口统一使用 `/api/chat/v1` 前缀：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/csrf-token` | 获取当前会话的 CSRF 凭证 |
| `POST` | `/sessions` | 表单 `username/password` 登录 |
| `GET` / `DELETE` | `/sessions/current` | 查询登录身份／退出 |
| `POST` | `/websocket-tickets` | 申请一次性 WebSocket 握手票据 |
| `GET` | `/conversations` | 获取可见会话及摘要 |
| `POST` | `/rooms` | 创建公共聊天室 |
| `DELETE` | `/rooms/{id}` | 由房主解散聊天室 |
| `GET` | `/users` | 搜索其他启用账号 |
| `POST` | `/direct-conversations` | 创建或复用双方的私聊 |
| `GET` | `/conversations/{id}/messages` | 查询历史，支持 `before`、`after`、`limit` |
| `POST` | `/conversations/{id}/attachments` | 申请附件上传记录 |
| `PUT` | `/attachments/{id}/upload` | 上传原始图片字节 |
| `GET` | `/attachments/{id}/content` | 读取原图 |
| `GET` | `/attachments/{id}/thumbnail` | 读取缩略图 |

前端通过 `X-Chat-Session` 隔离窗口身份；未带该 Header 的客户端仍可使用 Cookie Session。HTTP 写请求和 STOMP CONNECT 需要当前 Session 的 CSRF Token。登录成功后应保存轮换后的 Session ID，并重新获取 Token。

实时消息通过 `/ws/chat?ticket=...` 建立 WebSocket，再使用 STOMP 向 `/app/chat.messages.send` 发送。会话广播订阅 `/topic/chat/conversations/{id}`，个人队列包括 `/user/queue/chat.acks`、`/user/queue/chat.errors` 和 `/user/queue/chat.messages`。发送时携带稳定的 `clientRequestId`，用于失败重试和消息去重。

用户管理使用 `/sys/users` 及 `/sys/users/{id}`，更新方法为 `PATCH`；脚本执行使用 `POST /groovy/script-executions`，Kafka 消费启动使用 `POST /kafka/consumers`。请求与状态码约定见 [RESTful 接口迁移说明](docs/restful-api-migration.md)。

## 测试与构建

以下命令在项目根目录执行。Windows 使用 `.\mvnw.cmd`，Linux／macOS 替换为 `bash mvnw`；已安装 Maven 时也可使用 `mvn`。前端测试可使用与 CI 一致的 Node.js 22。

### 常规回归

```powershell
.\mvnw.cmd '-Dtest=Chat*Test,UserRestControllerTest,ExecutionRestControllerTest' test
npm ci
npm test
```

聊天基础回归使用 H2、随机 HTTP 端口及测试存储实现；用户和执行接口测试使用 MockMvc。专用 MySQL 测试及性能场景默认跳过。仓库还包含依赖外部服务、本地文件或长时间运行的实验测试，运行全量 `test` 前应核对各测试的环境要求。

### MySQL 与性能测试

使用独立测试 MySQL 实例，将命令中的地址和凭据替换为实际值：

```powershell
.\mvnw.cmd '-Dtest=Chat*Test' '-Dchat.mysql.test.url=jdbc:mysql://127.0.0.1:13316/' '-Dchat.mysql.test.user=root' '-Dchat.mysql.test.password=<测试库密码>' '-Dchat.performance=true' test
```

连接地址必须以 `/` 结尾且不指定数据库。测试会创建并删除随机 `chat_test_*` 数据库，因此账号需要建库、删库权限，应仅连接隔离测试实例。性能场景包含 100 个认证 WebSocket 连接和 10000 条历史消息，报告写入 `target/chat-performance-*.json`。

### 浏览器验收

先准备 MinIO 可执行文件，再编译测试应用并安装 Chromium：

```powershell
.\mvnw.cmd -DskipTests test-compile dependency:build-classpath '-Dmdep.outputFile=target/chat-e2e-classpath.txt' '-DincludeScope=test'
npm ci
npx playwright install chromium
$env:MINIO_BIN = 'C:\path\to\minio.exe'
npm run test:e2e
```

Linux／macOS 将 `MINIO_BIN` 指向对应的可执行文件；如使用已安装的 Chrome，可设置 `CHAT_E2E_BROWSER=chrome`。

脚本会启动测试专用的 H2 聊天应用和真实 MinIO，使用随机回环端口、临时凭据及专属对象目录。结束后停止这些进程并清理对象数据，保留 `target/chat-e2e-*` 下的日志、报告、截图和失败 trace。

### 打包

```powershell
.\mvnw.cmd -DskipTests package
java -jar target/admin-0.0.1-SNAPSHOT.jar
```

`-DskipTests` 跳过测试执行，仍会编译测试源码。运行 JAR 时沿用前述数据库和外部服务配置。

[聊天 CI 工作流](.github/workflows/chat.yml) 配置了 Java、MySQL、前端、性能及真实 MinIO 浏览器回归，并上传验证产物。

## 相关文档

- [文字消息链路](docs/chat-text-messaging.md)：持久化、ACK、历史分页和重连。
- [一对一私聊](docs/chat-direct-messaging.md)：账号搜索、唯一会话和访问隔离。
- [图片、浏览器验收与运行观测](docs/chat-completion.md)：附件协议、清理、指标和验收说明。
- [窗口 Session](docs/chat-window-sessions.md)：多窗口登录、握手票据和图片鉴权。
- [RESTful 接口迁移](docs/restful-api-migration.md)：用户及会话接口的路径和方法调整。
- [架构图](docs/architecture.html) 与 [时序图](docs/sequence.html)：在浏览器中打开 HTML 文件查看。

部分文档记录的是对应阶段的交付范围和历史测试结果，当前行为以源码及配置为准。
