# 科技树工作区选项卡 Implementation Plan

> Status: IMPLEMENTED（实现已完成，见文末 `Plan drift`）
> Source: 用户请求（2026-10-12，经 AskUserQuestion 澄清范围：**视图 + 完整研究编辑**，并用 `dev-plan` 出方案）
> Mode: (default) 完整 Planner → Architect → Critic loop
> Iterations: 2 / 3
> Author: 用户（AI 代拟）
> Last updated: 2026-10-12

## Requirements summary

在现有三个主区工作区选项卡（节点画布 / 物品与液体 / 星球与区块）之后，新增第 4 个选项卡「科技树」，
用于管理**每个实例（ContentRecord）的研究索引**：把全部记录的 `research` 字段解析成科技树森林并可视化，
标出游戏会警告/拒绝的写法，并允许就地编辑完整的 research 节点（parent / root / name / requiresUnlock /
planet / requirements / objectives）。

规范来源：`ContentParser.java:1268-1419`（`readFields` 把 `research` 从 JSON 摘出后单独处理）、
`UnlockableContent.java:209`、`Block.java:1284`、`UnitType.java:1405`、`TechTree.java:78-191`、
`Objectives.java:11-138`。当笔记（`04-参考/Mindustry-JSON键值工程指南`）与源码冲突时，**以源码为准**。

## Acceptance criteria

- **AC-1** 主区出现第 4 个选项卡，文本来自 `workspace.techTree`（中「科技树」/英 "Tech Tree"），不可关闭，排在「星球与区块」之后。
- **AC-2** `TechTreeRules.parent()` / `isRoot()` / `isWellFormed()` 语义不变，既有 `FmtRulesTest.java:142-147` 的断言全部继续通过。
- **AC-3** 树构建：`research` 为 `root:true` 的记录成为根节点；其余按 `parent` 挂到对应节点下；父节点名可用「裸名」或「`<mod内部名>-名`」两种拼写解析（`ContentParser.java:1401`）。
- **AC-4** 父节点在本模组与已知原版内容中都找不到 → 该节点标为「未解析的父节点」，并作为根显示（对应 `ContentParser.java:1403-1405` 的警告）。
- **AC-5** 既非 root 也无 parent → 标为「将被游戏忽略」（对应 `ContentParser.java:1414-1416`）。
- **AC-6** 补丁记录（`ContentRecord.patch == true`）带 `research` → 标为「补丁不允许改科技树」并禁用编辑面板（对应 `ContentParser.java:1338-1341`）。
- **AC-7** 编辑 parent：只有 parent 一个键时写**裸字符串**；一旦还有其它键则写对象（与 1348-1354/1394 的两条分支一致）。
- **AC-8** root 开关：写 `{root:true, ...}`；可附带 `name`（默认内容名）与 `requiresUnlock`（默认 false）；root 为真时不写 `parent`。
- **AC-9** objectives 可增删，类型限源码里的 5 种（`Research` / `Produce` / `SectorComplete` / `OnSector` / `OnPlanet`），`SectorComplete` 提供 `{type: SectorComplete, preset: <区块名>}` 模板。
- **AC-10** requirements 可增删，按源码 `ItemStack` 字符串语法写 `"item/amount"`（`ContentParser.java:466-470`）。
- **AC-11** 所有编辑走 `ProjectController.setField`（`ProjectController.java:1214`）→ 可撤销、置脏、重序列化提示与检查器一致。
- **AC-12** 全量 `gradlew test` 0 失败 0 跳过，且新增用例数 ≥ 4。

## RALPLAN-DR

### Principles

- 跟随 baseline「最小代码」：不引入强类型模型层，沿用项目既有的「字段即 `JsonElement`」约定。
- 跟随 baseline「外科手术式改动」：不动 `Workspace.of()` 路由、不动 `LinkGraph` 的 research 边、不动检查器。
- **记录为唯一真相**（架构 §二）：树是 `research` 字段的**派生视图**，不新增第二份存储。
- 规范保真：能写进 JSON 的键，必须与 `ContentParser` 读得到的一一对应；读不到的键不发明。
- 可验证：每一类「游戏会警告」的局面都要有可断言的标记。

