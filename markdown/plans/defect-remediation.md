# 缺陷修复（第二轮）Implementation Plan

> Status: APPROVED
> Source: user request（"检测当前项目的潜在缺陷，并计划修复"）
> Mode: (default) — 完整 Planner → Architect → Critic loop
> Iterations: 2 / 3
> Author: user
> Last updated: 2026-10-16

## Requirements summary

对当前工作区（HEAD `0dabb0c` + ~2000 行未提交改动）做一次**独立于**既有报告的缺陷复检，产出**按严重度分级、带 file:line 证据**的缺陷清单，并给出可执行的修复方案。

既有 `markdown/modmaker-defect-report-verified.md`（2026-10-08）已核实 **H1–H7 / M1–M20 / M22 / M24–M29 / L1–L7 全部修复**（§8）。因此本 plan **只覆盖该报告之后新浮现或遗留的缺陷**，不重复已修条目。本轮不做大范围重构，只做外科手术式修复 + 回归测试。

已明确排除（用户 2026-10-16 决定）：运行期内存占用上涨（疑似加载大型模组导致）——不再投入修复。

## 决策记录（用户 2026-10-16 确认，覆盖 Planner/Critic 的默认取舍）

| 议题 | 用户决定 | 对 plan 的影响 |
|---|---|---|
| 本轮范围 | **P0 + P1（D1–D8）** | P2（D9–D13）**移出本轮**，降为 Follow-up；AC 收敛到 AC-1..AC-8（AC-9 保留为全量回归门槛） |
| 工作区隔离 | **`git reset --soft HEAD~1` 合并上一个重复提交，以当前状态为主源头重新提一个基线** | 见 §Workspace setup；已执行，未丢任何内容 |
| D1 并发方案 | **Option B（结构性）** —— 否决 Planner 默认的 Option A | D1 实施步骤与 ADR 全部改写为 Option B；Architect 对 Option A 的 steelman 成立（"约定式锁 = 复现 D1 成因"） |
| D8 快捷键 | **改成 `Ctrl+Y`** | 只改 `AppShell.java:483`，同步文档 |

## Acceptance criteria

- AC-1 并发保存：`ProjectController.save()` 在后台线程执行的同时，FX 线程以最小间隔反复调用 `setField`/`addContent`，1000 次循环内 0 次 `ConcurrentModificationException`；保存后 `ProjectIo.open` 读回的记录数与内存一致。
- AC-2 打包原子性：令 `ModExporter.buildZip` 在中途抛 `IOException`，断言目标路径上**原有 zip 文件字节与调用前完全一致**，且不留 `*.mm-tmp` 残留。
- AC-3 覆盖确认：目录列举失败时 `approveOverwrite` 返回 `false`（要求用户确认），不再静默通过。
- AC-4 撤销完整性：`disconnect` 的"目标记录已不存在"分支删除边后，`controller.undo()` 能恢复该边（`board.edges.size()` 回到删除前）。
- AC-5 `sectorSize` 上界：`PlanetGrids.tileCount(20)` 不再返回负数；`Validator` 对 `sectorSize: 20` 报出明确的超限 issue，而不是一个无意义的扇区数。
- AC-6 meta 容错：`ModMetaCodec.fromJson({"minGameVersion": true})` 不抛异常，返回 fallback 且该键保留在 `extra`。
- AC-7 i18n 完整：两套 `messages*.properties` 中 `strings.filter.prompt` 与 `strings.missing` 均存在（用例断言 `Messages.t(key)` 不等于 key 本身）。
- AC-8 CSS 作用域：`.center-tabs` 的 tab 尺寸规则不再命中嵌套的 `.preview-tabs .tab`（用例断言预览页签的 tab 尺寸 ≠ 96×48）。
- AC-9 全量回归：`gradlew test` 全绿，用例数 ≥ 239（本轮新增用例计入）；便携包 `dist\ModMaker\app\*.jar` 重新构建后可启动、UI 外观无回退。

## Planner draft

### Principles

1. 跟随 baseline「最小代码」：每条缺陷用最小 diff 修，能不加抽象就不加。
2. 跟随 baseline「外科手术式改动」：只碰缺陷点，不顺手重构邻近代码；发现的其他问题只登记在 §Follow-ups。
3. 只修**有 file:line 证据且已复核**的缺陷；未复核项一律不进本 plan（不假设）。
4. 每条修复必须配一个**能先红后绿**的回归用例（baseline「可验证成功标准」）。
5. 不改变对外行为契约（除缺陷本身）：不引入新配置项、不做兼容 shim。

### Decision drivers

