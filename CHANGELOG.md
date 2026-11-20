# Oneiric 版本更新日志

> 构建规则：每次打包（`createMojmapPaperclipJar`）自动 +0.01，版本号写在
> `build-version.txt`（整数计数器，7 → `V0.07`，100 → `V1.00`）。
> 只编译不打包不自增。
>
> 三个位置会显示版本号：jar 文件名 `Oneiric-V0.xx.jar`、manifest
> `Implementation-Version ... +b0.xx`、启动日志
> `Loading Luminol 1.21.1-DEV-dev/1.21.1@<hash>+b0.xx`。

---

## 开源整理（2026-11-20）

从"本地能编译"到"别人 clone 也能编译"，一共发现并修了 **3 个「生成产物当源码」的坑**。
它们有同一个特征：**开发机上文件早就生成过，看起来一切正常；干净 clone 后必然失败。**

### 坑 1：`patches/api/` 完全为空

API 层有 58 个文件块的改动（21 个 `org.purpurmc.purpur.*` 类、`io/oneiric`、
`com/mohistmc`、33 个接口新增），但**全部没有进补丁**。

修：把 `Oneiric-API` 的改动 commit 后 `rebuildApiPatches`，
生成 `patches/api/0001-*.patch`（132 KB / 3625 行）。

不做这一步的表现：`org.bukkit.Bukkit`、`org.purpurmc.purpur.*`
全部找不到，编译报上百行"程序包不存在"。

### 坑 2：`applyApiPatches` 不是自动的

`createMojmapPaperclipJar` **不会**触发 `applyApiPatches`。
只跑 `applyServerPatches` 时 `Oneiric-API/` 是空的，同样报上百行错误。

修：README / CONTRIBUTING / CI 全部改成**显式三步**。

### 坑 3：`paper-api-generator/generated/` 从没被生成

`Oneiric-API/build.gradle.kts:83-92` 把这个目录当作**额外源码目录**：

```kotlin
val generatedApiPath = rootProject.projectDir.toPath().resolve("paper-api-generator/generated")
generatedSources.srcDir(generatedApiPath)
```

里面是 24 个自动生成类，包括 `VanillaGoal`（Paper 的 mob goal API）和
`BlockTypeKeys` / `ItemTypeKeys` 这些注册表键。

生成它的是 `applyGeneratedApiPatches` 任务，而 **`applyApiPatches` 不依赖它**。
干净 clone 时这个目录根本不存在 → `PaperVanillaGoal` 找不到 `VanillaGoal` →
7 个 `@Override` 连带报错，共 8 个错误。

修：在构建流程里加上 `applyGeneratedApiPatches` 作为第 0 步。

> 注：`patches/generatedApi/` 保持为空是正确的 —— 越位检查确认这 24 个文件
> 与上游 Paper 内容完全一致（只差 CRLF），没有任何自定义修改，
> 所以任务是"从上游复制 + 应用空补丁"，不需要自建补丁。

### 验证

```
applyGeneratedApiPatches   BUILD SUCCESSFUL
applyApiPatches            1 patches applied cleanly to Oneiric-API
applyServerPatches         19 patches applied cleanly to Oneiric-Server
createMojmapPaperclipJar   BUILD SUCCESSFUL  →  Oneiric-V0.16.jar (55.1 MB)
```

**正确的构建顺序（四步，缺一不可）**：

```bash
./gradlew applyGeneratedApiPatches   # 生成 API 自动生成源
./gradlew applyApiPatches            # 生成 Oneiric-API
./gradlew applyServerPatches         # 应用 19 个服务端补丁
./gradlew createMojmapPaperclipJar   # 打包
```

---

## V0.14 — 2026-11-20 19:22

**补齐空壳 API、接通诊断字段、移植 PCA 协议、采用 Leaves 命令框架。**

### 补齐：12 个「只有 javadoc 没有方法体」的空壳方法

`OneiricBaseRegion` 7 个：

```java
regionX(chunkX)          regionZ(chunkZ)
unpackX(key)             unpackZ(key)
minChunkX(regionX)       minChunkZ(regionZ)
sameBaseRegion(x1,z1,x2,z2)
```

