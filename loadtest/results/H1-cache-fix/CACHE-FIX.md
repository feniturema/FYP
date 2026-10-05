# SeckillEventCache 阻塞加载风险：确定性复现与修复验证

> 诊断记录，不是正式 H1 性能结论。分支 `p3-bench` @ `dd3150f`，修复**未提交**。
> 代码状态 = HEAD + 未提交 diff（`git diff -- backend` 的 sha256 前 16 位 `2b8014191872d088`）+ 此前的 `ENVIRONMENT.md` 修改。
> 本目录所有压测 `run.json` 都如实记录 `gitDirty=true`。日期 2026-10-05，时间为 UTC。
> 本目录不含 JWT secret、DB 密码、tracking token：压测 JFR、后端日志和 MySQL processlist 原文只保存在 `/tmp/ftsm-h1-cache-fix/`，这里只放聚合摘要。

## a. 确定性确认的缓存风险

**结论（已确认）：**
- 修复前，`SeckillEventCache.get()` 在 `ConcurrentHashMap.compute` 的监视器内执行阻塞的数据库加载。
- 在 JDK 21 上，持锁阻塞的加载线程会被钉住在承载线程上；同一 key 的等待者阻塞在进入监视器处，也各钉住一个承载线程。
- 等待者数量达到承载线程数（本机为 8）后，**JVM 内所有虚拟线程都无法运行**，包括与缓存无关的任务。

**复现方式：** 独立 JVM，见 `repro/CacheStallRepro.java` 和 `repro/COMMANDS.txt`。
- 使用真实编译的 `SeckillEventCache` 和 Caffeine 3.2.4；repository 是动态代理，`findById` 阻塞在 latch 上，不需要 MySQL。
- 步骤：加载线程进入 `findById`（latch 确认）→ N 个虚拟线程 `get(同一 key)` → 轮询状态（有上限）→ 提交一个无关的虚拟线程探针 → 采集 `jcmd Thread.print -l` 和 `Thread.dump_to_file`（`-Djdk.trackAllThreads=true`）→ `finally` 中释放 latch，并确认探针随后恢复。
- 调度参数只在子 JVM 的命令行上设置。

| 运行 | 实现 | 承载线程 | 等待者 | 无关探针 5 s 内完成 | 释放后探针 | 关键线程证据 |
| --- | --- | --- | --- | --- | --- | --- |
| R1 | 原实现 | 2（受控注入） | 8 | **否**（5005 ms 未运行） | 3 ms 后完成 | loader #29 挂在 worker-1 上，栈为 `parkOnCarrierThread` ← `findById` ← `ConcurrentHashMap.compute:1916`；waiter-0 挂在 worker-2 上，BLOCKED 于 `ConcurrentHashMap.compute:1932`；其余 7 个等待者和探针没有栈帧，从未得到运行。JFR：`VirtualThreadPinned` 1 次（25.4 s） |
| R2 | 原实现 | 默认（8） | 32 | **否**（5006 ms） | 2 ms 后完成 | 8/8 承载线程都标注 “Carrying virtual thread”（1 个 loader + 7 个 BLOCKED 于 `compute:1932`）；25 个等待者从未运行。JFR：`JavaMonitorEnter` 7 次，`VirtualThreadPinned` 1 次（25.4 s） |

R1 用 2 个承载线程放大机制，属于受控注入。R2 使用默认调度器，不依赖注入。

本复现**只**证明缓存的阻塞加载路径会让整个 JVM 的虚拟线程饥饿，**不**代表复现了 macOS 压测停滞的全部原因（见 c）。

**单元测试的红灯：** 在原实现上运行新的 `SeckillEventCacheTest`，8 个测试中只有 `aBlockedLoadDoesNotStarveOtherVirtualThreads` 失败（`every waiter got a carrier while the load was blocked` 为 false），另外 7 个契约测试通过。见 `unit/red-run.txt`。

## b. 修复与证据

**修改的文件**（只有这两个）：
- `backend/src/main/java/.../service/SeckillEventCache.java`
- `backend/src/test/java/.../service/SeckillEventCacheTest.java`

