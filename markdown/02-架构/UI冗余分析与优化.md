# UI 冗余引用分析与优化方案

> 对象：ModMaker（JavaFX 21，`src/main/java/dev/modmaker/ui`，8,850 行）
> 基线：2026-10-17，298 个测试 / 37 类
> 状态：**P0 已实施**（校验快照、隐藏面板惰性刷新、死表面清理）；P1–P4 待排期。

## 0. 一句话结论

功能不缺，缺的是**唯一入口 / 唯一写入口**：同一份数据被 2–4 个 UI 表面引用，同一类型有 2–4 个导航入口，同一条命令散落在菜单、工具条、页内按钮里；同时隐藏面板在每次编辑时照常全量刷新。P0 已把“每次编辑 4 次全量校验 + 3 个隐藏面板重建”收敛为“1 次校验 + 隐藏面板 0 次刷新”。

## 1. 现状信息架构

| 工作区 | 主视图 | 自带右侧面板 | 全局属性栏 | 子行（48px） | 固定横向占用 |
|---|---|---|---|---|---|
| 资源管理 | 卡片网格 + 480×560 预览 | — | 396 | 9 个类型 chip + 增/删 | 480 + 396 |
| 方块构筑 | 240 调色板 + 画布 | — | 396 | 自适应/撤销/重做/搜索/构建/停止/日志/画板 | 240 + 396 |
| 单位构筑 | 卡片网格（同资源页） | — | 396 | 单 chip + 增/删（P0 已移除 chip） | 480 + 396 |
| 星球战役 | 树 + 360 星球预览 | 预览 360 | 396 | 空行（P0 已移除） | 360 + 396 |
| 科技研究 | 树（pref 460）+ 详情编辑器 340 | 详情 340 | 396 | 空行（P0 已移除） | 340 + 396 |
| 扩展脚本 | 禁用占位 | — | 396 | 空行（P0 已移除） | — |

布局压力：1366 宽时，资源页网格只剩约 490px（约 2 列 232px 卡片）；科技研究固定被详情 340 + 属性 396 吃掉 736px。

## 2. 五类冗余

### R1 导航冗余：一个类型，多个“家”

三套独立的类型→界面映射：

1. `core/fmt/Workspace.of()`：block→canvas、unit→units、planet/sector→cosmos、item/liquid→assets。
2. `AppShell.typeChips(..., ContentType.creatable())`：把 **9 个可创建类型全部塞进资源管理**，包括已有专属页的 block/unit/planet/sector。
3. `AppShell.buildPalette()`：原先 `creatable()` **再加** 3 个非文件类型（bullet/unitCommand/unitStance），但这 3 行没有点击/拖拽处理器，是死控件（P0 已移除）。

结果：方块有资源网格/画布/科技树/搜索 4 个入口；行星有资源网格/星球树/星球预览 3 个入口；创建入口也有资源页“添加”+ 星球页“添加行星”两处。

### R2 编辑冗余：一个字段，多个写入口

| 数据 | 写入口 | 机制冲突 |
|---|---|---|
| `research` | 科技树详情（`TechTreePane.java:207` → `setField`）＋ 检查器 Raw JSON（`RecordInspector.java:354` → `applyRawTree`） | 两套 UI；Raw 可绕过结构化规则 |
| 引用字段 | 画布端口拖拽/连线 + 双击改数量（`NodeCanvasPane.java:148/247`）＋ 检查器内容下拉（`RecordInspector.java:615`）＋ 载体卡下拉（`CarrierCard.java:82`） | 三条写路径；三套候选列表，标签语义不同 |
| 名称/描述/详情/致谢 | 检查器本地化块（`RecordInspector.java:308`）＋ 字符串表（`StringsPane.java:92`）＋ Raw JSON | 同一 bundle 键两套编辑器 |
| `mesh/cloudMesh` | MeshEditor 结构化子表 ＋ Raw JSON | Raw 可整树覆盖 |
| 文件级 name/type | 标识区改名/换类 ＋ Raw JSON | Raw 改 `name` 不改文件名和 bundle 键 |

**候选列表重复实现**：
- 检查器：`record.fullName(internal)`（带前缀）+ vanilla，限 120/400（`RecordInspector.java:675`）
- 载体卡：`displayName`（显示名）+ vanilla，按 label 去重、无上限（`NodeCanvasPane.java:370`）

同一“引用”字段，两处看到的可选项和写法不一样，是典型漂移点。

### R3 展示冗余：一个事实，多次呈现

**检查器内部三重重复**：头栏（`new-block-1` + `方块 · 通用合成器`）、预览盒（`方块 / 通用合成器`）、统计行（类型/类/名称/文件）。

