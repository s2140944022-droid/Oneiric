<p align="center">
  <img src="public/image/logo.png" alt="Oneiric" width="560">
</p>

<p align="center">
  <b>面向红石与生电的 Minecraft 1.21.1 区线程服务端核心</b><br>
  在 Folia 的区域并行之上，重建了一套「不拆散耦合装置」的调度机制
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License"></a>
  <img src="https://img.shields.io/badge/Minecraft-1.21.1-62b47a.svg" alt="Minecraft">
  <img src="https://img.shields.io/badge/Java-21+-f89820.svg" alt="Java">
  <img src="https://img.shields.io/badge/Version-V0.14-brightgreen.svg" alt="Version">
</p>

---

## 这是什么

普通服务端全世界只有一个线程在跑。红石机器一多，所有装置排队等待。

Folia 用**区域并行**解决这个问题：地图切成小格，互不相干的格子**同时** tick。

但 Folia 的切分规则是「按邻接关系算连通块」—— 它不知道你的末影人农场跨了 40 格、
不知道你在两个区块之间拉了红石线。一旦跨区，两个本该同步的部件被交给两个线程，
**时序就乱了**。

Oneiric 做的事：**保留并行，但让「必须同步的东西」一定落在同一个线程里**。

这不是暴力关掉并行 —— 那样就退回单线程了。而是在 Folia 的调度器里加了两个决策点：

- **拒绝拆开**：一次区域分裂如果会把一组耦合方块切开，就否决这次分裂
- **主动合并**：两个区域如果存在跨距离耦合但已经各自跑起来了，在 tick 边界把它们并起来

代价是什么？并行度会下降。所以我们同时做了一份**并行度损失报告** ——
让你知道为了行为正确，到底牺牲了多少。


---

## 设计原则

1. **行为同原版**：任何优化都不得改变红石、活塞、漏斗、作物、实体挤压的行为。
2. **插件零改动**：Bukkit / Paper / Purpur 插件 API 全部保留，包括 Folia 原本抛异常的那些。
3. **状态栏说真话**：配置里写着 true 的开关，代码里必须有读取点；做不到的功能明确抛异常，不假装成功。

其中第 1 条是硬约束。`OneiricConfig.FORCED_OFF` 里有 4 个开关**即使命令行显式打开也不接受**：

```
optimize-suffocation   optimize-entities   reduce-chunk-lookups   optimize-fluids
```

因为它们会改变挤压 / 流体 / 内部结构行为，与「行为同原版」冲突。

---

## 我们对 Folia 区域调度做了什么

**这是本项目最核心的原创部分。** 不是「用了 Folia」，是「改了 Folia 的调度决策」。

### Folia 原来的问题

Folia 的 `ThreadedRegionizer` 用**邻接连通性**决定哪些区块属于同一个 tick 区域。
它只认得「挨着」，不认得「有影响」。

于是这些情况都会出错：

| 装置 | 跨区后会发生什么 |
| --- | --- |
| 末影人农场 | 玩家在 A 区、击杀平台在 B 区，末影人的 anger 传递跨线程 |
| TNT / 水晶爆炸 | 爆炸在 A 区、被破坏的方块在 B 区，破坏结果读到的是旧状态 |
| 玩家与红石 | 玩家在 A 区、脚下的红石在 B 区，踩压力板的时序错位 |
| 长红石线 | 一条线横跨 3 个区，被 3 个线程各 tick 一段，中继器延迟不再连续 |

Folia 的做法是：**不管**。区块被拆开就是被拆开，行为差异自负。

### 我们的改动

#### 1. 基础区从 4×4 改成 8×8 区块

Folia 上游的 `gridExponent` 默认是 `2`（4×4 区块 = 64×64 方块）。
Oneiric 改成 `3`（8×8 区块 = 128×128 方块）。

理由：4×4 太细，一台标准红石机器几乎必然跨好几个区，
合并请求会被触发得非常频繁，反而增加调度开销。8×8 是「一台中等机器刚好放得下」的粒度。

#### 2. 引入「耦合边」概念（上游没有）

上游只有「邻接」。我们加了 `OneiricLongRangeCoupling`：**每个实体 tick 时观察自己影响多远**。