**实现：**
- **缓存类型：** 改为 Caffeine `AsyncCache`（`buildAsync()`）。
- **监视器内做什么：** `cache.get(id, (k, e) -> CompletableFuture.supplyAsync(() -> load(k), loader))`。监视器内只创建 future，并把任务提交给 `loader`；`findById` 在 `loader` 的虚拟线程上执行，不在任何监视器内。
- **调用方如何等待：** 在监视器外 `join()`，parks 时卸载承载线程。
- **executor：**
  - 归属：bean 自有的 `Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("seckill-event-load-", 0).factory())`。
  - 不使用 common pool；Caffeine 的维护任务仍在它的默认 executor 上，不做阻塞工作。
  - 没有队列，提交时不会阻塞，也不会在调用方线程内执行加载。
  - 容量：并发加载数由缓存限制为每个 key 最多一个。
  - 关闭：`@PreDestroy shutdown()`，先 `shutdown`，等待 5 s，再 `shutdownNow`；关闭后不再接受新的加载（会抛 `RejectedExecutionException`）。
- **异常语义：** 捕获 `join()` 的 `CompletionException`，重新抛出原始的 `RuntimeException` / `Error`（例如 `DataAccessException`），并用条件 `asMap().remove(eventId, 那个失败的 future)` 立即移除失败条目，保证下一次调用一定重试。
- **保留不变：** `EventWindow` 及其时间窗口判断、TTL（`app.seckill.event-cache-ttl`，默认 5s）、`maximumSize(10_000)`、同一 key 并发加载合并为一次、未知活动缓存为 `Optional.empty`、`invalidate`、公开构造器签名不变。另加一个仅包内可见的 `Ticker` 构造器，供测试使用。

**修复后的同一复现**（`repro/R3-*`、`repro/R4-*`，命令相同，只换类路径）：

| 运行 | 承载线程 | 等待者 | 等待者状态 | 无关探针 | 线程证据 |
| --- | --- | --- | --- | --- | --- |
| R3 | 2 | 8 | 8/8 已启动，WAITING | 0 ms 完成 | — |
| R4 | 默认（8） | 32 | 32/32 已启动，WAITING | 0 ms 完成 | 整份 dump 中 `ConcurrentHashMap.compute` 帧出现 **0** 次；真正的加载线程 `seckill-event-load-0` 停在 `findById` ← `CompletableFuture$AsyncSupply.run`；等待者停在 `CompletableFuture.join` → `VirtualThread.park`（已卸载）；**0/8** 承载线程挂着虚拟线程 |

R3、R4 的 JFR 都没有 pinning 或监视器事件。这只作为辅助证据，结论以上面的线程栈为准。

**单元测试：** `SeckillEventCacheTest` 共 11 个，全部通过；连续运行 3 次（每次 11/0/0），用于确认并发测试不随机失败。这 3 次用 `-q` 运行，日志里没有汇总行，当时的 surefire 报告之后被覆盖，所以没有单独保存。之后按审查意见改写了 invalidate 测试（见下文“审查”），最终结果在 `unit/final-cache-test.txt`。

| 测试 | 覆盖内容 |
| --- | --- |
| `concurrentGetsOfOneKeyShareASingleLoad` | 同一 key 并发加载只查询一次 |
| `unknownIdIsCachedAsEmpty` | 缺失活动缓存为空 |
| `loadFailureKeepsItsExceptionTypeAndIsRetried` | 加载异常时抛出原异常类型（不是 `CompletionException`），之后可以重试 |
| `anExpiredEntryIsReloaded` | 用可控 `Ticker` 验证过期后重新加载 |
| `invalidateForcesAReload` | invalidate 后重新加载 |
| `invalidateDuringAnInFlightLoadIsNotBlockedAndTheStaleResultIsNotCached` | 加载进行中 invalidate：不等待在途加载即返回；下一次 get 触发新加载（product 20）；旧加载随后完成（product 10），只返回给它自己的调用方，不会覆盖缓存中的新值 |
| `aBlockedLoadDoesNotStarveOtherVirtualThreads` | 加载阻塞时，无关虚拟线程照常运行 |
| `theQueryRunsOnTheCachesOwnVirtualThreadNotTheCaller` | 查询在缓存自有的虚拟线程上执行，不在调用方线程，也不在 common pool |
| `afterShutdownNoNewLoadIsStarted` | 关闭后不再启动新的加载 |
| `hitDoesNotQueryTheRepositoryAgain`、`windowIsInclusiveAtBothEnds` | 原有测试 |

