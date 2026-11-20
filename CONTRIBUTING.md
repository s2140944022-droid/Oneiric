# 贡献指南

感谢你愿意参与 Oneiric。本文档说明这个项目的特殊工作方式 ——
它和普通 Java 项目不一样，**源码不在仓库里，而是在补丁里**。

---

## 一、先理解这个项目的结构

```
Oneiric/
├── patches/server/*.patch    ← 真正的源码（19 个补丁）
├── build.gradle.kts          ← paperweight 配置
├── oneiric.yml 文档说明     ← 在补丁 0004 里
└── （构建后生成）
    ├── Oneiric-Server/       ← 生成目录，不提交
    └── Oneiric-API/          ← 生成目录，不提交
```

`Oneiric-Server/` 里的 18000 个文件是 **applyServerPatches 从上游 Folia 拉下来再打补丁的产物**，
不是本项目的源码。`.gitignore` 已经把它们排除掉了。

如果你的改动出现在 `Oneiric-Server/` 里但没有变成 `patches/` 里的补丁，
**下次别人构建时你的改动会消失。**

---

## 二、改代码的完整流程

```bash
# 1. 生成源码树（首次必须三步都执行，顺序不能变）
./gradlew applyGeneratedApiPatches   # API 自动生成源（VanillaGoal / registry keys）
./gradlew applyApiPatches            # API 层
./gradlew applyServerPatches         # 服务端层
```

**这三个都不能跳过。**

- 少了 `applyGeneratedApiPatches`：`paper-api-generator/generated/` 不存在，
  `PaperVanillaGoal` 找不到 `VanillaGoal`，8 个 `@Override` 报错
- 少了 `applyApiPatches`：`Oneiric-API/` 是空的，编译报上百行 `org.bukkit.*` 找不到

这两个坑的共同点是：本地目录早就生成过这些文件，看起来能编译，
但别人 clone 下来必然失败。

```bash
# 2. 在生成树里改代码
cd Oneiric-Server
# ……编辑 src/main/java/...

# 3. 提交到生成树自己的 git 仓库
git add -A
git commit -m "你的改动描述"

# 4. 回到根目录，把 commit 固化成补丁
cd ..
./gradlew rebuildServerPatches

# 5. 验证：从干净状态重放所有补丁
./gradlew applyServerPatches
# 输出必须是 "19 patches applied cleanly to Oneiric-Server"

# 6. 编译验证
./gradlew createMojmapPaperclipJar
```

### 如果你改的是 API 层

流程一样，但目标换成 `Oneiric-API`：

```bash
cd Oneiric-API
git add -A && git commit -m "..."
cd ..
./gradlew rebuildApiPatches
# 输出：Saved modified patches (N/M) for Oneiric-API to patches\api/
```

**这一条特别重要**：API 层的改动如果没走 `rebuildApiPatches`，
本地因为生成树里有文件所以能编译，但补丁里没有 ——
别人 clone 下来必然构建失败。

2026-11-20 就踩过这个坑：`org.purpurmc.purpur.*` 那 21 个类 +
33 个接口修改，全部只存在于本地生成树，`patches/api/` 是空的。
清理后从干净副本构建，直接报 100 个错误。


### 第 3 步最容易出错

`rebuildServerPatches` **比较的是 commit 之间的差异**，不是工作区。
如果你改了代码但没 commit，它会显示：

```
Saved modified patches (0/19)
```

这个 `0` 就是「什么都没存下来」。看到它说明第 3 步漏了。

---

## 三、两个已知陷阱

### 1. `git am --abort` 会毁掉你的生成树

如果 `applyServerPatches` 失败，**不要**直接 `git am --abort`。
它会把 `Oneiric-Server` 重置到 paperweight 的初始提交，然后你会看到：

```
Project with path ':luminol-api' could not be found in project ':oneiric-server'
```

恢复方式：

```bash
cd Oneiric-Server
git reflog                    # 找到失败前的那个 commit
git reset --hard <那个 sha>
```

**abort 之前先 `git log -1` 记下 HEAD。**

### 2. 改「上游已有的文件」不能当新文件提交

如果你要改一个上游本来存在的文件（比如 `LevelResource.java`），
在补丁里它必须是 **modification diff**（`--- a/... +++ b/...`），
不能是 **new file diff**。否则 `applyServerPatches` 会报：

```
CONFLICT (add/add): Merge conflict in src/main/java/...
```

因为它在打你的补丁之前，先从原版导入了这个文件。