| 实体 | 影响半径 |
| --- | --- |
| 末影人 | 32 格 |
| TNT / 末地水晶 | 8 格 |
| 玩家 | 8 格 |
| 其他实体 | 5 格（默认） |

按实体包围盒的角点与 8×8 网格交叉校验，一旦发现跨区，
就在合并表里登记一条「这两个基础区**必须**同区」的边。

调用点在 `ServerLevel.tickNonPassenger` 路径上（`ServerLevel.java:818`），
和实体 tick 同一次遍历完成，不额外扫世界。

#### 3. 在 `ThreadedRegionizer` 注入 5 处决策点

这是真正「改 Folia」的地方。上游的合并/分裂流程（状态机、锁、`fromTickOffset` 换算、
队列迁移）耦合极深，重写任何一段都会连带破坏另外三段。
所以我们只在**决策点**上加判据，不动流程本身：

| 位置 | 注入内容 | 作用 |
| --- | --- | --- |
| `ThreadedRegionizer:68` | `assertAligned(regionShift)` | 启动时校验 section 粒度确实等于基础区粒度，不一致就报警 |
| `ThreadedRegionizer:734` | `vetoSplit(...)` | 分裂候选若会拆散某一簇 → **否决这次分裂** |
| `ThreadedRegionizer:614-628` | 主动合并 | 两个区已各自跑起来但存在耦合 → 在 tick 边界并起来 |
| `ThreadedRegionizer:242` | 簇归属判定 | 判断两个 section 是否同簇 |
| `ThreadedRegionizer:259` | `noteForcedMerge(n)` | 记录强制合并次数供诊断 |

**两个钩子的分工很重要**：

```
vetoSplit  = 阻止「拆开」   → 管不到「本来就已分开」的情况
主动合并    = 把「已分开」并起来 → 管不到「还没拆」的情况
```

只做其中一个都会留漏洞 —— 现实中两种情况都会发生。

#### 4. 放在正确的时机

主动合并放在 `onRegionRelease` **内部**，位置刻意的：

- 已经在 tick 边界 —— 符合「TICKING 中的区不可被结构修改打断」这条不变式
- 在回收 dead section 与分裂判定**之前** —— 合并后的区会再统一走一遍判定，
  不会出现「刚并完就带着 stale 状态进入分裂」
- 持有 `regionLock` 写锁 —— `killAndMergeInto` 的前置条件

#### 5. 让损失可见

强制合并是有代价的 —— 并行度会下降。如果不说出来，运维永远不知道
「为了 3 个末影人农场，我把 60% 的并行度吃掉了」。

`OneiricParallelismReport` 输出：

- 合并掉多少、否决多少次分裂
- 最大簇是哪个、为什么存在
- `lossRatio()` —— 实际并行度 / 理论并行度

`/oneiric` 一行就能看到。

### 这些改动的实测状态

| 项 | 状态 |
| --- | --- |
| 基础区坐标、耦合判定、合并表 | 已验证（单测级 + 启动验证） |
| `vetoSplit` / 主动合并的注入点 | 已验证编译、启动、`/oneiric` 输出正常 |
| **多区域并发下的真实压测** | **未完成** —— 本地压力测试被 `ThreadedRegionizer` 合并成单区，无法复现多区并发。需要在真实负载下验证 |

最后一条是诚实的。代码逻辑正确、注入点正确，但「在实际多区负载下表现如何」
需要你在真实服务器上跑才能确认。

---

## 功能来源

除上节所述的区域调度改动外，其余能力来自以下项目。**每项注明出处**，便于判断成熟度。

### 来自 Folia —— 区域并行（地基）

| 能力 | 说明 |
| --- | --- |
| 区域化 tick | 地图按区块切分，不同区域由不同线程同时 tick |
| Moonrise 区块系统 | Paper 重写的高性能区块加载/卸载 |
| 区域调度 API | `RegionScheduler` / `EntityScheduler` / `GlobalRegionScheduler` |

### 来自 Luminol —— 配置与工具层