**全量验证：** `cd backend && ./mvnw -B verify`：**70 个测试**（原 63 + 新 7），0 失败，BUILD SUCCESS。审查修改前见 `unit/verify.txt`，最终结果见 `unit/final-verify.txt`。Mockito 只打印了 self-attach 警告，没有影响测试，所以没有使用 `-javaagent`，也没有改 pom.xml。

**修复后压测**（诊断用，不是正式 H1；采集器、JVM 参数、`trackAllThreads`、运行时 JFR 与 H1-stall-debug 相同）：

| 配置 | 次 | 判定 | p50 / p95 / p99 (ms) | accepted = orders | 掉队 / k6 超时 / Hikari 超时 | Hikari pending 峰值 | drainSeconds |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B r200 | 1 | **无效（p99 ≥ 1000）** | 3.3 / 1034 / 2176 | 30030 = 30030 | 0 / 0 / 0 | 209 | 0 |
| B r200 | 2 | 有效 | 3.3 / 25 / 288 | 30029 = 30029 | 0 / 0 / 0 | 16 | 0 |
| B r200 | 3 | 有效 | 3.4 / 21 / 385 | 30005 = 30005 | 0 / 0 / 0 | 59 | 0 |
| C r400 | 1 | 有效 | 3.7 / 36 / 167 | 60022 = 60022 | 0 / 0 / 0 | 11 | 84 |
| C r400 | 2 | 有效 | 2.6 / 19 / 73 | 60020 = 60020 | 0 / 0 / 0 | 15 | 57 |
| C r400 | 3 | 有效 | 2.3 / 12 / 72 | 60026 = 60026 | 0 / 0 / 0 | 39 | 42 |

**B 第 1 次是一次约 10 s 的突发，然后自行恢复，不是停滞：**
- 13:39:57 健康探测 3.1 s、Hikari pending 209；13:40:12 已恢复（探测 2.1 ms、pending 0）。
- 突发期间的采集（`diagnostics/captures/throughput-B-r200-n1/stall-*`）：
  - 承载线程只有 3–4/8 在用，`ConcurrentHashMap.compute` 帧 0 次，没有 Java 死锁。
  - MySQL 有 **19 个事务处于 LOCK WAIT**，全部在 `update seckill_events set sold_count=sold_count+1 where id=…`（同一行），`data_lock_waits` 190 行，`Innodb_row_lock_time_max = 1854 ms`，另有提交在 `waiting for handler commit`。
- 这是 sync 模式在请求事务里更新同一个热点行造成的**数据库行锁排队**，是容量问题，不是 JVM 停滞。也说明：MySQL CPU 低并不能排除数据库锁等待。
- 这次是否触发与此修复无关，属于 B 的设计代价。

压测本身**不能证明**原始停滞已经消失：修复前一轮（H1-stall-debug）同样参数也是 0/6 停滞。修复风险的直接证据是 a/b 中的确定性复现。

一个相关观察（只是相关性）：修复前，未停滞的运行 Hikari pending 峰值不超过 27，两次停滞时是 35–37。修复后 pending 达到 39、59、209，都没有停滞。

## c. 原始现场根因中仍未确认的部分

1. **原始停滞时没有线程栈。** 两次原始停滞（H1-followup：B r200 第 2 次、C r400 第 1 次）没有当时的线程转储。我们确认了“这条路径能造成同样症状”，也就是全体虚拟线程饥饿、CPU 约 0、HTTP 无响应、每 30 s 串行放行一个；但没有直接看到当时的承载线程挂在 `SeckillEventCache` 上。其他可能钉住承载线程的 `synchronized`（日志、Lettuce、Hibernate）未逐一排除。
2. **Hikari housekeeper 的 52 s 延迟**（C 原始停滞）。它是平台线程，不受虚拟线程饥饿影响，原因未解释。
3. **停滞期间 JVM RSS 骤降、采样器自身变慢。** 本轮在宿主机看到 **swap 已用 3.15 GB / 4 GB**，存在内存压力；但它与原始停滞是否有因果关系未验证。
4. **触发条件** 推断为“缓存过期 + 连接池无空闲连接 + 同时有 ≥ 承载线程数的同 key 请求”，未被单独度量。

