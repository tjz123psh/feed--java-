> **定位**：选修课程。主线（阶段 2-9）不要求先学 Go——各阶段文档已直接给出行为规格。
> 只有当你想深入原版源码细节时再来读它。

# 阶段 1 · 给 Java 同学的 Go 速读课

> **目标**：学会"读懂"这个仓库里的 Go。不要求会写 Go。
> **预计用时**：1 天。
> **完成标准**：能逐行口述指定给你的那段缓存代码（第 8 节有说明）。

本课共 5 组、约 20 个语言点。每个点都遵循同一格式：代码长什么样 → 含义 → 对应 Java 的什么 →
在本项目中的出处。读的时候请同步打开对应文件，看到真实上下文比看摘录有效得多。

## 1. 文件与包

### 1.1 package 与 import

```go
package account

import (
    rediscache "feedsystem_video_go/internal/middleware/redis"
)
```

- 包名由目录决定，一个目录一个包。`internal/` 是编译期强制的私有目录：模块外部无法 import。
- Go 没有 `public` / `private` 关键字。**首字母大写 = 对外可见**，小写 = 包内私有。
  这与 Java 的大小写习惯相反。
- import 路径前可以写别名（如 `rediscache`），用于避免与依赖库重名。

出处：任意 `backend/internal/*/*.go` 的文件头。

### 1.2 没有 class

Go 用 `struct`（数据）+ 方法（行为）拼出"类"：

```go
func (s *SocialService) Follow(ctx context.Context, social *Social) error { ... }
```

- 函数名前面那段 `(s *SocialService)` 叫接收者，等价于 Java 的 `this`。
- 接收者写 `*SocialService` 表示方法可修改字段并避免拷贝；写值（无星号）通常表示只读。
  从 Java 来的你只要记住：**翻译时一律当成实例方法**，指针对你无意义。

出处：`social/service.go`。

## 2. 类型与结构体

### 2.1 struct tag：反射元数据（全课最重要的一节）

```go
type Account struct {
    ID       uint   `gorm:"primaryKey" json:"id"`
    Username string `gorm:"unique" json:"username"`
    Password string `json:"-"`
    Bio      string `gorm:"type:varchar(255)" json:"bio,omitempty"`
}
```

- 字段后的反引号字符串叫 tag。`gorm:` 管建表规则，`json:` 管序列化字段名。
- `json:"-"` 表示该字段永远不出现在 JSON 里（密码正是这样防外泄的）。
- `omitempty` 表示值为空时省略整个字段。
- **重写每个 DTO 的第一步，都是照 tag 抄出 JSON 字段名**：`@JsonProperty("avatar_url")` +
  按需 `@JsonInclude(NON_EMPTY)`。
- 有些结构体不打 json tag（如 `social/entity.go` 的 `Social`）。此时 Go 用字段原名序列化，
  输出 `"VloggerID"` 这种键名。碰到响应键名风格突变，先查 tag。

出处：`backend/internal/account/entity.go`。

### 2.2 零值与 nil

- 变量声明即获得零值：数值为 `0`，字符串为 `""`，切片 / map / 指针 / 接口为 `nil`。
- 常见模式（`like_handler.go`，同款还见 `comment_handler.go`、`video_handler.go`、`message/handler.go`）：

```go
if videos == nil {
    videos = []Video{}
}
```

  目的是让 JSON 输出 `[]` 而不是 `null`。Java 版同样要保证空列表初始化为空集合。
- `uint` 是 64 位无符号整数，用 Java 的 `long` 承载即可。
- `*uint`（指针）用来区分"字段没传"和"传了 0"。Feed 的游标字段就是这样设计的（见《06》）。

出处：`backend/internal/video/like_handler.go`、`video_handler.go`。

### 2.3 切片与 map

```go
var tags []string                 // 长度为 0
tags = append(tags, "java")       // 追加
for i, v := range videos { ... }  // 遍历，_ 表示丢弃某个值
likedMap := make(map[uint]bool)   // 相当于 new HashMap<>()
```