实现时有个必须注意的点：**用算术右移 `>>` 而不是除法 `/`**。
Java 的 `/` 向零取整，负坐标会算错基础区（`-1 / 8 == 0` 但 `-1 >> 3 == -1`）。
区块坐标经常为负，这一点算错会让整个基础区坐标系偏移。

`OneiricPartitionHook` 4 个：`isAligned` / `forceMerge` / `groupByCluster` / `clusterOf`。
`OneiricRegionPartition` 1 个：`noteSplit()` —— 它缺失导致 `splitCount()` 恒为 0。

### 接通：三个「永远是 0 / 永远是空 / 零调用」的诊断项

| 项 | 修复前 | 修复后 |
| --- | --- | --- |
| `OneiricStatus.laggingRegions` | 恒为 0（`out[2]` 从未赋值） | 比较各区域 `getCurrentTick()` 与全局 tick，落后 ≥2 判定掉队 |
| `OneiricStatus.line()` | 零调用点 | `/oneiric line` / `/oneiric status` |
| `OneiricRegionPartition.splitCount()` | 恒为 0 | `noteSplit()` 补上，可被调用 |

**`perRegionMspt` 刻意保持为空**：内核没有暴露 tick 结束时刻，
任何"每区 MSPT"都只能是语义错误的数字。宁可空着，也不报假数据 ——
逐区耗时请用 spark 的 `/spark profiler --thread`。

### 新增：PCA 同步协议（396 行，移植自 Leaves）

客户端可读取容器内容与实体 NBT，生电调试漏斗链、比对箱子库存用。
移植时做了四处必要改动：

1. **26.x → 1.21.1 API**：`Identifier` → `ResourceLocation`，
   `identifier()` → `location()`，`player.level()` → `player.serverLevel()`
2. **去掉全局线程跳转**：Leaves 用 `getGlobalRegionScheduler()`，在 Folia 上那是全局线程，
   而 `world.getBlockEntity()` 是区域状态。payload 本来就由发包玩家所在区域线程分发，
   就地执行才是对的
3. **四张观察表换并发容器**，所有锁用 `try/finally` 包住
4. **修两个 Leaves 的缺陷**：
   - `syncBlockEntityToClient` 原来会 `playerList.addAll(playerListAdj)` ——
     往表里的 Set 塞别的坐标的观察者，后续 remove 找不到人 → **内存泄漏**
   - 从来没有 `@PlayerLeave` 清理 → 四张表永久持有已下线的 `ServerPlayer`

**玩家实体同步的语义**：Leaves 是 6 值枚举（`NOBODY`/`BOT`/`OPS`/`OPS_AND_SELF`/`EVERYONE`），
Oneiric 只有一个布尔开关。映射为：关 = `NOBODY`，开 = `OPS_AND_SELF`。
**刻意不用 `EVERYONE`** —— 那会让任何客户端都读到任意玩家的背包和坐标。

### 新增：Leaves 命令框架（8 个类）

移植 `CommandNode` / `LiteralNode` / `ArgumentNode` / `RootNode` /
`CommandContext` / `CommandUtils` / `WrappedArgument` / `EnumArgumentType`。

`/oneiric` 从手写 `switch(args[0])` 改成真正的节点树：

```
/oneiric              多行状态（默认）
/oneiric line         单行状态
/oneiric switches     全部开关的生效值
/oneiric density      实体密度热力图
/oneiric brain        大脑细粒度计时
```

好处：补全与参数校验由 Brigadier 自动给出，注册走 `PaperCommands` 的 dispatcher
（客户端能看到正确的命令签名），不再需要同时维护 `execute` 和 `tabComplete` 两份逻辑。

`/bot` / `/counter` / `/photographer` **没有迁移** —— 它们当前实测可用，
迁移的收益（补全）小于破坏已工作功能的风险。

### 验证

```
19 patches applied cleanly to Oneiric-Server
Done (25.448s)!
```

探针实测：