## 审查（提交前）

逐项核对，结论都是“成立”：
- 数据库加载在监视器外执行（R4 dump 中 `ConcurrentHashMap.compute` 为 0）。
- 调用方在 `cache.get` 返回之后才 `join()`，等待时不持有监视器。
- 同 key 并发加载合并为一次；缺失活动缓存为 `Optional.empty`。
- TTL 从 future 完成时开始计。
- 在途加载与 invalidate：Caffeine 在旧加载完成时用条件 `replace(key, 旧future, 旧future)`，不会覆盖新加载。
- 失败条目用条件 `remove(eventId, 那个失败的future)` 删除，不会误删之后的新加载。
- 原异常类型保留；关闭后不再接受新加载。
- 后台查询不依赖调用方的事务或线程上下文：唯一的 `get` 调用方 `SeckillService.buy` 本身不在事务内；`findById` 在加载线程上开启自己的只读事务。

审查后的修改：
1. **执行器注释不准确。** 原注释写“并发由缓存约束”，实际只有同 key 合并，没有全局上限：每个未缓存的 key 各启动一次加载；invalidate 后同一 key 可能短暂有新旧两次加载；`maximumSize` 只限制已缓存的条目，不限制在途查询。已改为如实描述，没有加限流。
2. **invalidate 测试不能证明它在加载完成前返回。** 已改写：在旧加载仍阻塞时完成 invalidate，并确认旧加载尚未结束；新 get 拿到 product 20；释放旧加载（product 10），等它的线程结束后确认缓存仍是 20；`findById` 共 2 次。线程异常通过 Future 传回测试线程，所有等待都有超时，`finally` 中释放 latch。

只改了注释和测试，实现逻辑没有改，所以 R1–R4 的复现结论不受影响，没有重跑。

## 行为差异（修复带来的已知语义变化）

- 同一个进行中的加载失败时，所有正在等它的调用方**共享这次失败**并立即返回。原实现中，等待者会在锁内逐个重新执行加载，每个都可能再等 30 s。下一次调用仍然会重试。
- 抛出的异常类型和实例与 repository 抛出的一致，但栈从加载线程开始，不包含调用方的栈帧。
- 过期时间从 future 完成时开始计（与原实现“加载完成时写入”相差微秒级）。
- 加载进行中被 `invalidate`：正在等待的调用方拿到这次加载的结果，下一次 get 重新加载。原实现中，`invalidate` 会阻塞到加载结束。

## 文件

**随修复提交一起保存（git 跟踪）：**
- 本文件 `CACHE-FIX.md`。
- `repro/CacheStallRepro.java`、`repro/COMMANDS.txt`。
- `repro/R1…R4/{stdout.txt,thread-print.txt,thread-dump.txt}`：复现输出（JFR 事件计数已打印在 stdout 中）和线程栈。
- `unit/*.txt`：红灯、审查前 verify、最终缓存测试、最终 verify 的输出摘录。

提交中的 `.txt` 证据只去掉了行尾空白和文件末尾的空行（原始输出在 `/tmp/ftsm-cache-fix/`），内容未改动。

**本地保留、未提交（不在 git 中，可能随清理丢失；本文和代码都不依赖它们）：**
- `repro/*/pinning.jfr`（二进制，事件数已在 stdout 中）。
- `unit/*.xml`（surefire XML 含测试 JVM 的全部系统属性）。
- `B-sync/`、`C-async/`、`SUMMARY.md`：压测 run 目录（`gitDirty=true`），summarize 结果为 5 有效、1 无效。
- `diagnostics/`：ledger、采样、线程转储、`mysql-summary.txt`（聚合，不含订单 ID）。
- 此前各轮的 H1 证据：`loadtest/results/{A-baseline,B-sync,C-async,H1-followup,H1-stall-debug}`、`SUMMARY.md`、`H1-*.t*`。
- 只在 `/tmp`：压测 JFR（含进程环境变量）、后端日志、MySQL 原始输出。

表格中 B/C 压测的数字来自上述本地目录，只用于诊断，不是性能数据。