没有 Stream API。过滤、收集就是 for + if。等价改写不影响行为。

出处：`backend/internal/video/like_repo.go`、`tag_entity.go`。

### 2.4 结构体嵌入（组合代替继承）

```go
type Claims struct {
    AccountID uint `json:"account_id"`
    jwt.RegisteredClaims   // 匿名字段 = 嵌入
}
```

`Claims` 直接获得 `RegisteredClaims` 的所有字段，序列化时平铺（JWT 里的 `exp`、`iat` 就是这样来的）。
Java 对照：字段组合 + 委托。

出处：`backend/internal/auth/jwt.go`。

### 2.5 接口是隐式实现的

```go
type NotificationHub interface {
    Push(userID uint, n *Notification)
}
var _ NotificationHub = (*SSEHub)(nil)   // 编译期检查：SSEHub 必须实现该接口
```

没有 `implements` 关键字，方法齐了就自动实现。`var _ ...` 那行是惯用的编译期断言。
本项目接口很少，多数依赖直接写具体类型——Java 版按正常的接口/实现分层即可。

出处：`backend/internal/worker/notificationworker.go`。

## 3. 函数、错误与 defer

### 3.1 多返回值与 `if err != nil`

Go 没有异常。约定：**函数的最后一个返回值是 error**，非 nil 即失败。

```go
account, err := as.FindByUsername(ctx, username)
if err != nil {
    return "", "", err
}
```

读法：`if err != nil` 约等于 `catch`；`return nil` 约等于正常返回。
Go 代码里充满了这种提前返回（guard clause），把它读成一连串"失败就早退"即可。

出处：`backend/internal/account/service.go`。

### 3.2 错误的分类：`errors.Is` 与 `errors.As`

| Go | Java 近似物 | 本项目例子 |
|----|-------------|-----------|
| `errors.New("...")` 定义哨兵错误，`errors.Is(err, ErrX)` 判断 | 自定义异常类型 + `catch` | `ErrUsernameTaken`（`account/service.go`） |
| `errors.As(err, &mysqlErr) && mysqlErr.Number == 1062` | 捕获特定 SQL 异常 | 唯一键冲突判断（重复赞、重复关注） |
| `gorm.ErrRecordNotFound` | 查询结果为空 | 全项目通用 |

全项目的"错误 → HTTP 状态码"规则只有 28 行：`backend/internal/apierror/errors.go`。
先读它。它只认 3 种错误，**其余一律 500**。所以你会看到"密码错误返回 500"这种事，
这不是你看错了，是 Go 版的历史包袱（处理方式见《03》第 5 节）。

### 3.3 defer：函数返回前必执行

```go
ctx, cancel := context.WithTimeout(ctx, 50*time.Millisecond)
defer cancel()
```

`defer` 后面的语句推迟到函数退出时运行（无论哪条 return），相当于
try-with-resources 的关闭动作或 `finally`。全项目最常见的三件事：`cancel()`、`Close()`、`Unlock()`。

出处：`backend/internal/account/service.go`（同款 50 ms 超时多处）。

### 3.4 函数是值：中间件的本体

```go
func JWTAuth(repo *account.AccountRepository, cache *rediscache.Client) gin.HandlerFunc {
    return func(c *gin.Context) { ... }
}
```

函数可以当参数传、当返回值。`ratelimit.go` 的 `KeyFunc` 是"函数类型"，
`KeyByIP` / `KeyByAccount` 是它的两个实现——对应 Java 传一个 `Function<Request,String>`。

出处：`backend/internal/middleware/jwt/jwt.go`、`backend/internal/middleware/ratelimit/ratelimit.go`。

## 4. 并发三件套

### 4.1 goroutine：`go f()` 开一条执行流

```go
go runWorkerWithRetry(ctx, "SocialWorker", conn, ...)
```