**预览三套实现**：资源页 480×560（`ItemsPane.java:43`）＋ 检查器 88px 预览盒 ＋ 星球 360 canvas（`PlanetPreview.java:41`）。资源页同时出现两个预览；星球页大预览和检查器预览同时出现。

**校验四个表面、四次计算**（P0 前）：
- 检查器 `.report`（`RecordInspector.java:194`）
- 校验面板（`IssuesPane.java:63`）
- 资源网格红点（`ItemsPane.java:255`）
- 单位网格红点（第二个 `ItemsPane`）

实测（Exoprosopa 夹具，561 条记录 / 43 警告 / 0 错误）：`Validator.validate` 平均 18ms（首次 26ms，20 次平均）。一次编辑 → 4 次 ≈ 72ms+，且全部在 FX 线程上执行。

### R4 命令冗余且不一致

- 撤销/重做：菜单 + 画布工具条；其他工作区只有菜单；启用状态在 `refreshProjectState()` 手工同步四份。
- 搜索：全局 `Ctrl+F`，输入框只存在于画布子行；其他工作区按 Ctrl+F 会给不可见控件聚焦。
- 类自适应：影响全局检查器，却只放在画布工具条。
- 构建：画布工具条 + 菜单；校验/实机/保存只在菜单。
- 删除：画布 Delete 删整条内容（`NodeCanvasPane.java:136`），资源页有删除按钮，星球/科技树没有。
- 添加：调色板、资源页“添加”、星球页“添加行星/区块”。

### R5 隐藏/死表面

- `AppShell.java:62` `BOTTOM_STRIP_VISIBLE=false`，但 4 个面板仍被构建、注册监听，并在每次编辑时刷新（P0 前）。
- 资源页/星球页/科技页的 48px 空子行（P0 已移除后三者）。
- 扩展脚本禁用 Tab（保留为路线图占位，非死控件）。
- 调色板 3 个不可点击的非文件类型行（P0 已移除）。

## 3. 目标设计：单写多读 + 单一上下文面板

### 三条原则

1. **一个类型一个家**：`Workspace` 路由是唯一事实；资源页不再重复列出已有专属页的类型。
2. **一个数据一个写入口**：结构化字段、research、引用、字符串各选一个“规范写入口”，其他投影只读或发同一条命令。
3. **一条命令一个注册点**：菜单/工具条/快捷键都从同一个命令注册表派生，启用状态只维护一份。

### 目标布局

```text
┌ 36px 标题条（菜单 + 窗口键） ─────────────────────────────┐
├ 48px 全局命令条：撤销/重做 | 搜索 | 校验 | 构建 | 实机 | 日志 ┤
├ 48px 工作区条：类型 chips / 添加删除 / 画布工具 / 星球添加   ┤
├──────────────────────────────────┬───────────────────────┤
│ 工作区主视图                      │ ContextPanel（可调宽） │
│ （网格 / 画布 / 树 / 预览）        │ 属性 | 预览 | 校验 | 文本 │
└──────────────────────────────────┴───────────────────────┘
```

- **ContextPanel** 替代“检查器 + 480 预览 + 详情编辑器 + 字符串表”四块固定区域；
- 资源页不再有独立 480×560 预览，改到“预览”tab；
- 星球页的 360 预览也进“预览”tab，树拿回全宽；
- 科技树删除 340px 详情编辑器，research 编辑并入“属性”里的结构化 `Research` 块；
- 字符串表作为“文本”tab（或独立工作区），检查器本地化块改为只读摘要 + “定位到键”。

### 规范写入口矩阵

| 数据 | 唯一写入口 | 其他表面 |
|---|---|---|
| 普通字段 | 检查器属性表单 | 画布卡片只读 |
| 引用字段 | 检查器内容选择器；画布连线 = 同一命令的另一种手势 | 载体卡只读（或发同一命令） |
| research | 检查器 Research 块 | 科技树只读 + 拖拽改父节点发同一命令 |
| 文本 | 文本 tab / 字符串表 | 检查器本地化只读摘要 |
| mesh | MeshEditor | Raw 只读 |
| Raw JSON | “高级编辑”显式开关 | 默认只读 |
| 校验结果 | `ValidationStore` 缓存 | 所有红点/列表/状态栏读同一快照 |

## 4. 分阶段方案

| 阶段 | 内容 | 状态 |
|---|---|---|
| **P0 性能与死表面** | 校验快照缓存；隐藏面板惰性刷新；删除 3 个非文件调色板行、单位单 chip、空子行 | ✅ 已完成 |
| **P1 单一目录与命令条** | `WorkspaceCatalog`（每个 `ContentType` 恰好一个 home）；全局命令条；`UiAction` 注册表 | ✅ 已完成（2026-10-17） |
| **P2 单一上下文面板** | `ContextPanel`（属性/预览/校验/文本）；移除资源页 480 预览和星球并排预览；检查器头栏去重 | 🟡 部分完成（2026-10-17；星球预览迁移留 P3） |
| **P3 单写多读** | Research 块进检查器，删除科技树详情；字符串表唯一文本写入口；Raw 默认只读 + 高级开关；引用候选服务统一 | 🟡 部分完成（2026-10-17；引用候选统一与星球预览迁移留后续） |
| **P4 刷新粒度** | `contentRevision/stringsRevision/assetsRevision/boardRevision` 分域通知；隐藏 tab 暂停监听 | 待排期 |