### Decision drivers

1. **规范保真度**（用户明确要求「权重以源码规范为准」）—— 决定必须覆盖全部 7 个键。
2. **不破坏既有不变量** —— `TechTreeRules.parent()` 同时被 `LinkGraph.java:106-112` 与 `Validator.java:397-401` 使用，语义不得变。
3. **改动面可控** —— 项目已有 265 个测试与大量未提交改动，新增面越小越好。

### Viable options

**Option A（favored）：扩展 `TechTreeRules` 为读写模型 + 新建 `TechTreePane`（派生视图）**
- 实现思路：`TechTreeRules` 增加 `Node` 记录与 `read/write`；新增 `core/fmt/TechTreeIndex` 建森林与问题标记；`ui/TechTreePane` = TreeView + 右侧编辑面板，编辑直接调 `setField`。
- 改动文件：`core/fmt/TechTreeRules.java`、`core/fmt/TechTreeIndex.java`(新)、`ui/TechTreePane.java`(新)、`ui/Workspace.java`、`ui/AppShell.java`、`messages(_zh_CN).properties`、3 个测试文件。
- Pros：与既有分层一致（core 零 JavaFX、可单测）；树永远等于记录派生结果，不会与文件脱节；复用 `setField` 的撤销/脏标记。
- Cons：每次 `onProjectChanged` 重建整棵树；大模组（561 记录）下需要控制规模。

**Option B：把 research 建模成强类型 `ResearchNode` 并接进 `ContentRecord`**
- 实现思路：新增 record 类型 + 序列化/反序列化层，UI 只绑定该模型。
- Pros：类型安全，编辑面板写起来最直观。
- Cons：与项目「字段即 `JsonElement`、`raw`/`metaText` 无损往返」的核心约定冲突，等于引入第二份真相；`ProjectIo` 的文本补丁层（`JsonTextPatch`）也得跟着改造。**改动面远超收益**。

**Option C：复用检查器的 raw JSON 区，只在树里做导航**
- Pros：几乎零新增代码。
- Cons：raw JSON 区**已经存在**，用户仍要手写完整对象；等于没满足「管理研究索引」。**被否**。

> 只有 1 个 favored option 不合规，故 B/C 均保留并给出否决理由；**Option B 的 invalidation rationale**：项目已用 5 轮迭代确立「不做第二份真相」（P2 连线方向唯一化、P1 无损内联），强类型模型会同时打破这两条。

## Planner draft（v1）

- Principles / Decision drivers 如上。
- 选定 Option A；实施步骤 7 条（见下方最终版）。
- v1 的缺口：未处理「编辑后面板重建导致选中/焦点丢失」；未定义 vanilla 父节点如何呈现；未定义 `requirements` 缺省时的展示语义。

## Architect challenge（v1）

### Steelman against Option A

最强反驳：**Option A 的树会在每次字段编辑时整体重建**。本项目「每敲一个键就 `fireProjectChanged`」是已知行为
（`RecordInspector` 的 live 编辑、`CosmosPane` 甚至为此加了 150ms 防抖，见 `CosmosPane.java:37-44`）。
科技树面板若照抄 `refresh()` 全量重建，用户在编辑 parent 时树会跳动、选中会丢。
反驳成立则 plan 必须补「选中恢复 + 重建防抖」，否则 Option A 的 Cons 会变成真实缺陷。

### Tradeoff tensions

- **规范保真 vs 最小代码**：源码允许 7 个键，全做会让编辑面板变大（约 5 个控件组）；砍掉任何一个都违背用户
  「以源码为准」的要求。取舍依据：保留全部 7 个键，但把 `requirements`/`objectives` 做成**列表式增删**而非表单，
  单个控件组控制在 20 行内。
- **树的完整性 vs 规模**：561 个记录若全部展开会成为视觉噪声。取舍：**默认只展开根节点**，`TreeView` 天然懒展开。

### Synthesis path

把「重建」从「整棵树重建」降级为「复用已有 TreeItem、按 contentId 还原展开与选中」，与
`NodeCanvasPane.refresh()`（`NodeCanvasPane.java:179-304`）在同一场景下采用的 restore 手法一致。