1. **数据不可逆性**（P0 优先）：D1/D2/D3 会造成用户数据丢失或损坏，且由常规操作触发。
2. **改动风险**：当前工作区已有 ~2000 行未提交改动，任何大范围重构都会让 diff 不可审、不可 bisect。
3. **回归成本**：项目已有 239 个全绿用例 + 金标往返夹具，新改动必须能挂进现有测试骨架。

### Viable options

**Option A：按条外科修复 + 一个共享写锁（favored）**
- 实现思路：每条缺陷就地最小修改；只有 D1 需要引入跨线程不变量 —— 在 `ProjectController` 增加一把模型锁，所有模型写方法持锁，`save()` 在锁内**只做快照**、把实际文件 I/O 放到锁外。
- 改动文件：`ui/ProjectController.java`、`core/io/ProjectIo.java`、`core/io/ModExporter.java`、`ui/AppShell.java`、`core/validate/Validator.java`、`core/fmt/PlanetGrids.java`、`core/io/ModMetaCodec.java`、`ui/StringsPane.java`、`src/main/resources/messages*.properties`、`src/main/resources/app.css`。
- Pros：diff 可控（每条 5–30 行）；AFK 快照思路不改变"后台保存不冻结窗口"这一现有收益；每条缺陷都有独立回归用例，可分批提交。
- Cons：锁是一个"约定式"不变量 —— 以后新增的模型写方法若忘记持锁，缺陷会复发（见 Architect challenge）。

**Option B：把"序列化"整体搬回 FX 线程，io 线程只做写入**
- 实现思路：FX 线程产出不可变的"写盘计划"（path → text/bytes 列表），io 线程只执行 `Files.write`；`record.raw/dirty/rawPath` 的回写改成 io 完成后 `Platform.runLater` 合并。
- 改动文件：`core/io/ProjectIo.java`（拆 `save` 为 `plan` + `write`）、`ui/ProjectController.java`、`ui/AppShell.java`。
- Pros：把"模型只归 FX 线程"变成**结构性**不变量，不需要锁，也不会有遗忘持锁的隐患。
- Cons：改动面大（`ProjectIo.save` 是全项目写盘唯一入口，牵连 import/convert/build 三条调用链）；`record.dirty=false` 等回写时机变化会波及 `DirtyFlags`/`isDirty` 语义（既报告 H4 刚修好）；风险高于收益。

**Invalidation rationale（为什么砍 C 之外只剩 A/B）**
- 曾考虑 **Option C：io 忙时禁用编辑（busy guard）**。砍掉的理由：`runIo` 无法预知保存时长，忙碌期拒绝输入会让用户"敲了字却被吞"，与项目的 `dirty` 契约（H4）方向相反；且它只掩盖竞态，不消除。

### Implementation steps（基于 Option A）

**批次 P0 — 数据完整性**

1. **D1 模型并发（Option B：FX 线程序列化 / io 线程写盘）** — `core/io/ProjectIo.java`、`ui/ProjectController.java`、`ui/AppShell.java`
   - `ProjectIo.save(project, root)`(`core/io/ProjectIo.java:136-146`) 拆成两段：
     - `ProjectIo.SavePlan plan(ModProject project, Path root)` —— **必须在 FX 线程调用**。把 `writeMeta`(`:148`)/`writeContent`(`:176`)/`writeBundles`/`writeAuthoringLayer` 的**文本生成部分**抽出来，产出一个不可变计划：`List<WriteOp>{Path target, String text|byte[] bytes}`、`Set<Path> expectedContent`（供 prune）、`List<Path> deletes`（`previousMetaFile` `:170-171`、改名后的旧文件 `:212`、`pruneManaged` `:142` 的候选）。模型回写（`record.raw/rawPath/dirty` `:208-210`、`project.metaText/metaDirty/previousMetaFile` `:166-173`、`bundleDirty`）全部在 `plan` 内就地完成 —— io 线程**不再触碰模型**。
     - `ProjectIo.write(SavePlan plan)` —— 只做 `createDirectories`/`writeText`/`Files.write`/`deleteIfExists`/`pruneManaged` 的实际 I/O。
   - `ui/ProjectController.java`：`save()`(`:395-400`)、`convertFormat()`(`:407-414`)、以及 build 路径的 `:1521`/`:1530` 改为"FX 线程 `plan` → io 线程 `write`"。`ProjectController` 暴露 `SavePlan planSave()` 与 `void writeSave(SavePlan)` 两个方法；`AppShell.save()`(`:852-854`) 与 build/convert 的 `runIo` 调用点(`:874`/`:902`/`:929`/`:964`) 重新排成两步（`plan` 阶段用现有 `Platform.runLater` + `CountDownLatch` 模式回到 FX 线程，参见 `approveOverwrite` `:1071-1081`）。
   - 保留 `ProjectIo` 的 `synchronized`(若仍保留静态入口) 以防 save-vs-save。
   - **明确不做（Follow-up B2）**：`openProject`/`importPackage`(`:369-391`) 仍在 io 线程替换 `project` 引用与 `clearHistory()`；当前靠 `fireProjectChanged()`(`:1576-1588`) 的 `runLater` 兜底，UI 安全，但"模型引用赋值不在 FX 线程"这一条不在本轮范围。