| 能力 | 说明 |
| --- | --- |
| 配置模块 | `oneiric_config/` 下的 TOML 配置（SIMD、实体优化等） |
| `/membar` `/tpsbar` | 内存与 TPS 状态条 |
| 区域数据切换器 | 区域拆分/合并时把实体正确归位 |

### 来自 Paper —— 插件生态与性能

| 能力 | 说明 |
| --- | --- |
| Bukkit / Paper API | 绝大多数插件直接可用 |
| 异步区块加载 | 区块在后台线程生成与读取 |
| 反 Xray / 视距优化 | Paper 的经典优化 |
| 配置系统 | `paper-global.yml` / `paper-world-defaults.yml` |

### 来自 Purpur —— 额外 API 面

| 能力 | 说明 |
| --- | --- |
| Purpur API 接口 | 21 个 `org.purpurmc.purpur.*` 类，使 Purpur 插件能编译 |
| 真实现 | 服务器名、`isLagging()`、AFK 三件套、`PlayerAFKEvent` |
| 明确不支持 | 加燃料、方块高亮、自定义死亡界面 —— **调用时抛异常并记日志**，不假装成功 |

Purpur 的 API 在 Luminol / Folia / Paper 里一个都没有，是 Oneiric 自己补的。

### 来自 Leaves —— 生电与客户端协议

| 能力 | 说明 |
| --- | --- |
| **客户端 mod 协议（14 个）** | Carpet / BBOR / XaeroMap / AppleSkin / Jade / ChatImage / Servux×4 / Syncmatica / Bladeren / PCA |
| **假玩家 / Bot 系统** | `/bot create` 创建假人，可挂机、可执行动作 |
| **ReplayMod 摄影师** | `/photographer create` 让假人替你录回放，产出 `.mcpr` |
| **羊毛漏斗计数器** | `/counter`，Carpet 的 `hopperCounters` 同款 |
| **Carpet / 生电规则** | 30+ 条，逐条独立开关；31 条已注册进 Carpet 规则表供客户端 HUD 显示 |
| **可再生珊瑚 / 红石忽略向上更新** | 两条具体的生电规则 |
| **更新抑制防崩** | 刷沙机抛异常不再崩服，改为在 tick 边界消费 |
| **替代方块放置** | Carpet 精确放置 / Litematica 投影自动建造 |
| **PCA 容器同步** | 客户端读容器内容与实体 NBT，调试漏斗链用 |
| **Leaves 命令框架** | Brigadier 节点树，补全与参数校验自动生成 |

原版客户端完全感知不到这些协议，不占带宽。

### 来自 Canvas

| 能力 | 说明 |
| --- | --- |
| 行为控制位集 | 用 BitSet 代替 HashSet 存 AI 行为控制，减少对象分配 |

### 来自 Leaf

| 能力 | 说明 |
| --- | --- |
| 跨区寻路限制 | AI 寻路目标落在别的区域时丢弃该目标（按同样思路重写） |

---

## 其他独有功能

上节讲的是区域调度。以下是除此之外、在 Folia / Luminol / Paper / Leaves /
LeavesX / Leaf / Canvas / PRTS 全部 8 个参考核心里都找不到的内容。

### 1. 每 tick 只触发一次的服务端 tick 事件

**这是修上游的缺陷。** Folia 每个区域线程各跑一次 tick 循环，所以
`ServerTickStartEvent` / `ServerTickEndEvent` 每 tick 会被触发 **N 次**（N = 区域数）。

spark、ClearLag 这类「每 tick 滚动平均」的插件会把定长环形缓冲写越界 ——
实测到每分钟 **837 次**异常。

`OneiricTickEventGate` 用全局 tick 代次做闸门，每 tick 只放行一次。
开关 `compat-single-tick-event` 默认开。

### 2. 固定基础区坐标系

Folia 里「这个区块归谁」是算出来的（BFS 连通分量），tick 中途可能正在变。
于是方块刻问「我能不能写这个 chunk」时，得到的是那一刻的答案，不是 tick 开始时的答案。

`OneiricBaseRegion` 把 8×8 区块的格子定义为固定坐标，任何时刻都是纯函数，不查表。
并行诊断、所有权判定、合并表全都用它做参考系。