### 验收指标

- `Validator.validate` 每次编辑调用 **≤1 次**（P0 已达成：缓存 + 身份测试）；
- 隐藏面板刷新次数 **0**（P0 已达成：SpritePane `refreshCount` 测试）；
- 1366×768：科技树主树区 ≥600px，资源网格 ≥3 列（P2 后）；
- 561 条记录项目上“编辑→重绘”p95 < 50ms（P4 后）；
- 每个 `ContentType` 恰好一个规范入口（`WorkspaceCatalogTest` 全类型遍历，P1）；
- 无“点了没反应”的控件（P0 已覆盖调色板；后续覆盖全部可点击行）。

### 明确不建议做的事

- ❌ 合并画布和资源网格：一个表达空间/引用关系，一个表达清单管理；
- ❌ 删除 Raw JSON：保留为格式逃生口，但从“实时第二编辑器”降级为“显式高级模式”；
- ❌ 给单位开第二个画布（已论证过回归面）；
- ❌ 把资源页做成“万能入口”：重复入口正是当前问题的来源；
- ❌ 为了统一而让六个工作区长得一样：统一的是外壳，不是内容形态。

## 5. P0 实施记录（2026-10-17）

### 5.1 校验快照

`ProjectController` 新增 `volatile List<Issue> issuesCache`：

- `validateQuietly()` 首次计算后缓存，四个调用点共享同一个不可变列表；
- `validate()`（F5/校验按钮）先清缓存再计算，保证显式校验永远是最新结果；
- `fireProjectChanged()` 在派发监听前清缓存，避免监听者读到上一状态。

回归：`ProjectControllerInteractionTest.validationIsCachedUntilTheProjectChanges` —— 同一状态两次调用 `assertSame`；`validate()` 返回不同实例；模型变更后缓存失效。

### 5.2 隐藏面板惰性刷新

`IssuesPane` / `StringsPane` / `SpritePane` 增加 `live` / `stale`：

- 构造时仍做一次初始刷新；
- `AppShell` 在 `BOTTOM_STRIP_VISIBLE=false` 时调用 `setLive(false)`；
- 隐藏期间项目变更只置 `stale`，不重建表格/不扫描 1507 个精灵；
- `AppShell.selectTab(key)` 激活对应 pane，`setLive(true)` 时若 stale 则补一次刷新。

回归：`UiSmokeTest.hiddenBottomPaneStaysIdleUntilItsTabIsOpened`（SpritePane `refreshCount`：隐藏时不变，打开 tab 后 +1）。

### 5.3 死表面清理

- 调色板只列 `ContentType.creatable()`（9 个文件型），移除 bullet/unitCommand/unitStance 三个不可点击行；
- 单位页移除只有一个 chip 的类型行，只保留增/删；
- 星球/科技/脚本页不再预留空 48px 子行（`buildSubHeader` 返回 null）；
- 扩展脚本 Tab 保留为禁用占位（有提示、有测试断言），不属于死控件。

回归：`UiSmokeTest.paletteOnlyListsCreatableTypes`（行数 = 可创建类型数，且每行都有 click handler）。

### 5.4 结果

- 全量 `gradlew test --rerun-tasks`：**298 / 37 类，0 失败 0 跳过**；
- 每次编辑的校验开销：4 次全量 → 1 次全量（其余为缓存读取）；
- 隐藏面板不再参与每次编辑的重建；
- 界面减少 3 个死行、1 个单 chip 行、3 个空子行。

### 5.5 P1 实施记录（2026-10-17）

**单一类型目录**：`Workspace.of` 把 status/weather/team 归入 assets，新增 `Workspace.assetTypes()`；资源页 chips 从 `ContentType.creatable()`（9）改为 `Workspace.assetTypes()`（5），block/unit/planet/sector 不再重复列出。调色板仍列全部 9 个可创建类型（画布可建任意内容）；`addContentRouted` 对 assets 类型补 `resourcesPane.showType(type)`。

**全局命令条**：标题条下新增 44px `.command-bar`，承载撤销/重做/搜索/校验/构建/实机/停止/日志，右侧保留琥珀「类自适应」开关（遵守用户可见开关规则）；画布 48px 工具条只留画板选择器。