2. **D2 打包原子性** — `core/io/ModExporter.java:118-142`
   - 写入目标改为同目录临时文件 `zipFile + ProjectIo.TEMP_SUFFIX`，成功后 `Files.move(tmp, zipFile, REPLACE_EXISTING, ATOMIC_MOVE)`；失败在 `catch` 内 `Files.deleteIfExists(tmp)` 后重抛。
   - 复用已存在的 `ProjectIo.TEMP_SUFFIX` 常量（避免再引入一套命名）；确认 `ModPackageReader`(`:37-39`) 已把该后缀视为噪声。
3. **D3 列目录失败被当作空目录** — `ui/AppShell.java:1056-1066`、`ui/ProjectController.java:438-444`
   - `approveOverwrite`：`catch (IOException)` 分支改为 `return false`（走确认对话框），而不是 `return true`。
   - `isDirectoryEmpty`：`catch (IOException)` 改为 `return false`（"非空"→ 触发确认），去掉"失败即空"的语义。
4. **D4 删边不可撤销** — `ui/ProjectController.java:850-857`
   - 早退分支改为与主分支同形：捕获旧状态 → `pushCommand(...)`（undo 里 `board.edges.add(edge)` 并 `restoreBefore()`），不再裸 `edges.remove` + `fireProjectChanged`。

**批次 P1 — 静默错误与误报**

5. **D5 `sectorSize` 溢出** — `core/fmt/PlanetGrids.java:58-64`、`core/validate/Validator.java:882`
   - `pow3` 改为 `long` 内部计算并在 `tileCount/cornerCount/edgeCount` 内做溢出保护（超过 `Integer.MAX_VALUE` 时钳到上限）；`capacity`(`:277`) 同步。
   - `Validator`(`:869-882`) 对 `size` 增加上界检查（游戏 `Vars.maxSectorSize` 量级），超出即报 error，不再喂给 `capacity`。
6. **D6 meta 非数字字段毁掉整份 meta** — `core/io/ModMetaCodec.java:107-120`
   - `string/bool/number` 三个取值函数改为先判 `isString()/isBoolean()/isNumber()`（或 `getAsJsonPrimitive().isNumber()`），形状不符即返回 fallback，并保留该键在 `extra`（与 `KNOWN` 集合协作）。
7. **D7 缺失 i18n 键** — `ui/StringsPane.java:47`、`:123`；`src/main/resources/messages.properties`、`messages_zh_CN.properties`、`messages_en.properties`
   - 补 `strings.filter.prompt`、`strings.missing` 三条（两套语言 + 英文兜底一套），文案与现有条目风格一致。
8. **D8 重做快捷键不一致** — `ui/AppShell.java:483`
   - 用户已定：改为 `Ctrl+Y`（`keyCombination("Ctrl+Y")`），并同步更新文档中对该快捷键的描述，不得只改代码。

**批次 P2 — 清理（本轮不做，见 Follow-ups）**

9. **D9** `ui/PlanetPreview.java:142` `dispose()` 无调用者：在 `CosmosPane`(`:31`) 或工作区切换处调用，或删除该 API 与其 javadoc 承诺（二选一，不留假契约）。
10. **D10** `src/main/resources/app.css:945` 把 `.center-tabs .tab` 改成直接子选择器（`.center-tabs > .tab-header-area > .headers-region > .tab`），消除对嵌套 `.preview-tabs` 的泄漏；同步修正 `:952` 的注释。
11. **D11** 删除死规则 `.panel-title`(`:442`)、`.color-swatch`(`:552`)、`.field-value`(`:670`)、`.tool-button.key/.danger/.mini`(`:250/257/262/267/271`) 与冗余 `.center-tabs .tab:selected .tab-label`(`:975`)。
12. **D12** 删除死代码 `ui/AppShell.java:1360-1362` `isDirectory(Path)`；修正 `:565` 误导注释。
13. **D13** `ui/AppShell.java:1165` 启动的 `Process` 增加 `destroy()`/超时回收；`ioExecutor`(`:1287`) 在窗口关闭时 `shutdownNow()`（现为 daemon，影响极小，列为最低优先）。

（登记但**不修**：`ui/AppShell.java:61` `BOTTOM_STRIP_VISIBLE=false` —— 属用户已知的有意开关（见项目记忆），本轮不动。）

### Workspace setup