```
--- 命令注册
  oneiric      -> VanillaCommandWrapper   （已进原生命令表）
  bot          -> BotCommand
  counter      -> CounterCommand
  photographer -> PhotographerCommand

--- /oneiric 子树
  oneiric            -> true
  oneiric line       -> true
  oneiric switches   -> true
  oneiric density    -> true
  oneiric brain      -> true

--- PCA 协议类
  PcaSyncProtocol loaded, PROTOCOL_ID = pca
```

---

## V0.13 — 2026-11-20 18:33

**补上 6 个孤儿开关，接通三处死代码。**

这一轮是对比 8 个参考核心（Folia / Luminol / Paper / Leaves / LeavesX / Leaf / Canvas / PRTS）
时发现的。**上一轮我说"98/98 覆盖、0 缺口"是错的** —— 我只检查了
「NAMES 里的开关有没有 yml 说明」，没反过来检查「代码里读了但 NAMES 里没有」。

### 修复：6 个开关恒为固定值，无法配置

| 开关 | 修复前 | 后果 |
| --- | --- | --- |
| `replay-mod` | **永远 true** | `/photographer` 关不掉 |
| `protocol-packet-event` | **永远 false** | 插件无法拦截协议包 |
| `protocol-strict-mode` | **永远 false** | 未知 payload 静默丢弃 |
| `protocol-pca` | **永远 false** | PCA 同步不可用 |
| `protocol-pca-sync-player` | **永远 false** | 同上 |
| `protocol-syncmatica-quota` | **永远 false** | 配额限制不生效 |

原因：这 6 个名字在代码里被 `isEnabled()` / `enabled()` 读取，但没登记进
`OneiricConfig.NAMES`。而 `isEnabled()` 的兜底只看 `DEFAULT_ON`，于是
在不在 `DEFAULT_ON` 里就决定了它恒真还是恒假 —— 命令行和 yml 都改不动。

全部补进 `NAMES` 并写 yml 说明。**NAMES 98 → 104，覆盖率 104/104。**
另外 `PhotographerCommand` 现在真的检查 `replay-mod` 了（以前完全没判断）。

### 修复：Carpet 规则表永远是空的

`CarpetServerProtocol.CarpetRules.register()` **零调用点** ——
规则同步通道早就实现了，但没人往里放规则，于是发给 Carpet 客户端的
永远是一张空表。玩家装了 Carpet 也看不到服务器开了哪些规则。

新增 `LeavesConfig.registerCarpetRules()`，登记 **31 条**已实现的规则：

```
carpet 管理器：renewableCoral / redstoneIgnoreUpwardsUpdate / shearsWrench /
              movableBuddingAmethyst / hopperCounters / zeroTickPlants /
              renewableDeepslate / crafter1gt / oldRaidBehavior … 共 29 条
pca    管理器：avoidAnvilTooExpensive / sharedVillagerDiscounts
```

规则名沿用 Carpet 原名（这样客户端才能按名字匹配），管理侧标识与 Leaves 一致。
**刻意只登记真的实现了的** —— 把没实现的也写进去，玩家会在 HUD 上看到一个
开着却毫无行为的规则，那比看不到更糟。

在 `DedicatedServer` 里于 `LeavesProtocolManager.init()` 之后调用。

### 修复：替代方块放置是死代码

`CarpetAlternativeBlockPlacement`（154 行）和 `LitematicaEasyPlaceProtocol`（223 行）
两个类都在源码树里，但**没有任何调用点** —— 连配置项都不存在。

- 新增 `LeavesConfig.alternativeBlockPlacement` 枚举
  （`NONE` / `CARPET` / `CARPET_FIX` / `LITEMATICA`，
  用 `-Doneiric.alternative-block-placement=` 设置）
- 接入 `BlockItem.getPlacementState()`

现在 Carpet 的精确放置和 Litematica 的投影自动建造能真正工作了 ——
客户端把目标朝向编码在点击坐标里，服务端按偏移反解，否则投影建造会放错方向。

### 验证

```
18 patches applied cleanly to Oneiric-Server
[Oneiric] 读取了 oneiric.yml，104 个开关
Done (52.280s)!
```