## Critic verdict（v1）

| 维度 | 状态 | 备注 |
|---|---|---|
| Principle consistency | ✓ | Option A 与「记录为唯一真相」一致 |
| Alternative exploration | ✓ | B/C 都有明确否决理由 |
| Risk mitigation clarity | ✗ | 「重建丢选中」只被 Architect 提到，**未进 Risks 表** |
| AC testability | ✗ | AC-9「objectives 可增删」不可二值验证，缺具体断言目标 |
| Verification concreteness | ✗ | 未给出测试类名与命令 |
| File/line coverage | ✓ | 步骤均已 cite |
| Pre-mortem | n/a | 非 deliberate 模式 |

### Verdict: REVISE

待改项：
1. Risks 段补「编辑触发重建导致选中/焦点丢失」一条 + mitigation。
2. AC-9 拆成可二值验证的两条（类型集合固定为 5 种；`SectorComplete` 模板落成 `{type, preset}`）。
3. Verification 段补具体测试类名 / 断言 / 命令。

## Planner draft（v2，已合入反馈）

- Risks 表新增「重建丢选中」与 mitigation（复用 contentId 恢复，参考 `NodeCanvasPane.java:179-304`）。
- AC-9 拆为 AC-9/AC-10，requirements 顺延为 AC-11，编辑链路为 AC-12。
- Verification 段落到 `gradlew test` + 具体测试类名与断言。

## Critic verdict（v2）

| 维度 | 状态 | 备注 |
|---|---|---|
| Principle consistency | ✓ | — |
| Alternative exploration | ✓ | — |
| Risk mitigation clarity | ✓ | 4 条 risk 均有对应 mitigation |
| AC testability | ✓ | AC-1..AC-12 均可二值判定 |
| Verification concurrency | ✓ | 命令 + 类名 + 断言明确 |
| File/line coverage | ✓ | 7 条实施步骤全部 cite 文件 |
| Pre-mortem | n/a | 非 deliberate |

### Verdict: APPROVED（合入 2 处措辞修正）

### Reservations

- `TechTreeIndex` 把「找得到父节点」的判定建立在 `SchemaRegistry.vanillaNames(type)`（`SchemaRegistry.java:367`）
  上。我对此有保留：**原版内容名清单是 schema 生成物，可能滞后于游戏版本**，届时一个真实存在的原版父节点会被
  误标为「未解析」。Mitigation 段只写了「标为未解析并作为根显示」，没有区分「疑似原版缺失」。建议实现时把该标记
  文案写成中性表述（「未在本模组/已知原版内容中找到」），不要断言"游戏里不存在"。
- 实施步骤 6 新增 `techtree.*` 词条：Critic 无法从 plan 判断词条总数，若超过 12 条应拆 `Messages` key 前缀以免
  messages.properties 继续膨胀。**不阻塞**，实现时按实际需要落。

## Implementation steps

1. **扩展 `core/fmt/TechTreeRules.java`（现 15-60 行）** — 新增：
   - `record Node(String parent, boolean root, String name, boolean requiresUnlock, String planet, List<String> requirements, List<JsonObject> objectives)`；
   - `static Node read(JsonElement research)`（按 `ContentParser.java:1348-1396` 逐键读取；`requirements` 兼容 `"item/amount"` 字符串与 `{item,amount}` 对象两种形态）；
   - `static JsonElement write(Node node)`（只有 parent 且非 root → 返回 `JsonPrimitive(parent)`；否则返回对象；键序 `parent, root, name, requiresUnlock, planet, requirements, objectives`）；
   - `static boolean allowsResearch(boolean patch)`（补丁返回 false，对应 `ContentParser.java:1338-1341`）；
   - `static List<String> objectiveTypes()` 返回 5 个类型名（`Objectives.java:11-138`）。
   - **保留** `parent/isRoot/isWellFormed` 不动（`LinkGraph.java:108`、`Validator.java:400`、`FmtRulesTest.java:142-147` 依赖）。