- 实施前运行 `git status --short` 与 `git branch --show-current`。**已运行**：当前分支 `main`，工作区 dirty（104 项改动 + 未跟踪文件）。
- **用户已裁决（2026-10-16）**：不走 worktree，改为把上一个重复提交合并进来、以当前状态为主源头重提一个基线。**已执行**：
  1. `git reset --soft HEAD~1` —— HEAD `0dabb0c` → `d023700`，`0dabb0c` 的改动全部留在暂存区，**文件内容零丢失**（HEAD 与 `d023700` 原为同一条消息的重复提交）。
  2. `.gitignore` 追加 `bin/`、`dist/` —— 这两项原先未被忽略：`dist/` 是 58 MB 的便携包（含整套 JRE），`bin/` 是 1.2 MB 编译产物，直接提交会把可执行包塞进仓库。
  3. 全量 `gradlew test --rerun` 确认基线全绿后再提交为单一基线 commit。
- 本轮 P0+P1 的改动**在此基础上继续**，并按缺陷分批提交（D2/D3/D4/D5/D6+D7/D8/D1 各自独立可回滚）。
- 仍遵守用户长期约束：除上述基线 commit 外，**本轮改动的提交须经用户确认**。

### Open questions

1. D1 的锁方案：是否接受"约定式"不变量（新增写方法需记得持锁）？若不接受，则需走 Option B（结构性但改动大）——请用户裁决。
2. D8：改代码为 `Ctrl+Y`，还是保留 `Ctrl+Shift+Z` 而改文档？
3. P2 是否纳入本轮？（纯清理，可与 P0/P1 分开提交）
4. D3 的"确认对话框"是否会打断批量导入流程（导入时列目录失败的频率）？若体验不可接受，需改为"仅记录警告 + 保守跳过"。

## Architect challenge

### Steelman against favored option

- **反方核心论点**：Option A 把"模型只归 FX 线程"这条不变量降级成了一把**靠人记住**的锁，这恰恰是缺陷 D1 的成因（`ProjectIo.save` 的 `synchronized` 也是一把"以为够了其实没够"的锁）。用同一种手段（约定）去修由约定失效导致的缺陷，等于没修：下一个新增 `setXxx` 的开发者（或未来的 AI 编辑）不会知道要持锁，D1 会以相同形态复发。Option B（FX 线程序列化 / io 线程写入）把不变量交给**结构**而不是记忆，且顺带消除 `raw/dirty` 的跨线程回写。
- **若反驳成立，plan 应改成**：D1 单独走 Option B 的子集 —— 只把 `ProjectIo.writeContent/writeMeta/writeBundles` 的**文本生成**（`serialize`/`HjsonWriter.write`）在 FX 线程完成，其余 I/O 留在 io 线程；`record.dirty/raw` 由 FX 线程在生成时就地更新。其余 P0/P1 条目仍走 Option A 的最小修。

### Tradeoff tensions

1. **结构性正确 vs 改动风险**：Option B 让不变量不可遗忘，但 `ProjectIo.save` 是全项目唯一写盘入口，牵连 `open`(`:118`)、`convertFormat`、`buildZip` 三条链；而工作区已有 ~2000 行未提交改动，一次大重构会让 diff 不可审、无法 `git bisect`。
   → Planner 取舍：**P0/P1/P2 走 Option A**；D1 折中 —— 锁 + **一条防回归用例**（并发压力测试）作为"遗忘持锁"的显式护栏，并在 `ProjectController` 类注释中写明该不变量。若用户/评审认为该护栏不足，再升级到 Option B（已作为 Follow-up 记录）。
2. **保存不冻结窗口 vs 数据一致性**：任何"锁住 FX 线程直到保存完成"的方案都会把后台保存的收益还回去（大模组保存数秒 → 窗口卡死）。本 plan 通过"持锁只做快照、I/O 在锁外"同时保住两者；代价是快照需要一次深拷贝（脏记录数量级可控）。

### Synthesis path

采纳折中：D1 = Option A 的"锁内快照 + 锁外写入"，并**强制**配 AC-1 的并发压力用例；其余条目保持最小修。若 AC-1 用例在实现中发现难以稳定构造，则退回 Option B 的子集（仅文本生成上 FX 线程）。

## Critic verdict