约等于 `executor.submit(task)`，但创建成本极低。读的时候只需要问：
这段代码不在主流程里，它什么时候停、出错谁兜底。

出处：`backend/cmd/worker/main.go`。

### 4.2 channel：阻塞队列 + select 多路等待

```go
ch := make(chan *Notification, 20)        // 容量 20 的 BlockingQueue

select {
case <-ctx.Done():                        // 取消信号
case n := <-ch:                           // 收到通知
case <-time.After(30 * time.Second):      // 超时
}
```

`select` 同时等多个事件，谁先到走谁。还有一行值得注意的惯用法（`ssehub.go`）：

```go
select {
case ch <- n:
default:            // 队列满就直接丢弃，绝不阻塞推送方
}
```

推送端不背慢客户端的债——Java 里对应 `offer` 失败即弃。

### 4.3 WaitGroup 与 Mutex

```go
var wg sync.WaitGroup
var mu sync.Mutex
wg.Add(1)
go func() {
    defer wg.Done()
    mu.Lock(); defer mu.Unlock()
}()
wg.Wait()          // 相当于 CountDownLatch.await()
```

出现在 `feed/service.go` 的 `GetVideoByIDs`（并发查库回写缓存）。
注意 Go 闭包捕获循环变量的老坑：代码用 `go func(videoID uint)` 以参数传值规避。

### 4.4 singleflight：同键请求合并

```go
v, err, _ := f.requestGroup.Do(key, func() (interface{}, error) { ...查库... })
```

同一个 key 并发到达时，只放一个请求去查库，其余等待共享结果——缓存防击穿的标准件。
Java 等价物：Caffeine 的 LoadingCache，或 `Map<Key, CompletableFuture>`。

出处：`backend/internal/feed/service.go`。

### 4.5 context：贯穿一切取消与超时

几乎每个 Go 函数的第一个参数都是 `ctx context.Context`。它负责传递"请求已取消"信号和截止时间。
本项目最高频的用法是 **50 ms 缓存超时**（`context.WithTimeout(ctx, 50*time.Millisecond)`）——
意思是"缓存是加速器，不是数据源，宁可不要也别让它拖慢请求"。

出处：`backend/internal/account/service.go`（50 ms 缓存超时的典型点）。

> 说明：Java 没有直接对应物。**不要**发明一个 Context 参数往下传。
> 超时用 Lettuce/HTTP 客户端自带的 timeout 配置实现；取消交给连接池和线程池。

## 5. 框架惯用法对照表（翻译时查）

### 5.1 Gin（Web 框架）→ Spring MVC

| Gin 写法 | 含义 | Spring MVC 对应 |
|----------|------|-----------------|
| `r.Group("/account")` | 路径分组 | `@RequestMapping("/account")` |
| `group.POST("/login", h.Login)` | 注册路由 | `@PostMapping("/login")` |
| `group.Use(中间件)` | 挂拦截器 | interceptor / filter |
| `c.ShouldBindJSON(&req)` | JSON 转 DTO | `@RequestBody` + Jackson |
| `binding:"required,min=1"` | 字段校验 | `@NotNull` + `@Min(1)` |
| `c.ShouldBind(&req)` | 表单/multipart 参数绑定 | `@RequestParam` |
| `c.FormFile("file")` | 接收上传文件 | `MultipartFile` |
| `c.JSON(200, obj)` | 写 JSON 响应 | `ResponseEntity.ok(obj)` |
| `c.GetHeader` / `c.ClientIP()` | 读请求头 / 客户端 IP | `request.getHeader` / `getRemoteAddr` |
| `c.Set("accountID", v)` / `c.Get` | 请求域传值 | `request.setAttribute` |
| `c.AbortWithStatusJSON(401, ...)` | 中断后续处理并返回 | 拦截器返回 false + 写响应 |
| `r.Static("/static", dir)` | 静态目录映射 | `addResourceHandler` |

### 5.2 GORM（ORM）→ MyBatis