2. **新增 `core/fmt/TechTreeIndex.java`** — `static Forest build(ModProject project, SchemaRegistry registry)`：
   - 节点 = 记录；先收集 `read(...).parent()`；root 记录与「未解析父」记录作为根；其余挂到父节点；
   - 父名解析两条分支：裸名命中本模组记录名，或 `<project.internalName()>-名`（`ContentParser.java:1401`）；否则查 `registry.vanillaNames(type)`（`SchemaRegistry.java:367`）→ 命中则建**只读原版节点**；都不中 → 归入「未解析」；
   - 输出 `Forest(List<Entry> roots, List<Entry> unresolvedParents, List<Entry> ignored, List<Entry> patchesWithResearch)`，`Entry(String contentId, String label, List<Entry> children, boolean vanilla)`。
3. **新增 `ui/TechTreePane.java`**（照 `CosmosPane.java:27-77` 的结构）：
   - 左 `TreeView<Entry>`（默认折叠，只展开根）；右上工具栏（设为根 / 设置父节点 / 清除研究 / 添加目标 / 添加需求 / 设置星球）；右下为选中节点的只读摘要 + 编辑控件；
   - 选中树节点 → `controller.select(record)`（`ProjectController.java:1347`）；
   - 订阅 `controller.onProjectChanged(this::refresh)`；`refresh()` 复用 TreeItem 并按 contentId 还原展开/选中（手法照 `NodeCanvasPane.java:179-304`）；
   - 所有写操作 → `controller.setField(record, TechTreeRules.FIELD, TechTreeRules.write(node))`。
4. **新增父节点下拉候选**：照 `RecordInspector.referenceCandidates`（`RecordInspector.java:590-610`）取「本模组记录 fullName + `registry.vanillaNames`」，但**不设 contentCtype 过滤**（research 的 ctype 是 `any`，见 `schemas/schema.json:190-194`）。
5. **`ui/Workspace.java:8-22`** — 加枚举值 `techTree`；`of(ContentType)` **保持不变**（科技树是全局视图，不绑定内容类型，故不会被 `showWorkspace` 自动切入）。
6. **`ui/AppShell.java`** — `62-63` 加字段 `techTreePane`；`82-88` 构造；`177-190` 的 `buildCenter` 追加 `workspaceTab(Workspace.techTree, techTreePane)`；`877-890` 加测试钩子 `TechTreePane techTreePane()`。
7. **`src/main/resources/messages.properties:48-50` / `messages_zh_CN.properties:47-49`** — 加 `workspace.techTree`；新增 `techtree.*` 词条（工具栏、问题标记、面板标签），中英同步。
8. **测试**：
   - 新增 `src/test/java/dev/modmaker/core/fmt/TechTreeIndexTest.java`（见 Verification）；
   - `src/test/java/dev/modmaker/ui/UiSmokeTest.java` 加一个用例 + 快照 `snapshots/ui-techtree.png`。

## Workspace setup

- 实施前已运行：`git status --short` → **92 项改动**；`git branch --show-current` → **`main`**。
- **结论：本轮不建 worktree**（与 skill 默认建议相反，理由写在下）。因为 HEAD 停在 `0dabb0c`（10-06），
  10-07 起的全部工作（可移植化、LinkGraph、校验汉化、无损内联、表格就地编辑、连线数量、目录预检）**都还没提交**，
  从 HEAD 切出的 worktree 会**缺失本 plan 依赖的代码**（例如 `TechTreeRules` 本身是 10-07 后的产物）。