| 维度 | 状态 | 备注 |
|---|---|---|
| Principle consistency | ✓ | 每条都有 file:line、都有回归用例；未把内存问题混进来 |
| Alternative exploration | ✓ | A/B/C 均为真候选，C 有明确砍除理由；B 有 steelman 与升级路径 |
| Risk mitigation clarity | ✓ | Risks 表逐条对应 mitigation；D1/D2/D3 都有"失败即不可逆"的专门缓解 |
| AC testability | ✓ | AC-1..AC-9 均为二值判定（0 次异常 / 字节一致 / 返回 false / 尺寸 ≠ 96×48 / 用例全绿） |
| Verification concreteness | ✓ | 给出 `gradlew test` 命令、新增用例名、便携包重建步骤 |
| File/line coverage | ✓ | Implementation steps 全部带 file:line（13 条缺陷无一例外） |
| Pre-mortem present | n/a | 非 deliberate 模式；不额外强加 |
| Expanded test plan present | n/a | 同上 |

### Verdict: REVISE（v1）→ APPROVED（v2）

v1 拒收原因：D1 只写了"给所有写方法加锁"，但没写**锁的粒度与 I/O 位置**，读者无法判断是否会冻结窗口；且未给出"遗忘持锁"的护栏。v2 补入"锁内快照 / 锁外写入"与 AC-1 并发压力用例后通过。

### Reservations

- 对 D1：即便有 AC-1，`modelLock` 仍是**约定式**护栏；`ProjectController` 之外（如 `RecordInspector` 直接 `record.fields` 的结构化编辑器修改，见 `setField` 的 `mutable` 分支 `:1220-1224`）若绕过 controller 直接改 `fields`，锁同样无效。Mitigation 段只覆盖了 controller 内的入口。→ 已记入 Follow-ups，建议后续做一次"谁在直接改 `record.fields`"的专项扫描。
- 对 D2：`ATOMIC_MOVE` 在跨卷时会抛 `AtomicMoveNotSupportedException`；本 plan 假定临时文件与目标同目录（同卷），实现时必须显式保证，否则退化为 `REPLACE_EXISTING` 非原子移动。
- 对 D5：本 plan 只是"钳位 + 报错"，未与游戏 `Vars` 的真实上限逐一核对；若游戏实际允许的上限与本 plan 的常量不一致，会产生新的误报。实现前需先读一遍 `Mindustry-160.5` 的扇区尺寸上限。
- 对 D7：AC-7 只断言"键存在"，不断言译文质量；`messages_en.properties` 与 zh 的语义一致性靠人工复核。

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| D1 锁粒度太粗导致保存冻结窗口 | 强制"锁内只做快照、I/O 在锁外"；AC-1 用例同时断言 FX 线程在保存期间的可响应性（保存期间 `setField` 的调用耗时上限） |
| D1 未来新增写方法忘记持锁 | 在 `ProjectController` 类注释写明不变量；AC-1 压力用例常驻；登记 Follow-up 专项扫描 |
| D2 跨卷 `ATOMIC_MOVE` 不支持 | 临时文件与目标同目录（复用 `ProjectIo.TEMP_SUFFIX`）；`catch AtomicMoveNotSupportedException` 退化为非原子 move 并记录日志 |
| D2 崩溃残留 `*.mm-tmp` | `ModPackageReader`(`:37-39`) 已把该后缀视作噪声，构建(`ModExporter.modFiles`)已拒绝打包，无需新逻辑 |
| D3 改保守后打断批量导入 | 若 Open question 4 判定体验不可接受，改为"记录警告 + 不覆盖只跳过" |
| D5 常量与游戏真实上限不符引入新误报 | 实现前读 `Mindustry-160.5` 扇区上限；用例同时覆盖边界值 |
| D6 放宽后掩盖真实的 meta 错误 | 形状不符的键进 `extra` 保留原文，不丢弃；用例断言往返后该键仍在 |
| P2 的 CSS 改动影响既有视觉 | 只改选择器作用域/删死规则；跑 UI 快照用例 + 便携包肉眼复核 |
| 与既有 ~2000 行未提交改动互相污染 | Workspace setup 强制 worktree 或用户确认；按缺陷分批提交 |

## Verification steps

- AC-1：`$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.12.1'; .\gradlew.bat test --tests "dev.modmaker.ui.ProjectControllerConcurrencyTest"`
- AC-2：`.\gradlew.bat test --tests "dev.modmaker.core.io.ModExporterAtomicityTest"`
- AC-3/AC-4：`.\gradlew.bat test --tests "dev.modmaker.ui.ProjectControllerInteractionTest"`
- AC-5：`.\gradlew.bat test --tests "dev.modmaker.core.validate.ValidatorPrecisionTest"`（新增 `sectorSize` 溢出边界用例）
- AC-6：`.\gradlew.bat test --tests "dev.modmaker.core.io.*Meta*"`
- AC-7：`.\gradlew.bat test --tests "dev.modmaker.ui.UiSmokeTest"`（新增"两套语言键完整"用例）
- AC-8：`.\gradlew.bat test --tests "dev.modmaker.ui.UiSmokeTest"`（新增嵌套预览页签尺寸用例）
- AC-9：全量 `.\gradlew.bat test`（期望 ≥ 239 用例全绿）；`.\make-portable.bat` 重建 `dist\ModMaker\app\*.jar` 后双击 `run.bat` 启动，肉眼复核标题栏/选项卡/资源页外观无回退。

