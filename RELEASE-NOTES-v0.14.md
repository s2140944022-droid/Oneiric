# Oneiric V0.14 — 首个公开版本

面向红石与生电的 Minecraft 1.21.1 多线程服务端核心。

## 这是什么

基于 Folia 的区域并行架构，但**重构了区域调度**：Folia 按邻接关系切分区域，
不知道你的末影人农场跨了 32 格、红石线横跨 3 个区块。
Oneiric 加了跨距离耦合判定，让「必须同步的东西」一定落在同一个线程里。

同时整合了完整的生电生态：14 个客户端 mod 协议、假玩家、ReplayMod 摄影师、
Carpet 规则同步、104 项逐条说明的配置。

## 本版本包含

**区域调度（原创部分）**
- `gridExponent` 从 2（4×4 区块）改为 3（8×8 区块）
- 跨距离耦合判定：末影人 32 格 / TNT·水晶 8 格 / 玩家 8 格 / 默认 5 格
- `ThreadedRegionizer` 注入 5 处决策点：分裂否决、tick 边界主动合并、几何校验
- 并行度损失报告（合并掉多少、损失比多少）

**生电生态（移植自 Leaves，GPLv3）**
- 客户端协议 14 个：Carpet / BBOR / XaeroMap / AppleSkin / Jade / ChatImage /
  Servux×4 / Syncmatica / Bladeren / PCA
- 假玩家系统：`/bot create|remove|action|config|list|save|load`
- ReplayMod 摄影师：`/photographer create`，产出标准 `.mcpr`
- 羊毛漏斗计数器：`/counter`
- Carpet 规则 31 条已注册进客户端 HUD
- 更新抑制防崩、替代方块放置（Carpet 精确放置 / Litematica 投影）

**插件兼容**
- Bukkit / Paper / Purpur 三套 API 全部可用
- Purpur 21 个 API 类已补齐（能真做的真做，做不到的抛异常而不是假装成功）

**配置**
- 104 个开关，100% 有 4 行说明（做什么 / 为什么默认这个值 / 什么时候该改）
- 4 个开关硬禁止（`FORCED_OFF`）：`optimize-suffocation`、`optimize-entities`、
  `reduce-chunk-lookups`、`optimize-fluids` —— 它们会改变挤压/流体行为

## 安装

需要 **Java 21**。

```bash
java -Xms4G -Xmx8G -jar Oneiric-V0.14.jar nogui
```

首次启动生成 `oneiric.yml`，里面有全部 104 个开关的说明。

**生电服建议先改：**

```properties
# server.properties
allow-flight=true
```

```yaml
# oneiric.yml
mc-technical-mode: true
update-suppression-crash-fix: true
```

## 从源码构建

```bash
./gradlew applyApiPatches      # 必须，不能跳过
./gradlew applyServerPatches
./gradlew createMojmapPaperclipJar
```

首次构建约 45 分钟（要拉取并反编译上游）。详见 README。

## 已知限制

| 项 | 说明 |
| --- | --- |
| REI 配方同步 | 1.21.1 没有 `crafting.display.*` API，做不了 |
| 异步寻路 / CPU 亲和性 | 未实现，改动面大且无源码可参照 |
| 逐区 MSPT | 内核未暴露 tick 结束时刻，宁可不报也不报错数 |
| 多区域并发压力测试 | **未完成** —— 本地被合并成单区，无法复现。需要真实负载反馈 |

最后一条尤其希望有人帮忙验证：如果你在多区域负载下发现问题，请开 issue。

## 许可证

GPLv3。衍生自 Paper / Folia / Luminol，移植了 Leaves 的生电生态。
上游版权与许可全部保留，详见 `LICENSE`、`NOTICE`、`AUTHORS.md`。

Minecraft 是 Mojang Synergies AB 的商标，本项目与其无关联。