---

## V0.12 — 2026-11-20 16:42

**配置文档补齐 + 修一个上游遗留的区域轮转缺陷。**

### 修复：`oneiric.yml` 里看不到假玩家功能

15 个 `fakeplayer-*` 开关一直登记在 `OneiricConfig.NAMES` 里，功能能用，
但**没有对应的 `oneiric.yml` 条目** —— 打开配置文件会以为核心没这个功能。
现在补了完整说明（段标题、做什么、为什么默认这个值、什么时候该改），并列出 `/bot` 命令表。

覆盖率从 `83/98` 提升到 **`98/98`，0 缺口**。

### 修复：yml 里的非布尔值被静默丢弃

`OneiricYml.parse()` 原来只收 `true`/`false`：

```java
if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
    out.put(name, Boolean.parseBoolean(...));   // 其它值直接丢掉
}
```

于是这 5 个参数型配置在 yml 里写了**毫无反应，日志也没有任何提示**：

```
fakeplayer-limit / fakeplayer-prefix / fakeplayer-suffix
fakeplayer-regen-amount / fakeplayer-unable-names
```

改成保留原始字符串写入系统属性，由 `OneiricConfig.intValue()` /
新增的 `stringValue()` 各自解析。配套改动：

- 新增 `OneiricYml.blockRaw()`：写数值/字符串型条目（字符串自动加引号，
  避免 `fakeplayer-unable-names` 里的逗号被 YAML 误解析成数组）
- `LeavesConfig` 的 5 个字段改走 `intValue` / `stringValue`
- `fakeplayer-regen-amount` 用容错解析：写错值只**告警并回落**，
  不再让 `NumberFormatException` 在类初始化时把服务器拖崩

### 修复：`RegionizedWorldDataSwitcher` 的轮转逻辑是死代码（上游遗留）

`io.oneiric.core.RegonizedWorldDataSwitcher`（来自 Luminol 基线）里有个
`//TODO Is that a truly exact value?` 标记。查下去发现**不是阈值问题，是两处逻辑缺陷**：

**1. `calledTimes` 从未递增**

```java
if (this.calledTimes.get() / regionToData.size() >= 400) {  // 永远是 0/size >= 400 = false
    this.calledTimes.set(0);
    this.randomIndex.set(idx + 1);                          // 永远执行不到
}
```

`calledTimes` 只有 `set(0)`，**没有任何 `++`**。所以 `randomIndex` 恒为 0，
整个"轮转"是死代码 —— 每次查不到时都只命中 `regionToData` 的第一个元素。
区域拆分/合并瞬间大量实体涌向同一份区域数据，其他区域数据却空闲。

**2. 越界判断用了 `>`**

```java
if (idx > regionToData.size())   // idx == size 会穿过
```

`idx == size` 时后面的 `curr == idx` 循环走不到，于是**静默返回 `lastMatch`** ——
可能是上一个世界的、甚至 `null` 的数据。

两处都修正，并把 `400` 提取成有名字的常量 `SWITCH_BATCH`，
说明它的语义是"同一索引连续命中 `BATCH × 区域数` 次后换下一个"。

### 验证

```
17 patches applied cleanly to Oneiric-Server
[Oneiric] 读取了 oneiric.yml，98 个开关
Done (49.942s)!
```

生成出的 `oneiric.yml` 共 532 行，假玩家段在 248 行起，参数段在 304 行起，
`fakeplayer-limit: 10` / `fakeplayer-prefix: ""` / `fakeplayer-unable-names: "Server"`
等非布尔值格式正确，回读无一条"默认值不一致"告警。

---

## V0.11 — 2026-11-20 07:11

**修复两个「编译通过但服务器不能用」的致命问题。**

### 修复

- **启动即崩：`DEFAULT_ON` 里 `replay-mod` 重复**
  `Set.of(...)` 遇到重复元素直接抛 `IllegalArgumentException`，而 `OneiricConfig`
  在 Moonrise 初始化时就被加载 → 服务器还没起来就崩。
  `V0.07` 和 `V0.08` 都有这个问题。
  已加类初始化自检：报错会点名**哪张表、哪个开关、出现几次**，而不是 `Set.of`
  那句没有上下文的 `duplicate element: xxx`。这类 bug 本项目已发生三次
  （`mc-technical-mode`、`crafter1gt`/`renewable-deepslate`、`replay-mod`）。