| GORM 写法 | 含义 | MyBatis 对应 |
|-----------|------|--------------|
| `Where("id = ?", id).First(&acc)` | 主键查询 | `selectById` |
| `.Updates(map[string]interface{}{...})` | 按列部分更新 | 动态 `<set>` SQL |
| `.Delete(&Like{})` 后读 `RowsAffected` | 用影响行数判断是否删到 | mapper 返回 int |
| `gorm.Expr("likes_count + 1")` | 数据库内原子自增 | SQL 里写 `likes_count = likes_count + 1` |
| `Where("id IN ?", ids)` | IN 查询 | `<foreach>` |
| `.Transaction(func(tx ...))` | 闭包事务 | `@Transactional` |
| `FirstOrCreate(&tag, Tag{Name: n})` | 没有则插入（upsert 近似） | `INSERT IGNORE` + 再查 |
| `db.AutoMigrate(&Account{}...)` | 启动时自动建表 | Java 版不自动建表：每个阶段的第 1 步给你那几张表的 DDL，手工执行 |

表名规则：结构名转下划线复数（`Social` → `socials`，`OutboxMsg` → `outbox_msgs`）。全仓库没有覆写。

### 5.3 小杂项（5 分钟能过完）

| 写法 | 出处 | 含义 |
|------|------|------|
| `regexp.MustCompile(`#([\p{L}\p{N}_]+)`)` | `tag_entity.go` | 反引号是原始字符串；正则与 Java `Pattern` 语法基本相同 |
| `@(\w+)` 只匹配 ASCII | `comment_service.go` | Java 的 `\w` 默认行为相同，别加 UNICODE 标志 |
| `filepath.Join(".run","uploads")` | 多处 | 相当于 `Path.of`；相对路径基于**进程工作目录** |
| `os.MkdirAll` / `os.Create` | 上传链路 | `Files.createDirectories` / `Files.createFile` |
| 匿名结构体当查询投影 | `notificationworker.go` | `struct{ ID uint; AuthorID uint }` 只为取两列，Java 写个小 DTO |
| `time.Time.Unix()` / `.UnixMilli()` | 到处 | 秒 / 毫秒。**同一字段在不同接口单位不同**，《06》第 4 节有对照表 |
| Go 时间格式 `"200601021504"` | 热榜 key | 魔法参考时间：2006-01-02 15:04。等价 Java pattern `yyyyMMddHHmm` |

## 6. 本项目错误处理的标准形态

这个三行模式在全项目出现了几百次，务必读顺：

```go
if err != nil {
    c.JSON(apierror.ClassifyHTTPStatus(err), gin.H{"error": err.Error()})
    return
}
```

翻译要点：Java 版把这段收敛为 `@RestControllerAdvice` + 自定义异常，
但最终对外的 JSON 必须保持 `{"error": "同样的文字"}`。

出处：`backend/internal/account/handler.go` 等多处同款调用，规则本体在 `backend/internal/apierror/errors.go`。

## 7. 课后练习（答案都在源码里）

1. `Logout` 删了哪 3 个 Redis key、清了 `accounts` 表哪 2 个列？（`account/service.go`）
2. `chunk/complete` 时第 37 片文件丢了，接口返回什么？半成品文件怎么处理？（`chunk_handler.go`）
3. `SoftJWTAuth` 与 `JWTAuth` 的代码差异只有哪里？（`middleware/jwt/jwt.go`）
4. `wg.Wait()` 对应 Java 的什么？L3 层某个 goroutine 中 panic 会发生什么？
   （Go 答案：未 recover 的 panic 使整个进程崩溃。）

## 8. 结业标准

打开 `feed/service.go` 的 `ListByFollowing` 方法。它有四层逻辑：
查缓存 → miss 抢锁 → 抢不到锁就轮询等待 → 兜底查库。
**逐行口述**每一步在做什么、对应 Java 的什么设施。能做到，你的 Go 阅读量已达标，进入《02》。