## ADR

- **Decision**：P0+P1 按条外科修复（D2–D8 走最小 diff）；**D1 采用 Option B** —— `ProjectIo.save` 拆成"FX 线程 `plan`（生成文本 + 就地回写模型）/ io 线程 `write`（纯文件 I/O）"，使"模型只归 FX 线程"成为**结构性**不变量，不再依赖锁。
- **Drivers**：数据不可逆性（P0 优先 D1–D4）；数据一致性优先于"保存不冻结窗口"（用户明确选择结构性方案，接受更大的改动面）；回归成本（每条都挂现有测试骨架）。
- **Alternatives considered**：
  - Option A（按条最小修 + `ProjectController` 模型锁）——**rejected**：Architect 的 steelman 成立 —— 锁是"靠人记住"的约定，与 D1 的成因（`ProjectIo.save` 的 `synchronized` 也是一把"以为够了其实没够"的锁）同构，等于用同种手段修同种缺陷；用户裁决采用 Option B。
  - Option B（FX 线程序列化 / io 线程写盘）——**chosen**（本轮做 B1 子集：save/convert/build 的写盘路径）。
  - Option C（io 忙时禁用编辑）——**rejected**：掩盖竞态、吞用户输入，与 H4 的 dirty 契约方向相反。
- **Why chosen**：把不变量交给结构而不是记忆；顺带消除 `record.raw/dirty/rawPath`、`metaText/metaDirty` 的跨线程回写；代价是 `ProjectIo` 需要一次拆分，由分批提交与全量回归兜住风险。
- **Consequences**：
  - 正面：P0 三类不可逆数据损失被消除（D1 从根上消除竞态，D2/D3 消除原子性与覆盖风险）；P1 的静默错误/误报消除；每条都有回归护栏。
  - 负面：`ProjectIo` 的入口从 1 个变 2 个（`plan`/`write`），调用方必须**成对**调用且 `plan` 必须在 FX 线程 —— 这是一处新的、需要文档化的契约（写进 `ProjectIo` 类注释）；`openProject`/`importPackage` 的模型引用赋值仍留在 io 线程（Follow-up B2）。
- **Follow-ups**（本次明确不做）：
  1. **B2**：`openProject`/`importPackage`(`ProjectController.java:369-391`) 的"替换 `project` 引用 + `clearHistory()`"也回到 FX 线程，彻底做到"模型只归 FX 线程"。
  2. 专项扫描"绕过 `ProjectController` 直接修改 `record.fields`"的调用点（`RecordInspector` 结构化编辑器、`MeshEditor`）——既然不再有锁，这条不再是并发问题，但仍影响 `plan` 阶段的一致性假设。
  3. P2 清理项 D9–D13（死 CSS / 死代码 / `.center-tabs` 选择器作用域 / 快捷键文档剩余项）。
  4. `AppShell.BOTTOM_STRIP_VISIBLE=false`（`:61`）恢复底栏（校验结果/log 面板目前不可见）——属用户已知开关，另开任务。
  5. 运行期内存上涨的复测（用户已决定本轮搁置）。

## 实施记录（2026-10-16）

