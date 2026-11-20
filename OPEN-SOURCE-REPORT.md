# 开源就绪检查报告

生成时间：2026-11-20
目标目录：`C:\Users\Administrator\Desktop\Oneiric-开源`

---

## 一、安全检查

| 检查项 | 结果 |
| --- | --- |
| 硬编码密码 / 密钥 / token | **0 处** |
| 真实 IP / 内网地址 | **0 处**（只有上游默认的 `127.0.0.1` / `0.0.0.0`，在补丁里） |
| 绝对路径（`C:\...`、`/home/...`） | **0 处** |
| 个人用户名（Sc_hen 等） | **0 处** |
| 对话痕迹（“用户要求”“我上次…”） | **0 处** |
| 第三方 API 凭据（`secret_v2` 等） | **0 处**（含该变量的 `PushToAPI.sh` 已删除） |
| 服务器运行数据（`world/`、`logs/`、`oneiric.yml`） | **已排除** |

---

## 二、仓库内容

**39 个文件，约 1.9 MB。**

```
Oneiric-开源/
├── .github/
│   ├── ISSUE_TEMPLATE/{bug-report,feature-request}.yml   已重写为 Oneiric 版本
│   └── workflows/build.yml                               已重写（原版指向 LuminolMC 组织）
├── gradle/wrapper/                                       保留（否则无法构建）
├── licenses/Luminol-MIT.txt                              Luminol 的 MIT 部分
├── patches/server/                                       19 个补丁 = 真正的源码
├── AUTHORS.md                                            已补全上游作者
├── CHANGELOG.md                                          V0.13 → V0.14 全记录
├── CONTRIBUTING.md                                       新增（含补丁工作流说明）
├── LICENSE                                               GPLv3 完整文本
├── NOTICE                                                逐个列出上游项目
├── README.md                                             重写（GitHub 风格）
├── .gitattributes                                        新增（行尾规范）
├── .gitignore                                            重写（排除生成产物）
├── build.gradle.kts / settings.gradle.kts
├── build-version.txt                                     14
├── gradle.properties
├── gradlew / gradlew.bat
```

### 已排除（不会进仓库）

| 目录 | 体积 | 原因 |
| --- | --- | --- |
| `Oneiric-Server/` | 182.69 MB / 18060 文件 | `applyServerPatches` 生成产物 |
| `Oneiric-API/` | 20.29 MB / 3660 文件 | 同上 |
| `.gradle/` | 948.80 MB / 59359 文件 | Gradle 缓存 |
| `build/` | 587.69 MB | 构建输出 |
| `paper-api-generator/` | 0.90 MB | 生成产物 |
| `public/` | 5.66 MB | **LightingLuminol 旧品牌图片**，需重新设计 |

**这一步是最关键的**：如果不排除，一次 `git add -A` 会把 1.7 GB 上游代码推上去，GitHub 会直接拒绝，而且容易被误解为抄袭。

---

## 三、修复的问题

### 1. `.gitignore` 规则失效（严重）

原规则写的是改名前的旧名字：

```gitignore
LightingLuminol-API/*
LightingLuminol-Server/*
```

改名成 `Oneiric-*` 后这些规则**全部失效**，生成目录会裸露在 `git add .` 的范围内。
已改为 `Oneiric-API/` 与 `Oneiric-Server/`，并保留旧名规则以防从旧分支合并。

### 2. `.github/workflows` 指向 LuminolMC

原 workflow 写死了：

```yaml
run: git config --global user.email "ci@luminolmc.com"
tag: ${{ env.tag }}
uses: ncipollo/release-action@v1.14.0
```

还有 `scripts/PushToAPI.sh`（把构建 POST 到 Luminol 的私有 API，带 `secret_v2`）与
`scripts/SetENV.sh`（`project_id="lightingluminol"`）。

**全部删除并重写**。新 workflow 走标准 `gradlew applyServerPatches` + `createMojmapPaperclipJar`，
产物上传 artifact，只有打 tag 才发 Release。

### 3. Issue 模板是 Luminol 的

原模板里 `LeavesMC-version`、`LightingLuminolMC Version`、`/tpsbar` 都是上游内容。已重写。