- **`/bot` `/counter` `/photographer` 根本不注册**
  `SimpleCommandMap#register(String label, String fallbackPrefix, Command)` ——
  **第一个参数是命令名**。原来写成 `register("oneiric", NAME, cmd)`，
  命令被注册成 `counter:oneiric`、`bot:oneiric` 这种名字，`/counter` 派发不到，
  **而且不报任何错**。
  `/oneiric` 之所以"看起来正常"，是因为它的 label 和 fallbackPrefix **恰好都是
  `oneiric`** —— 这个巧合掩盖了 bug。

### 改进

- **`/bot create` 从 12 秒缩短到 1-2 秒**
  `spawnWithSkin` 原来是「先拉 Mojang 皮肤，再创建」，连不上 Mojang 时要等满
  两次 HTTP 超时。改成**先创建（立刻上线），皮肤异步后补**；皮肤只是外观，
  晚几秒完全可接受，而"敲了命令没反应"不可接受。

### 验证

```
command 'bot'          -> org.leavesmc.leaves.bot.BotCommand
command 'counter'      -> org.leavesmc.leaves.command.CounterCommand
command 'photographer' -> org.leavesmc.leaves.command.PhotographerCommand

dispatch: bot create TestBot -> returned true
bots after create: 1
  bot: TestBot world=world at 0,-60,0
dispatch: bot remove TestBot -> returned true
bots after remove: 0
```

---

## V0.10 — 2026-11-20 07:04

**命令注册修复（同 V0.11 的第二项，这是首次验证通过命令注册的构建）。**

- 修正 `register()` 参数顺序后，`/bot` `/counter` `/photographer` 首次
  出现在命令表里并可派发
- 探针实测：`bots after create: 0` → 发现 bot 是异步创建的，
  `TestBot[Local] logged in with entity id 11` 在几秒后才出现

---

## V0.09 — 2026-11-20 06:47

**修复 `V0.08` 的启动崩溃。**

- 删掉 `DEFAULT_ON` 里重复的 `"replay-mod"`
- 加 `checkNoDuplicates(...)` 静态自检

---

## V0.08 — 2026-11-20 06:39

**假玩家 / Bot 系统首次植入 + 全量线程安全审计。**

> ⚠ 这个版本**启动即崩**（`replay-mod` 重复），不要使用。

### 新增：假玩家 / Bot 系统（42 个新类 + 23 个原版改动）

移植自 `Leaves-ver-1.21.1/patches/server/0010-Fakeplayer-support.patch`（4780 行）。

**命令**

```
/bot create <名字> [皮肤名]   [世界 x y z]
/bot remove <名字> [时] [分] [秒]
/bot remove <名字> cancel
/bot action <名字> <动作> ...     攻击/挖掘/钓鱼/跳跃/看向/转向/潜行/游泳/使用物品…
/bot action <名字> list|stop <索引>|stop all
/bot config <名字> <配置项> [值]   simulation-distance / skip-sleep / spawn-phantom / always-send-data
/bot list [世界]
/bot save <名字>  /  /bot load <名字>
```

**开关**（`oneiric.yml`）

```yaml
fakeplayer-support: true              # 总开关（默认开；不建 bot 时零开销）
fakeplayer-resident: false            # 常驻，重启后自动复活
fakeplayer-manual-save-and-load: false
fakeplayer-modify-config: false
fakeplayer-use-action: false
fakeplayer-skip-sleep: false
fakeplayer-spawn-phantom: false
fakeplayer-always-send-data: false
fakeplayer-cache-skin: false
fakeplayer-open-inventory: false
fakeplayer-limit: 10
fakeplayer-prefix: ""
fakeplayer-suffix: ""
fakeplayer-regen-amount: 0.0
fakeplayer-unable-names: "Server"
```