| 缺陷 | 状态 | 落地内容与偏差 |
|---|---|---|
| D1 | 已实施（含 1 处计划外补充） | `ProjectIo.save` 拆为 `plan(project, root)`（FX 线程：生成文本 + 就地回写 `raw/dirty`）/ `write(SavePlan)`（io 线程：纯文件 I/O，写入 → 删除 → 剪枝）。`AppShell` 新增 `onFxThread(Callable)` 与 `writePlan(...)`；`save()`、格式转换都改为「FX 线程 plan → io 线程 write → FX 线程收尾」。<br>**计划外补充**：拆分本身会开一个新的数据丢失窗口 —— `plan` 已把 `dirty/metaDirty/bundleDirty` 全部清掉，若 `write` 随后失败，模型会"看起来已保存"，下次保存跳过这些文件、退出守卫也不再提示。因此 `SavePlan` 额外快照了这三类标记（按记录对象而非下标索引，避免写入期间增删记录导致错位），并提供 `rollback()`；`AppShell.writePlan` 在写入失败时先回 FX 线程 rollback 再抛错。回归：`ProjectIoSavePlanTest`（写阶段不读模型 / plan 不写盘 / 失败可回滚）。<br>**仍未覆盖（Follow-up B2）**：`build`（`Validator` + `buildZip` 只读模型）与 `open`/`import`（在 io 线程替换 `project` 引用）未纳入本轮。 |
| D2 | 已实施 | 打包改为同目录 `.mm-tmp` + `ATOMIC_MOVE`（不支持则退化为 `REPLACE_EXISTING`），失败时清临时文件并保留上一版产物。回归：`ModExporterAtomicityTest`。 |
| D3 | 已实施 | 两处「列目录失败即视为空目录」改为保守语义：`approveOverwrite` 回落到确认对话框，`isDirectoryEmpty` 返回 `false`。回归：`ProjectControllerInteractionTest.anUnlistableDestinationIsPutToTheApproverInsteadOfBeingOverwritten`（用 ACL 撤销读权限稳定复现，无 ACL 平台自动跳过）。 |
| D4 | 已实施 | `disconnect` 的"目标记录已不存在"分支改为压入可撤销命令，与主分支同形。回归：`ProjectControllerInteractionTest.disconnectingAnEdgeWhoseTargetRecordIsGoneIsUndoable`。 |
| D5 | 已实施 | `pow3` 改为饱和长整数运算，`tileCount/cornerCount/edgeCount/capacity` 不再溢出；新增 `PlanetGrids.isRepresentable` / `MAX_SECTOR_SIZE = 16`（由 `30 x 3^n <= Integer.MAX_VALUE` 推导，非臆造）；`Validator` 超出即报 `validate.planet.sectorSizeTooLarge`（error），并在扇区计数处跳过以免产生无意义告警。 |
| D6 | 已实施（范围收窄） | 只有 `number()` 会抛：`string()`/`bool()` 的 Gson 取值对任意 primitive 都能强制转换，不会抛异常。因此只修 `number()`（先判 `isNumber()`，引号数字仍接受，其它形态回落默认值），保持最小改动。 |
| D7 | 已实施 | `messages.properties` + `messages_zh_CN.properties` 补 `strings.filter.prompt`、`strings.missing`。注意 `messages_en.properties` 是**故意留空**的兜底文件（其头部注释说明原因），所以不需要新增英文文件。 |
| D8 | 已实施（无需改文档） | 代码改 `Ctrl+Y`。核查文档后发现现有 `markdown/02-架构/architectureCN_ZH.md:198` 与英文版本来就写 `Ctrl+Y`，即代码原本是唯一的不一致源；仅 `markdown/03-历程/存档/项目笔记.md:21` 提及 `Ctrl+Shift+Z`，那是历史存档记录，不作为文档口径修改。 |
| D9–D13 | 未实施 | 按用户决策移出本轮，见 Follow-ups。 |

**验证**：`gradlew test --rerun` 全量结果见下节；便携包需在收尾时用 `make-portable.bat` 重建。

### 验证结果

- **全量回归**：`gradlew test --rerun` —— **286 用例 / 0 失败 / 0 跳过**（基线 279，本轮新增 7 条：D1 三条、D2 两条、D3/D4 各一条）。
- **关键回归**（D1 动的是全项目唯一写盘入口，必须看这几条）：
  - `RoundTripTest.realModRebuildsByteForByte` —— 真实模组 `import -> 项目文件夹 -> build` 仍**逐字节一致**（missing=0 / different=0 / extra=0）；
  - `AuthoringTest.aNewProjectBuildsACompleteLoadablePackage`、`ProjectIoDataSafetyTest.*`（双 meta 采纳、扩展名大小写改名不误删、注释保留）、`StabilityTest.repeatedZipBuildsAreIdentical`、`ModExporterAtomicityTest.*` 全部通过。
- **红验证（抽样）**：临时把 D4 的修复还原为裸 `board.edges.remove(edge)` 后重跑 `ProjectControllerInteractionTest`，`disconnectingAnEdgeWhoseTargetRecordIsGoneIsUndoable` 在 `ProjectControllerInteractionTest.java:122` 的 `assertEquals(1, edges.size())` 断言上失败（`AssertionFailedError`）；恢复修复后通过 —— 证明该用例不是空转。（D1/D2/D5/D6 的用例引用的是新增 API 或新语义，在基线上无法编译，因此其"红"态表现为编译失败，不作为可比对的证据。）
- **便携包**：`gradlew jar` 重建后覆盖 `dist\ModMaker\app\mindustry-modmaker-0.1.0.jar`；用包内 `runtime\bin\java.exe -cp "app\*" dev.modmaker.Launcher --snapshot=...` 验证，退出码 0、快照 31 KB。肉眼复核布局无回退：36px 标题栏内含 7 个菜单按钮与右上角窗口控制按钮，工作区选项卡（资源管理/方块构筑/单位构筑/星球战役/科技研究/扩展脚本）与次级芯片行（物品/方块/液体/状态/单位/天气/区块/行星/队伍 + 添加/删除）均为 48 高。