- 本轮继续在现有工作区**原地实现**，与用户「暂不提交、只改代码/文档」的一贯要求一致。
- 若后续要评审，应先把现有 92 项改动整理成提交再谈分支策略（属于 Follow-ups，不在本轮）。

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| 编辑触发 `onProjectChanged` 重建整棵树 → 选中/焦点丢失、树跳动 | `refresh()` 复用 TreeItem，按 `contentId` 还原展开集与选中（手法照 `NodeCanvasPane.java:179-304`） |
| 大模组（561 记录）下树过大 | 默认只展开根节点；节点标题用 `controller.displayName(record)`；不预展开子节点 |
| 原版父节点被误判为「不存在」（schema 的 vanilla 名清单可能滞后，见 Reservations） | 标记文案写成「未在本模组/已知原版内容中找到」，不断言游戏里不存在；`TechTreeIndexTest` 用一个真实原版父名做正例 |
| 写对象时丢掉用户原有的未覆盖键 | `read`→改→`write` 全节点往返；`write` 只在对应键非默认时才写出 |
| 补丁记录被误允许编辑 | `allowsResearch(record.patch)` 为假时禁用面板并显示原因（AC-6） |
| 与 `LinkGraph` 的 research 边产生分歧 | 两者都只读 `TechTreeRules.parent()`，不新增解析逻辑；AC-2 用既有测试锁住语义 |

## Verification steps

- **AC-2 回归**：`gradlew.bat test --tests "*FmtRulesTest*"` —— 现有 5 条 `TechTreeRules` 断言必须全绿。
- **AC-3/4/5/6（core 层）**：新增 `core/fmt/TechTreeIndexTest`
  - `rootsAndChildrenAreLinkedByTheResearchParent()`：A(root) ← B(parent=A) ← C，断言森林结构与深度；
  - `bothBareAndPrefixedParentNamesResolve()`：B 写 `parent: C` 与 `parent: <mod>-C` 都能挂上；
  - `missingParentIsFlaggedAndSurfacedAsARoot()`：`parent: no-such-thing` 进 `unresolvedParents` 且出现在根列表；
  - `nodeWithNeitherParentNorRootIsFlaggedAsIgnored()`：进 `ignored`；
  - `patchWithResearchIsRefused()`：`record.patch=true` + research → 进 `patchesWithResearch`；
  - `vanillaParentBecomesAReadOnlyNode()`：`parent: copper` 生成 `vanilla=true` 的节点。
- **AC-9/10（core 层）**：`FmtRulesTest`（或新 `TechTreeRulesTest`）
  - `writeUsesTheBareStringWhenOnlyAParentIsSet()`（AC-7）；
  - `readKeepsEveryKeyTheGameReads()`：7 键往返相等；
  - `objectiveTypesAreExactlyTheFiveTheGameShips()`（AC-9）；
  - `requirementsRoundTripAsItemSlashAmount()`（AC-10）。
- **AC-1/8/11/12（UI 层）**：`UiSmokeTest.techtreeTabShowsTheResearchForestAndEditsANode`
  - 断言第 4 个选项卡文本 = `Messages.t("workspace.techTree")`；
  - 断言树根数量 = 输入记录算出的根数；
  - 选中一个记录 → 点「设为根」→ 断言 `record.fields.get("research")` 是对象且 `root==true`、记录被置脏；
  - `controller.undo()` → 断言字段回到原值（AC-11）；
  - `Snapshotter.write(scene, Path.of("snapshots", "ui-techtree.png"))` 并用 Read 工具**肉眼复核**。
- **全量**：`.\gradlew.bat test --console=plain` → 0 失败 0 跳过（AC-12）。

## ADR

- **Decision**：新增 `Workspace.techTree` 选项卡，UI 由 `TechTreePane`（TreeView 派生视图 + 就地编辑面板）承载；
  research 的读写规范集中在 `core/fmt/TechTreeRules`，树的构建集中在 `core/fmt/TechTreeIndex`；编辑一律经
  `ProjectController.setField`。
- **Drivers**：规范保真度（用户明示）＞ 不破坏既有 `parent()` 语义 ＞ 改动面可控。
- **Alternatives considered**：
  - Option A（**chosen**）派生视图 + 复用 setField；
  - Option B 强类型 `ResearchNode` 模型 —— **rejected**：引入第二份真相，需改造 `ProjectIo` 文本补丁层；
  - Option C 仅导航 + raw JSON —— **rejected**：raw JSON 已存在，未满足「管理研究索引」。