**数据文件**（刻意与玩家数据分开，避免清理玩家时把 bot 一起带走）

```
<world>/fakeplayerdata/<uuid>.dat   每个 bot 的实体存档
<world>/fakeplayer.dat              bot 清单（名字/uuid/resume）
```

**Folia 适配要点**（这是移植的主要工作量）

| 位置 | 问题 | 处理 |
| --- | --- | --- |
| `BotList.networkTick` | 全局 tick 里 `forEach` 全部 bot → 跨区 `doTick` | 逐个判断 `TickThread.isTickThreadFor(bot)`，线程对就地 tick，否则投递到它自己的区域 |
| `createNewBot` / `removeBot` | 放置/移除是区域状态修改 | 投递到 bot 所属区域；**停服时例外**（调度器已不接任务，就地执行） |
| `loadNewBot` | 需要先知道目标世界/坐标才能决定投递 | 先**非破坏性** `read()` 探测，放置成功后才 `consume()` 删档 |
| `BotCommand` 定时移除 | 用全局 `BukkitScheduler`，回调在全局线程改 bot | 改用 `getRegionScheduler().runDelayed` |
| `ServerBot.sendFakeData` | 延迟 10 tick 同步头部，用全局调度器 | 区域调度器 + 朝向提前快照 |
| `UseItemOnAction` 等 | `chestBlockEntity.stopOpen()` 跨区改方块实体 | 区域调度器 |
| `MojangAPI` | `openStream()` 无超时、缓存非并发、失败也写缓存 | 5s/10s 超时、`ConcurrentHashMap`、失败不缓存 |

**API 差异（必须知道）**

Leaves 把 `getBotManager()` 加在 `org.bukkit.Server` / `Bukkit` 上，靠的是
`patches/api`。Oneiric 的 `patches/api` 是空的，所以改走：

```java
org.leavesmc.leaves.entity.BotApi.getBotManager()
org.leavesmc.leaves.entity.BotApi.getBot(String) / getBot(UUID)
org.leavesmc.leaves.entity.BotApi.getBots()
org.leavesmc.leaves.entity.BotApi.isBot(Player)
```

签名与 Leaves 完全一致，插件机械替换即可。

### 修复：45 处 Folia 线程安全缺陷

根因是架构性的：`@ProtocolHandler.Ticker` 跑**全局线程**（`handleTick` 只在
`region == null` 时执行），而 `@PayloadReceiver` 跑**发包玩家所属的区域线程**
→ 同一个静态注册表被"全局线程 + 多个区域线程"同时读写。

- **30 处容器** → `ConcurrentHashMap` / `CopyOnWriteArrayList` / `newKeySet()`：
  BotList 的两张表、Actions、Configs、AppleSkin(4)、BBOR、Carpet(2)、
  Bladeren(2)、MsptSync、Jade、PacketSplitter、ServuxEntity、ServuxHud(4)、
  ServuxStructures、ServuxLitematics、CommunicationManager(6)、
  SyncmaticManager、PlayerIdentifierProvider、FileStorage
- **4 处加锁/volatile**：`HopperCounter.counter`（fastutil 有序 map 无并发版，
  漏斗热路径多区域并发 add + `/counter` 迭代 → 按单色加锁，`format()`
  锁内快照）、`HopperCounter.enabled`、`JadeProtocol.shearableBlocks`、
  `ServuxHudDataProtocol.refreshSpawnMetadata/refreshWeatherData`、
  `ServuxStructuresProtocol.retainDistance`、`Recorder.isSaved`
- **2 处去掉错误的全局线程跳转**：`JadeProtocol.requestEntityData/requestBlockData`、
  `ServuxEntityDataProtocol.onBlockEntityRequest/onEntityRequest` ——
  它们本来就已经在玩家区域线程上，再跳全局线程反而变成跨区读
- **1 处按区块路由**：`SchematicPlacement.pasteToFolia` ——
  Litematic 常横跨多区块，相邻区块可能属于**不同区域**，按 `ChunkPos`
  逐个投递到各自区域

### 修复：`ServerBot.doTick()` 每 tick 抛异常