**`UiAction` 注册表**：新增 `ui/UiAction`，菜单项与命令条按钮共享同一启用态、快捷键与异常处理；`refreshProjectState` 只设 `undoAction.enabled`/`redoAction.enabled`，`refreshGameButtons` 只设 `stopAction`/`openLogAction`，删除了原先 `undoItem/undoButton` 四份手工同步。

**回归**：`WorkspaceTest` 补 `everyFileBackedTypeHasExactlyOneCanonicalHome` 与 `inlineTypesAreNotListedInTheAssetsGrid`；`UiSmokeTest.commandBarActionsShareTheirStateWithTheMenu`（命令条 7 按钮、撤销随编辑/撤销切换启用态）；几何测试更新：顶栏 36+44=80px，标签采样带下移 44px。全量 **298 / 37 类，0 失败 0 跳过**。

### 5.6 P2 实施记录（2026-10-17，部分）

**ContextPanel**：新增 `ui/ContextPanel`，右侧固定 396px 改为四 tab（属性/预览/校验/文本）。`属性`=检查器；`预览`=新 `RecordPreview`（单一预览面）；`校验`=`IssuesPane`；`文本`=`StringsPane`。校验/文本 tab 首次选中才 `setLive(true)`（保持 P0 的惰性收益）；底栏改为只保留精灵/日志。`selectTab("bottom.validation"/"bottom.strings")` 重定向到面板，校验结果终于可见。

**资源页预览移除**：`ItemsPane` 删除 480×560 预览与 `updatePreview`，网格全宽（1366 下从 2 列回到 3 列）。

**检查器去重**：删除 `previewBox`/`statList`（type/class/name 三重展示），头栏成为唯一身份展示。

**回归**：`contextPanelExposesValidationAndTextTabs`（四 tab + 校验 tab 展示未解析引用）；`assetGridListsOneTypeWithCardsAndPageDots` 改断言网格全宽。全量 **298 / 37 类，0 失败 0 跳过**。

**未做（留 P3）**：星球页并排 360 预览迁入预览 tab；科技树 340px 详情编辑器并入属性 Research 块。

### 5.7 P3 实施记录（2026-10-17，部分）

**Research 单写入口**：新增 `ui/ResearchEditor`，把科技树的 research 编辑（parent/root/planet/requirements/objectives/clear）迁入检查器「研究」块，唯一经 `controller.setField` 提交；`TechTreePane` 改为只读导航（树选中记录，右侧属性 tab 呈现编辑器），删除 340px 详情编辑器与重复的候选/写路径。科技树测试改为驱动 `shell.inspector().researchEditor()`。

**Raw JSON 默认只读**：整文件 raw 区（`.raw-tree`）默认不可编辑，新增「编辑原始 JSON」开关 + 应用按钮作为逃生口；字段级 raw 编辑器（`.raw-area` 逐个字段）保持不变。

**字符串表唯一写入口**：检查器本地化块改为只读摘要 + 「在字符串表中编辑」按钮（`contextPanel.showStrings()`），`StringsPane`（文本 tab）成为 name/description/details/credit 的唯一编辑面。

**回归**：`rawJsonIsReadOnlyUntilTheAdvancedToggle`；全量 **298 / 37 类，0 失败 0 跳过**。

**留后续**：引用候选列表统一（检查器/载体卡语义不同，需单独立项）；星球页并排预览迁入预览 tab（P2 收尾，耦合 `LiveEditProbe`）。

## 6. 后续建议

1. P2 收尾：星球页 360 预览迁入预览 tab（`PlanetPreview` 由 `CosmosPane` 持有改由 AppShell/ContextPanel 持有），并同步 `LiveEditProbe` 与快照测试；
2. 引用候选列表统一：抽出共享服务，让检查器内容下拉与画布载体卡用同一套「本模组优先 + vanilla 上限 + 去重」规则（注意两处语义不同：字段值 vs 画板绑定）。

## 附：关键文件

| 关注点 | 文件 |
|---|---|
| 外壳/工作区/子行/调色板 | `ui/AppShell.java` |
| 属性栏/本地化/Raw/引用候选 | `ui/RecordInspector.java` |
| 资源/单位网格/预览/红点 | `ui/ItemsPane.java` |
| 校验面板 | `ui/IssuesPane.java` |
| 字符串表 | `ui/StringsPane.java` |
| 精灵面板 | `ui/SpritePane.java` |
| 画布/连线/载体卡 | `ui/NodeCanvasPane.java`、`ui/CarrierCard.java` |
| 科技树详情 | `ui/TechTreePane.java` |
| 星球树/预览 | `ui/CosmosPane.java`、`ui/PlanetPreview.java` |
| 校验缓存/选择/命令 | `ui/ProjectController.java` |
| 类型→工作区路由 | `core/fmt/Workspace.java` |