- **Why chosen**：A 是唯一同时满足「规范保真」「记录为唯一真相」「改动面可控」的选项，且与既有分层（core 可单测 / ui 薄）一致。
- **Consequences**：
  - 正面：科技树成为与记录同步的派生视图，不会出现「画布/记录双真相」那类漂移；新增 core 层测试可脱离 JavaFX 验证全部规范分支。
  - 负面：每次字段编辑重建树（用 contentId 恢复选中缓解）；原版父节点的存在性依赖 schema 的 vanilla 名清单。
- **Follow-ups**（本轮明确不做）：
  - 树上的拖拽改父节点（对应 receiving §7 未做项②「研究链可视化」的拖拽部分）；
  - 把 `research` 的校验从「泛型引用解析」升级为专用规则（`validate.research.*`）；
  - 把 92 项未提交改动整理成可评审的提交序列（见 Workspace setup）。

## Review trail

- Planner draft v1：Option A（派生视图 + setField），7 条实施步骤；缺口 = 重建丢选中、vanilla 节点呈现、requirements 缺省语义。
- Architect challenge v1：steelman = 「每次字段编辑全量重建树」会变成真实缺陷（对照 `CosmosPane.java:37-44` 的防抖先例）；
  tension = 规范保真 vs 最小代码 / 树完整 vs 规模；synthesis = 复用 TreeItem + contentId 还原。
- Critic verdict v1：**REVISE** —— Risks 缺「重建丢选中」、AC-9 不可二值验证、Verification 缺类名与命令。
- Planner draft v2：补 Risks 一条 + mitigation；拆 AC-9；Verification 落到具体测试类与断言。
- Critic verdict v2：**APPROVED**，合入 2 处措辞修正，并留 2 条 Reservation（vanilla 名清单滞后 → 标记措辞中性化；`techtree.*` 词条数量控制）。
- Final iterations: 2 / 3

---

## Plan drift（实现期偏离，2026-10-12）

实现完成后回填。凡与上文 ADR / Implementation steps 不一致处，以本节为准。

1. **`TechTreeIndex` 落在 `core/io` 而非计划的 `core/fmt`**。它需要 `ModProject` + `SchemaRegistry` + `ContentRecord`，
   而 `core/fmt` 是零模型依赖的纯格式规则层；同族的派生器 `LinkGraph` 本来就在 `core/io`。
   理由：不让格式层反向依赖模型层。
2. **`Forest` 多了一个 `unassigned` 列表**（计划只有 4 个）。没有它的记录（完全不带 research）在树里无处可寻，
   用户也就没地方给它们加研究索引 —— 而这正是本选项卡的目的。UI 里渲染成一个「未参与科技树」分组。
3. **编辑控件只在右侧详情面板**：计划写的「顶部工具栏 = 设为根/设置父节点/清除…」与其右侧面板重复，已合并为一处。
   详情面板跟随 `controller.selected()`（与检查器同一约定），因此画布上选中的卡片也能在此编辑。
4. **实现期发现并修掉一个真缺陷**：「兜底成根」循环原本放在 vanilla 分组之前，导致挂到**原版父节点**下的记录
   会同时以「多余的根」出现一次（重复节点）。已把该循环移到 vanilla 分组之后，并加测试锁住。
5. **测试落点与计划略有出入**：`TechTreeIndexTest` 落在 `core/io`（随第 1 条）；`TechTreeRules` 的读写用例进了
   既有的 `FmtRulesTest`（计划里的 `TechTreeRulesTest` 未单独建）。总计 +13 个用例（9 + 3 + 1），258 → 278。
6. **Critic Reservation 1 的处置**：`TechTreeIndex` 只把「本模组节点 / 已知原版名」之外的名字标为
   `unresolvedParent`，面板文案写成「父节点未找到」并注明「游戏会警告」，**没有**断言该内容在游戏里不存在 ——
   与保留意见一致。Reservation 2（词条数量）：新增 19 条全部集中在 `techtree.` 前缀下，未散落。

**验证结果**：`.\gradlew.bat test` → **278 个 / 35 类，0 失败 0 错误 0 跳过**；
快照 `snapshots/ui-techtree.png` 已肉眼复核（第四个选项卡「科技树」、树呈现 `new-block-1 → new-block-2`、
详情面板的父节点/根节点/星球/需求/目标控件齐全）。