Leaves 原文 `this.server.tell(this.server.wrapRunnable(this::runAction))`，
而 Folia 里这两个方法**都直接抛 `UnsupportedOperationException`** →
`doTick()` 每 tick 崩一次，后面的 `livingEntityTick()` 永远执行不到，
bot 既不动也不跑动作。改为直接同步调用 `runAction()`。

同一模式在 Litematica 粘贴里也存在（`scheduleOnMain` = `tell(wrapRunnable(...))`），
**粘贴功能 100% 失败**，已一并修掉。

### 修复：数据丢失风险

`loadNewBot` 原来在探测目标位置时就调用 `playerIO.load(probe)`，
而这个方法**会删掉存档文件**。一旦区域投递失败，bot 数据永久丢失。
改成 `read()`（只读）→ 放置 → `consume()`（删档）三步分离。

---

## V0.07 — 2026-11-20 03:04

**ReplayMod 摄影师 + 三个空壳开关补齐。**

### 新增：ReplayMod 摄影师

11 个类：`Recorder` / `ReplayFile` / `RecordMetaData` / `ReplayMarker` /
`RecorderOption` / `DigestOutputStream` / `ServerPhotographer` /
`ServerPhotographerGameMode` / `ServerPhotographerPacketListenerImpl` /
`PhotographerStatsCounter` + `PhotographerCommand`。

```
/photographer create <id> [跟随玩家]
/photographer remove <id>
/photographer list
```

产物是标准 `.mcpr` 回放文件，可直接用 ReplayMod 打开。

**Folia 适配**：创建时必须投递到目标位置的区域线程；移除时检查
`TickThread.isTickThreadFor(this)`，不在就重新调度。

### 新增：renewable-coral（可再生珊瑚）

- `FertilizableCoral` + `CoralPlantBlock` / `CoralFanBlock` / `WallFanBlock`
- 4 个 feature 类（`CoralFeature` / `CoralClawFeature` / `CoralTreeFeature` /
  `CoralMushroomFeature`）从 mc-dev 复制并放开 `placeFeature` 可见性
- 两档模式：默认只有珊瑚株可骨粉（Carpet `TRUE` 语义）；
  `-Doneiric.renewable-coral-mode=expanded` 时珊瑚扇与墙扇也可骨粉（`EXPANDED`）

### 新增：redstone-ignore-upwards-update（红石忽略向上更新）

4 处改动统一在一个开关下：`RepeaterBlock` / `ComparatorBlock` /
`RedStoneWireBlock` / 绊线钩，`direction == DOWN` 时直接返回。
`redstone-trapdoor-connect` 作为别名。

### 修正：old-block-remove-behaviour 是 1.21.1 上的空操作

查明 1.21.1 **原生就有** `onRemove` 钩子，这个补丁只是回退了 1.21.2+ 的重构。
`oneiric.yml` 里如实标注，没有写死代码假装实现。

---

## V0.06 — 2026-11-20（未单独打包，并入 V0.07）

版本号两位小数格式调整（`0.007` → `0.07`，步进 +0.01）。

---

## V0.05 及更早（同一构建链累积）

### 协议层（8 套客户端模组协议）

移植自 LeavesMC/Leaves（GPLv3），默认全开 —— 只在客户端主动握手后才响应，
不占带宽、不影响原版客户端。

| 协议 | 用途 |
| --- | --- |
| Carpet | 规则同步、TPS/mobcaps HUD |
| BBOR | 结构边界可视化 |
| XaeroMap | 小地图/世界地图 |
| AppleSkin | 饥饿值/饱和度同步 |
| Jade | 悬停方块/实体信息 |
| ChatImage | 聊天图片 |
| Servux（5 个子协议） | HUD 记录器 / 实体数据 / 结构数据 / Litematica 原理图 |
| Syncmatica | 共享原理图放置 |
| Bladeren | MSPT 广播 |

**REI 未移植**（26.x 的 `crafting.display.*` 在 1.21.1 不存在，且客户端协议格式不同），
已在 `oneiric.yml` 注明。