> 实现细节：区块 → 基础区必须用算术右移 `>>` 而不是除法 `/`。
> Java 的 `/` 向零取整，负坐标会算错（`-1/8 == 0` 但 `-1>>3 == -1`），而区块坐标经常为负。

### 3. 104 项逐条说明的配置

`oneiric.yml` 里每个开关都有 4 行说明：

```yaml
# 羊毛漏斗计数器
# 【做什么】漏斗正前方放羊毛时，把经过的物品按羊毛颜色计数，用 /counter <颜色> 查看。
# 【为什么默认开】对应 Carpet 的 hopperCounters 规则，是生电服统计产量的标准做法。
# 【什么时候该改】只在漏斗指向羊毛时生效，普通漏斗行为不变；不需要计数时可关。
hopper-counter: true
```

**104 个开关，104/104 有说明，0 缺口。**

优先级：命令行 `-D` > `oneiric.yml` > 内置默认值。
写错键名会点名告警，不会静默忽略。yml 默认值与代码默认值不一致时也会告警。

### 4. 生电红线

`OneiricConfig.FORCED_OFF` —— 四个开关显式禁止，见上文「设计原则」。

别的核心是「默认关但可以开」，Oneiric 是「禁止」。

### 5. 线程池自动 sizing

`OneiricThreadSizing` 自动决定四类线程池大小：

| 线程池 | 算法 |
| --- | --- |
| tick 线程 | `cores / 2`，限制 8..32 |
| 区块 worker | `cores × 3/4`，最少 12 |
| 区块 IO | `cores / 2`，上限 16 |
| 寻路 / Folia 异步 / 传播器 | 按核数推导 |

还有同机多服检测（通过 `%TEMP%\oneiric-share\` 文件锁），多开时自动降配。

### 6. Purpur API 完整补位

Purpur 的 API 在 Luminol / Folia / Paper 里一个类都没有。Oneiric 补了 21 个类，策略是：

- **能真做的真做**：服务器名、`isLagging()`、AFK 三件套、`PlayerAFKEvent`
- **做不到的抛异常**：加燃料、方块高亮、自定义死亡界面

第二条是刻意的。假装实现会让插件静默失效（燃料没加进去、高亮没显示），
运维查不出原因；抛异常至少让调用点立刻暴露。

### 7. 实体密度诊断

`OneiricEntityDensity` 按 4×4 区块网格统计实际参与 tick 的实体数，并记录历史最高。

关键是在实体 tick 路径上顺带累加，不像全量 `world.getEntities()` 扫描那样反过来干扰被测负载。

### 8. 生电模式一键开关

`mc-technical-mode` 一个开关同时打开：TNT 复制、无头活塞、永破、绊线复制、
末地门不安全传送，并把包限流放宽到 5000/s。

---

## 与同类核心的对比

| | Oneiric | Folia | Leaves | LeavesX | Leaf | Canvas | PRTS |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **区域并行** | ✅ | ✅ | ❌ | ❌ | ❌ | ✅ | 不同模型 |
| **改了区域划分规则** | ✅ **独有** | — | — | — | — | ❌ | — |
| **跨距离耦合判定** | ✅ **独有** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **并行度损失报告** | ✅ **独有** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| 假玩家 / Bot | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | 有限 |
| ReplayMod 摄影师 | ✅ | ❌ | ✅ | ✅ | ✅ | ❌ | ❌ |
| 羊毛漏斗计数器 | ✅ | ❌ | ✅ | ✅ | ❌ | ❌ | ❌ |
| 客户端协议 | 14 个 | ❌ | 全（含 REI） | 全（含 JEI/REI） | 部分 | ❌ | ❌ |
| Carpet 规则注册 | ✅ 31 条 | ❌ | ✅ | ✅ | 部分 | ❌ | ❌ |
| 每 tick 一次的 tick 事件 | ✅ **独有** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| 逐条说明的配置 | ✅ 104 项 | ❌ | 部分 | 部分 | 部分 | ❌ | 一句注释 |
| 生电红线（禁止开关） | ✅ **独有** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |

**结论**：只有 Oneiric 同时做到「区域并行 + 生电行为正确 + 客户端 mod 协议 + 假玩家 + 摄影师」。

Folia 有并行但没有生电生态；Leaves 系有生电生态但只能单线程。
Oneiric 是把两边接起来的那个。

---

## 已知限制

诚实清单。以下都是**明知不做**，不是遗漏：

| 缺什么 | 为什么 |
| --- | --- |
| **REI 配方同步** | 1.21.1 没有 `crafting.display.*` API（26.x 才有），客户端协议格式也不同。**做不了** |
| 异步寻路 / 多线程实体追踪 | Leaf 有，本核心仍用原版寻路。改动面广、无源码可参照，风险高于收益 |
| CPU 亲和性 / 虚拟线程 | LightingLuminol、Leaf 有，本核心未做 |
| 逐区 MSPT | 内核没有暴露 tick 结束时刻，任何数字都是错的。所以 `perRegionMspt` 保持为空 |
| 多区域 tick 闸门的生产验证 | 本地压力测试被 `ThreadedRegionizer` 合并成单区，无法复现多区并发，需要真实负载 |

---

## 构建

### 环境要求

- **JDK 21**（必需）
- **Git**，且 `git` 必须是**可直接执行的二进制**（不能是 `.cmd` 包装脚本），
  paperweight 会用 `ProcessBuilder` 调用它
- **必须在 git 仓库里构建**。构建脚本会把 commit hash 写进 jar 的 manifest，
  没有 `.git` 会报 `git rev-parse --short=7 HEAD` 失败。
  正常 `git clone` 下来的目录满足这个条件；如果你把源码打包成 zip 传给别人，
  对方需要先 `git init && git add -A && git commit` 才能构建
- 建议 8 GB 以上内存（构建期 Gradle 需要 3 GB）

### 命令

**必须按这个顺序执行。** 跳过第一步会导致 `100 个错误`（`org.bukkit.*` 找不到），
因为 `createMojmapPaperclipJar` **不会**自动生成 API 层。

```bash
# 0. 生成 API 自动生成源（VanillaGoal / registry keys）—— 不能跳过
./gradlew applyGeneratedApiPatches