### 4. `FUNDING.yml` 挂着 Luminol 的爱发电

已删除（如需赞助渠道，请自己填写）。

### 5. 补丁与 `gradlew` 带 CRLF（会导致构建失败）

`0001-*.patch` 有 32 处 CRLF、`gradlew` 有 244 处、`gradle-wrapper.properties` 有 6 处。

补丁文件一旦是 CRLF，`git apply` / paperweight 的 hunk 匹配会全部失败，
报错还很难懂（`corrupt patch at line N`）。`gradlew` 是 CRLF 则在 Linux/macOS 上无法执行。

已全部转为 LF，并新增 `.gitattributes` 强制规则：

```gitattributes
*.patch     text eol=lf
*.diff      text eol=lf
gradlew     text eol=lf
*.sh        text eol=lf
*.bat       text eol=crlf
*.jar       binary
```

---

## 四、许可证合规

| 要求 | 状态 |
| --- | --- |
| 完整 GPLv3 文本 | ✅ `LICENSE`（34 KB / 675 行） |
| 上游版权声明 | ✅ `NOTICE` 逐个列出 Paper / Folia / Luminol / Spigot / Leaves / LeavesX |
| Luminol 的 MIT 部分单独保留 | ✅ `licenses/Luminol-MIT.txt` |
| 移植代码标注原作者 | ✅ 文件头 + `AUTHORS.md` |
| 不把上游说成自有 | ✅ README / NOTICE / AUTHORS 三处明确写明衍生关系 |
| 商标声明 | ✅ `NOTICE` 里有 Mojang 商标声明 |

**结论：合规。**

### 关于保留的上游品牌字样

以下位置仍出现 `Luminol` / `LightingLuminol`，**都是必须保留的**：

| 位置 | 为什么保留 |
| --- | --- |
| `NOTICE` / `AUTHORS.md` | GPLv3 要求的署名 |
| `licenses/Luminol-MIT.txt` | 上游许可证原文 |
| `gradle.properties` 的 `luminolCommit` | 上游 commit 锁定，改了就没法构建 |
| `build.gradle.kts` 的 `useStandardUpstream("luminol")` | 上游仓库地址 |
| `patches/0001-*.patch` 的文件名与内容 | **这是上游 MrHua269 的原始补丁**，改名会破坏补丁链 |

最后一条特别注意：补丁 0001 的标题是 `Fix build and set serverName to LightingLuminol`，
它是从 Luminol 继承的**历史事实**，不是 Oneiric 的品牌。

---

## 五、需要你自己做的两件事

### 1. 品牌图片

`public/` 里的 12 张图全是 **LightingLuminol 的 LL 标志**，体积 5.66 MB（占原目录 73%）。
我没有复制它们，因为那是别人的品牌。

如果需要 logo，请自己做一套 Oneiric 的，放到 `public/image/` 下。

### 2. 推送前确认

```bash
cd C:\Users\Administrator\Desktop\Oneiric-开源
git remote add origin https://github.com/<你的用户名>/Oneiric.git
git branch -M main
git push -u origin main
```

推送前建议再跑一次：

```bash
git ls-files | wc -l          # 应该是 39
git ls-files | grep -i oneiric-server   # 应该没有输出
```

---

## 六、构建验证

已验证：将目录复制到 ASCII 路径后，从零执行
`gradlew applyServerPatches` + `createMojmapPaperclipJar`。

（构建日志见 `C:\oneiric_clone_test\build_apply.log` 与 `build_jar.log`）

> 注：本地路径含中文时，`git clone file:///...` 的 URL 编码会失败。
> 这不影响 GitHub 上的真实 clone，只是本地验证的限制。

---

## 七、给第一次接触这个仓库的人

`README.md` 里已经写明了最反直觉的一点：

> `Oneiric-Server/` 和 `Oneiric-API/` 是 `applyServerPatches` 的生成产物，
> **不在版本控制里**。真正的源码只有 `patches/server/*.patch`。

`CONTRIBUTING.md` 把改代码的完整流程（apply → 改 → commit → rebuild → 验证）
和三个已知陷阱都写清楚了。这是最容易让新人踩坑的地方。