### 羊毛漏斗计数器（Carpet `hopperCounters`）

```
/counter [enable|disable|reset]
/counter <颜色> [reset|realtime]
```

漏斗指向羊毛时计数；`hopper-counter-unlimited-speed` 可一次 tick 搬空。

### Carpet / 生电规则（20+ 条）

剪刀扳手、可推动紫水晶母岩、旁观者不得成就、木棍切盔甲架手臂、
雪球鸡蛋击退、三角分布扁平化、鞘翅不加载区块、优化末影龙重生、
铁砧不显示"过于昂贵"、共享村民折扣、蜘蛛骑士掉金苹果、
僵尸增援/僵尸猪灵掉落/袭击/矿车 旧版行为、湿 TNT 不伤掉落物、
烟花保持拴绳、允许 Inf/NaN 运动值、出生无敌时间、
发射器剪刀不耗耐久、零 tick 作物、可再生深板岩/海绵、
铜灯 1gt / 合成器 1gt、绊线复制、无 TNT 放置更新…

### 修复：`ServerTickStartEvent` / `ServerTickEndEvent` 每 tick 重复触发

Folia 里 `tickChildren` **每个区域线程各跑一次**，于是这些事件每 tick 被触发
N 次（N = 区域数）。spark / ClearLag 这类"每 tick 滚动平均"的插件会把定长
环形缓冲写越界（实测 837 次/分钟）。

加 `OneiricTickEventGate`：从全局 tick 句柄推导代次，每 tick 只放行一次。
开关 `compat-single-tick-event` 默认开。

### 修复：协议层 payload CCE（玩家进不了服的真正原因）

```
java.lang.ClassCastException: SyncmaticaPayload cannot be cast to DiscardedPayload
```

1.21.1 的 `CustomPacketPayload` codec 是**静态构建、不可注册**的，未知 payload
类型会被硬转成 `DiscardedPayload`。修复：把 `LeavesCustomPayload` 包装成
`DiscardedPayload(id, body)` 再发。同时发现入站 `@PayloadReceiver` 的类型分发
**从未接上**，一并补了 `handleTypedPayload`。

### 修复：版本号自动递增 + 产物简化命名

- `build-version.txt` 整数计数器，打包时 +1，格式 `0.xx`（步进 +0.01）
- 产物 `Oneiric-V0.xx.jar`（原始长名 `Oneiric-paperclip-...jar` 保留，
  因为 paperweight 的任务输出跟踪指向它）
- manifest 写 `Oneiric-Build-Version`，启动日志显示 `+b0.xx`

---

## 待办 / 已知限制

| 项 | 状态 |
| --- | --- |
| REI 配方同步 | 未移植（1.21.1 缺 API），已文档化 |
| 多区域下 tick 事件闸门的真实压力验证 | 代码级原因已确认，但本地压力测试被 `ThreadedRegionizer` 合并成单区域，无法复现多区并发 |
| `CraftBotManager.getBots()` 的惰性视图 | 与 Leaves 一致，插件在自己线程物化 `CraftBot`；未改 |
| 假玩家的跨区只读快照 | `sendFakeData` / `renderAll` 仍跨区**读**位置；只读且读到略旧值不影响正确性，收敛代价大于收益，已在代码里注明 |
| 5 个 Purpur 可选 API | `addFuel` / `sendBlockHighlight*` / `sendDeathScreen` 抛 `UnsupportedOperationException` + 记日志。**刻意为之**：假装实现会让插件静默失效 |

### 关于上游遗留的 TODO

`Oneiric-Server` 源码树里有 160 处 `TODO`/`FIXME`，逐个查过：

- **159 处来自上游**（Folia / Paper / Moonrise / concurrentlyutil），不属于本项目
- **1 处**在 `io.oneiric.core.RegonizedWorldDataSwitcher`，已在 V0.12 修掉
  （实际是两处逻辑缺陷，不是那个 TODO 猜测的"阈值不准"）

Oneiric 自有代码（`io/papermc/paper/oneiric/**` 16 个文件 + `io/oneiric/core/**`）
**已无待办项**。