# 1. 生成 API 层
./gradlew applyApiPatches

# 2. 生成服务端层
./gradlew applyServerPatches

# 3. 构建（产物：build/libs/Oneiric-V*.jar）
./gradlew createMojmapPaperclipJar
```

Windows 上把 `./gradlew` 换成 `gradlew.bat`。

首次构建需要从 GitHub 拉取 Folia / Paper / Luminol 上游并反编译，
**约 45 分钟**（视网络与磁盘）。之后增量构建约 2 分钟。

产物是 `Oneiric-V0.xx.jar`，版本号每次打包自动 +0.01。

### 为什么第 0、1 步都不能省

**第 0 步（`applyGeneratedApiPatches`）**：`Oneiric-API/build.gradle.kts` 把
`paper-api-generator/generated/` 当作**额外源码目录**参与编译
（`srcDir(generatedApiPath)`）。这个目录里是 24 个自动生成类，包括
`VanillaGoal`（Paper 的 mob goal API）和 `BlockTypeKeys` / `ItemTypeKeys`
这类注册表键。

不跑这一步，目录不存在 → `PaperVanillaGoal` 找不到 `VanillaGoal` →
`@Override` 全部报错，共 8 个错误。

**第 1 步（`applyApiPatches`）**：`oneiric-server` 依赖 `project(":luminol-api")`。
只跑 `applyServerPatches` 时 `Oneiric-API/` 是空的（或只有 2 个文件），
编译服务端时 `org.bukkit.Bukkit`、`org.purpurmc.purpur.*` 全部找不到 ——
报错会显示成上百行"程序包不存在"。

这两个坑在开发时都不容易发现：本地目录早就生成过这些文件，看起来"能编译"，
但别人 clone 下来必然失败。

---

## 运行

```bash
java -Xms4G -Xmx8G -jar Oneiric-V0.14.jar nogui
```

首次启动会在根目录生成 `oneiric.yml`，里面有全部 104 个开关的说明。

### 生电服建议先改这几项

```properties
# server.properties —— 创造飞行必开，否则悬停 4 秒就会被踢
allow-flight=true
```

```yaml
# oneiric.yml
mc-technical-mode: true              # TNT复制 / 无头活塞 / 永破 / 绊线复制
update-suppression-crash-fix: true   # 更新抑制不再崩服
```

### 常用命令

```
/oneiric                    区域并行状态、并行度损失
/oneiric line               单行状态
/oneiric switches           全部开关生效值
/counter [颜色]             羊毛漏斗计数
/bot create <名字> [皮肤名]  创建假玩家
/photographer create <id>   创建回放录制者
```

---

## 开发：补丁工作流

**这是本项目最重要的一条约定。** `Oneiric-Server/` 和 `Oneiric-API/` 是
`applyServerPatches` 的生成产物，**不在版本控制里**。真正的源码只有
`patches/server/*.patch`。

改代码的流程：

```bash
# 1. 生成源码树
./gradlew applyServerPatches

# 2. 在 Oneiric-Server/ 里改代码
cd Oneiric-Server
git add -A && git commit -m "描述你的改动"

# 3. 把 commit 固化成补丁（回到根目录）
cd ..
./gradlew rebuildServerPatches

# 4. 验证：从干净状态重放
./gradlew applyServerPatches
# 应输出 "19 patches applied cleanly"
```

**注意**：`rebuildServerPatches` 比较的是 **commit 之间**的差异。
未提交的工作区改动会被忽略，并显示 `Saved modified patches (0/N)` —— 这是最常见的踩坑点。

另有两个已知陷阱：

- `git am --abort` 会把 `Oneiric-Server` 重置到 paperweight 的初始提交，
  导致 `build.gradle.kts` 变回上游版本、Gradle 报 `project ':luminol-api' not found`。
  恢复方式：`git reflog` 找到之前的提交，`git reset --hard <sha>`。
- 任何 `applyServerPatches` 会从原版导入的文件，**必须用 modification diff**，
  不能用 new-file diff，否则会触发 `CONFLICT (add/add)`。

---

## 贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。

提交前请确认：

1. `./gradlew applyServerPatches` 干净通过
2. `./gradlew createMojmapPaperclipJar` 编译通过
3. **实际启动服务器并测试**，不只是编译通过

第 3 条不是形式主义。本项目已经因为「编译通过但跑不起来」修过三个致命 bug：
重复的配置项导致启动崩溃、命令注册参数顺序写反导致命令不存在、
`server.tell(wrapRunnable())` 在 Folia 上直接抛异常导致功能 100% 失效。

---

## 许可证

Oneiric 是 Luminol → Folia → Paper 的衍生版，全部 **GPLv3**。

- 保留所有上游版权声明与许可证文件（见 [LICENSE](LICENSE)、[NOTICE](NOTICE)）
- 移植的 Leaves 代码标注来源 `LeavesMC/Leaves (GPLv3)` 与原作者
- 每个移植文件里写明「与 Leaves 的差异」：改了什么、为什么改
- 品牌为 Oneiric，维护者 Fuchen

没有去掉 GPL、没有删除上游版权、没有把 GPL 代码说成完全自有。

Minecraft 是 Mojang Synergies AB 的商标。本项目与 Mojang Studios 或 Microsoft
无关联、未获其认可或赞助。

---

## 致谢

| 项目 | 贡献 |
| --- | --- |
| [Paper](https://github.com/PaperMC/Paper) | 性能与插件 API 地基 |
| [Folia](https://github.com/PaperMC/Folia) | 区域并行架构 |
| [Luminol](https://github.com/LuminolMC/Luminol) | 配置模块与工具层 |
| [Leaves](https://github.com/LeavesMC/Leaves) | 生电规则、协议层、假玩家、摄影师 |
| [Purpur](https://github.com/PurpurMC/Purpur) | API 设计 |
| [Canvas](https://github.com/CraftCanvasMC/Canvas) | AI 行为位集 |
| [Leaf](https://github.com/Winds-Studio/Leaf) | 寻路限制思路 |
| [LeavesX](https://github.com/chyppt/LeavesX) | 部分优化补丁参考 |
| [fabric-carpet](https://github.com/gnembon/fabric-carpet) | 规则语义参考 |
