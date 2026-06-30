# 邻里团购社区优惠平台

这是基于springboot搭建的社区团购优惠平台。主要功能有社区商户、自提点、团长种草、限时团购券和关注团长 Feed 流，技术栈仍保留 Spring Boot、MyBatis-Plus、Redis、Redisson、Nginx 静态 Vue，方便继续围绕 Redis 高并发和缓存方案讲项目。

## 主要改造

- 登录认证：手机号验证码/密码登录后使用 Sa-Token 签发 token，前端通过 `satoken` 请求头携带。
- 首页视觉：改为社区团购首页，包含自提点入口、场景品类、团长动态和团购券引导。
- 数据内容：SQL 种子数据改成社区团购商户、团长动态、限时团购券和本地图片素材。
- 图片资源：社区团购图片位于 `nginx-1.18.0/html/buyTogether/imgs/community`。
- Redis 能力：保留缓存穿透、互斥锁、逻辑过期、GEO 附近商户、Stream 异步下单、Redisson 一人一单。

## 环境准备

建议版本：

- JDK 8
- Maven 3.6+
- MySQL 5.7 或 8.x
- Redis 6+

数据库默认连接配置在 `buytogether/src/main/resources/application.yml`，可以用环境变量覆盖：

```powershell
$env:MYSQL_URL="jdbc:mysql://localhost:3306/dianping?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="your_mysql_password"
$env:REDIS_HOST="localhost"
$env:REDIS_PORT="6379"
$env:REDIS_PASSWORD=""
$env:REDIS_DATABASE="10"
```

## 初始化数据

创建数据库并导入 SQL：

```sql
CREATE DATABASE IF NOT EXISTS dianping DEFAULT CHARACTER SET utf8mb4;
USE buyTogether;
SOURCE buyTogether/src/main/resources/db/buyTogether.sql;
```

也可以导入后端资源目录中的同版 SQL：

```text
buyTogether/src/main/resources/db/buyTogether.sql
```

## 启动后端

```powershell
cd buyTogether
mvn spring-boot:run
```

后端默认端口：`http://localhost:8081`。

## 启动前端

前端是静态 Vue 页面，仓库只保留页面代码和图片资源。可以用任意静态服务器托管 `nginx-1.18.0/html/buyTogether`，例如使用本机已安装的 Nginx 指向该目录。

浏览器访问：

```text
http://localhost
```

## 登录验证

1. 打开 `http://localhost/login.html`。
2. 输入手机号，点击获取验证码。
3. 后端日志会打印验证码。
4. 输入验证码登录，前端会把 Sa-Token token 保存到 `localStorage.token`。
5. 访问个人页、发布动态、点赞、关注、抢券时会通过 `satoken` 请求头校验登录态。

## 数据预热

预热工具在：

```text
buyTogether/src/test/java/com/buyTogether/DataPreheatTests.java
```

它默认带有 `@Disabled`，避免普通测试依赖本地 MySQL/Redis。需要预热时临时去掉类上的 `@Disabled`，再运行对应方法：

- `preheatShopLogicalExpireCache`：预热商户逻辑过期缓存。
- `preheatShopGeoIndex`：重建附近自提点 GEO 索引。
- `preheatSeckillVoucherStock`：预热限时团购券 Redis 库存。
- `initVoucherOrderStreamGroup`：初始化 `stream.orders` 消费组 `g1`。

## 测试

```powershell
cd buyTogether
mvn test
```

当前自动测试会覆盖秒杀下单策略；需要 Redis/MySQL 的预热类保持手动运行。

## 重点接口

- `POST /user/code`：发送验证码。
- `POST /user/login`：Sa-Token 登录。
- `GET /shop-type/list`：社区团购品类。
- `GET /shop/of/type`：按品类查询自提点。
- `GET /shop/{id}`：自提点详情。
- `GET /voucher/list/{shopId}`：团购券列表。
- `POST /voucher-order/seckill/{id}`：限时团购券抢购。
- `GET /blog/hot`：热门团长动态。
- `GET /blog/of/follow`：关注团长 Feed 流。

更多优化原因见 `docs/optimization-notes.md`。