## 实施记录（第二轮：P2 清理 + B2 结构性收尾）

| 项 | 状态 | 落地内容与偏差 |
|---|---|---|
| D9 | 已实施 | 删除 `PlanetPreview.dispose()`、`disposed` 字段以及场景监听里的守卫。`CosmosPane` 是 `AppShell` 的 final 字段、没有任何丢弃点，所以这是无人调用的假契约；真正管理 ticker 的一直是 `sceneProperty` 监听（detach 即 `pause()`）。 |
| D10 | 已实施 | `app.css` 中 6 条 `.center-tabs X` 规则全部改为 `.center-tabs > .tab-header-area > .headers-region > .tab ...` 形式：96×48 的单元格不再强加到嵌套的 `.preview-tabs` 上，也不再依赖"后面的规则覆盖前面的"这种顺序巧合；同步修正说明注释。 |
| D11 | 已实施 | 删除 8 条死规则（`.panel-title`、`.color-swatch`、`.field-value`、`.tool-button.key`/`.key:hover`/`.danger`/`.danger:hover`/`.mini`）与冗余的 `.center-tabs .tab:selected .tab-label`——这些 style class 在全部 Java 源码中都没有被添加过。 |
| D12 | 已实施 | 删除死代码 `AppShell.isDirectory(Path)`（无调用者）；把 `:566` 的「action row above」注释改为与实际相符的说明（构建/测试运行仍在工具栏，校验/保存/格式切换在菜单与标题栏）。 |
| D13 | 已实施一半，另一半复核后不修 | 新增 `AppShell.shutdown()` 并在 `App.stop()` 里调用 `ioExecutor.shutdownNow()`：退出时不再留有排队中的写盘任务。**Process 回收经复核后不修**——`ProcessBuilder.start()` 返回的 `Process` 由 JDK 的 ProcessReaper 在子进程退出后释放句柄，即使引用立刻不可达也不会泄漏；反过来，立刻 `close()` 它的三个流有可能让 Unix 上的 `xdg-open` 收到 broken pipe，风险大于收益。 |
| B2a | 已实施 | `openProject` 拆为 `readProject`（io 线程：读包 + 解析）/ `publishProject`（FX 线程：赋值 + `clearHistory` + drift 检查 + 通知）；`importPackage` 拆为 `readImport` + `ImportResult` / `publishImport`。`AppShell` 的打开与导入改为「io 读取解包 → FX 发布」；同步入口 `openProject`/`importPackage` 保留给 CLI 启动（`App.loadFromArguments`）与测试。 |
| B2b | 已实施 | `ModExporter.buildFolder/buildZip` 增加 `(root, internalName, destination, issues)` 重载（`requireRoot`/`requireInternalName` 转 public 供 FX 半调用）；`ProjectController.planBuild()`（校验 + 目标值 + 保存计划）跑在 FX 线程，`writeBuildFolder`/`writeBuildZip` 跑在 io 线程；新增 `writeSavePlan` 统一「写失败即回滚」，`save`/`convertFormat`/两条 build 路径共用。 |

**结果**：至此「模型只归 FX 线程」覆盖 **save / convert / build / open / import** 五条链路——io 线程上只剩纯文件 I/O；原 Follow-up B2 已完成。仍未做（按用户决定搁置）：`AppShell.BOTTOM_STRIP_VISIBLE=false` 恢复底栏、运行期内存上涨复测。

## Review trail

- Planner draft v1：按条最小修 + 共享锁；D1 只写"加锁"未写粒度 → Critic REVISE。
- Architect challenge v1：steelman 指出"约定式锁 = 复现 D1 成因"，建议 D1 走 Option B 子集；给出"结构性正确 vs 改动风险"这条 tension。
- Critic verdict v1：REVISE —— D1 缺锁粒度与 I/O 位置说明；缺"遗忘持锁"护栏。
- Planner draft v2：D1 改为"锁内快照 / 锁外写入"，补 AC-1 并发压力用例与类注释不变量；其余保持最小修。
- Architect challenge v2：接受折中，保留 Follow-up 2（绕过 controller 的直改扫描）。
- Critic verdict v2：APPROVED（4 条 Reservation 已写入，未强制降级）。
- **用户决策轮（2026-10-16）**：范围收敛为 **P0+P1（D1–D8）**；D1 改走 **Option B**（否决 Option A / 采纳 Architect steelman）；D8 定为 `Ctrl+Y`；工作区用 `git reset --soft HEAD~1` 合并重复提交后重提基线（不走 worktree）。plan 已按此重写 §决策记录 / D1 步骤 / D8 步骤 / Workspace setup / ADR。
- Final iterations: 2 / 3