判断方法：如果 `Oneiric-Server/src/main/java/` 下**构建前就有这个文件**，就是上游文件。

---

## 四、代码规范

### 注释

**这个项目的注释密度高于平均，这是刻意的，请不要削减它。**

每条重要改动都要回答三个问题：

```java
// 为什么这么改？（不是"做了什么"——代码本身说明做了什么）
// 不这么改会怎样？（具体后果，不是"会有问题"）
// 与上游的差异在哪？（如果是移植代码）
```

反面例子：

```java
// 设置 state
this.state = state;
```

正面例子：

```java
// 必须用加锁版本 computeForAllRegions，不能用 ...Unsynchronised：
// 后者明确要求"调用者已持有锁"，而状态命令是从任意线程发起的，
// 用一个未加锁的遍历去读正在被合并/分裂的 regionsById 会直接崩服。
regioniser.computeForAllRegions(region -> { ... });
```

### 移植代码

如果代码来自其他核心，必须：

1. 文件头注明来源与许可证，例如 `移植自 LeavesMC/Leaves（GPLv3）`
2. 保留原作者信息
3. 在类注释里写明「与 Leaves 的差异」清单

### 开关

新增开关必须：

1. 在 `OneiricConfig.NAMES` 登记
2. 在 `OneiricYml` 里写 4 行说明（做什么 / 为什么默认这个值 / 什么时候该改）
3. 确认 `OneiricYml.block()` 里写的默认值与 `OneiricConfig.effectiveDefault()` 一致
   （不一致会打告警）

**反向检查同样重要**：代码里 `isEnabled("xxx")` 读到的每个名字，
都必须真的在 `NAMES` 里。否则它会永远返回 false（或永远 true），
而配置里改不动，日志也不报错 —— 这是本项目踩过的坑。

---

## 五、提交前检查清单

- [ ] `./gradlew applyServerPatches` 干净通过（`N patches applied cleanly`）
- [ ] `./gradlew createMojmapPaperclipJar` 编译通过
- [ ] **实际启动服务器，验证改动真的生效**
- [ ] 新增开关已登记并写了 yml 说明
- [ ] 没有把 `Oneiric-Server/`、`Oneiric-API/`、`build/` 提交进 git

### 关于「实际启动」

第 3 条不是形式主义。这个项目已经因为「编译通过但服务器跑不起来」修过三个致命 bug：

| Bug | 症状 |
| --- | --- |
| 配置项重复 | `Set.of()` 抛异常，**启动即崩**，连世界都进不去 |
| 命令注册参数顺序写反 | `/bot` `/counter` 被注册成 `counter:oneiric`，**命令根本不存在**，且不报错 |
| `server.tell(wrapRunnable())` | Folia 上直接抛异常，**假玩家 100% 不工作** |

这三个都是编译期完全看不出问题的。请务必启动一次。

---

## 六、性能改动的额外要求

任何声称「更快」的改动，请提供：

1. **测量方法**：怎么测的、测了几次
2. **基线数据**：改动前后的 TPS / MSPT
3. **行为验证**：证明生电行为没变（哪些装置、怎么验证的）

只给「感觉更快了」的 PR 不会被合并。

参考：本项目已记录的基线是 2048 僵尸 / 16 区块压力场景。

---

## 七、不要做的事

- **不要**在优化名义下改变红石 / 活塞 / 漏斗 / 作物 / 挤压行为
- **不要**删除上游版权声明或许可证文件
- **不要**把 GPL 代码重新标称为完全自有
- **不要**提交 `server.properties`、`oneiric.yml` 运行实例、`world/` 或任何含 IP / 密码的文件
- **不要**为了「看起来支持」而给未实现的 API 写空实现 —— 抛异常更好，见下节

---

## 八、关于「明确不支持」

`OneiricPurpurCompat` 里有一类方法是**故意抛 `UnsupportedOperationException`** 的：

```java
@Override
public void addFuel(...) {
    throw new UnsupportedOperationException("Not implemented in Oneiric");
}
```

这不是偷懒。如果假装实现（比如空方法体），插件会静默失效 ——
燃料没加进去、高亮没显示，运维完全查不出原因。
抛异常至少让调用点立刻暴露，日志里能直接看到是哪个 API。

新增功能时请遵循同样的原则：**能真做的真做，做不到的明确拒绝。**

---

## 九、许可证

贡献的代码默认以 **GPLv3** 授权（与项目一致）。

如果你移植了其他项目的代码，请确认那个项目的许可证与 GPLv3 兼容，
并在文件头标注来源。
