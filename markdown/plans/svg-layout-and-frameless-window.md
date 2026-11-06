# SVG 版面（资源管理 / 方块构筑）+ 无框窗口 Implementation Plan

> Status: APPROVED
> Source: 用户请求（2026-10-14）+ 两张参考 SVG `参考 (1).svg`（方块构筑页）、`参考 (2).svg`（资源管理页）
> Mode: (default) 完整 Planner → Architect → Critic loop
> Iterations: 2 / 3
> Author: 用户（AI 代拟）
> Last updated: 2026-10-14

## Requirements summary

把主界面重排成两张参考 SVG 的版面：顶部为「36px 自绘标题条 + 64px 菜单行」，其下是「64px 主区选项卡行 +
64px 子行」，内容区右侧固定 396px「属性栏」。主区选项卡改为 6 个（资源管理 / 方块构筑 / 单位构筑 /
星球战役 / 科技研究 / 扩展脚本），其中「扩展脚本」做**禁用占位**。资源管理页按参考(2) 做成「类型条 +
分页圆点 + 4 列紧凑卡片网格 + 中央预览效果」；方块构筑页按参考(1) 做成「子行类型选择器 + 左侧纵向
分类列表 + 中央节点画布」。同时把窗口改为**无框**（去掉系统标题栏/边框），把最小化/最大化/关闭三个
按钮画进应用自己的 36px 标题条内，默认最大化。

### 参考 SVG 的精确几何（本次实测，非估计）

用 Edge headless 渲染两张 SVG 并读 `<rect>` 属性得到（1920×1020 画布）：

| 区域 | rect (x, y, w, h) | 颜色 |
|---|---|---|
| 标题条 | (0, 0, **1812**, **36**) —— 右端 108px 留给窗口按钮（3×36） | `#16181D` |
| 菜单行 | (0, 40, 1920, **64**) | `#16181D` |
| 选项卡行 | 6 格各 (n×120, 108, **120**, **64**) + 填充 (720, 108, 804, 64) | 激活 `#171A1C` / 空闲 `#1C2022` |
| 子行（资源管理） | 6 格各 (n×120, **172**, 120, **64**)，激活 (0,172,120,64) | 激活 `#121516` / 空闲 `#171A1C` |
| 子行（方块构筑） | 类型选择器 (77, 188, **240**, **32**) rx8 | `#1C2022` |
| 左分类栏（方块构筑） | 面板 240 宽；行 (5, 240+n·52, **220**, **48**) | 面板 `#0B0C0F`，行 `#171B1C` |
| 画布（方块构筑） | (240, 236, 1284, 780) | `#0E1011` |
| 分页圆点带（资源管理） | (0, 236, 1524, **60**) | `#121516` |
| 卡片网格（资源管理） | 起点 x=18, y=318；卡片 **64** 高、色条 **16** 宽、列距 **248**（4 列） | 卡片 `#171B1C`+分类色条 |
| 预览效果框（资源管理） | (1032, 313, **480**, **560**) | `#808080`（占位） |
| 属性栏 | 头 (1524, 108, **396**, **128**) + 体 (1524, 236, 396, 780) | 头 `#171A1C` / 体 `#0B0C0F` |
| 属性栏输入 | 左列 (1579, 153/177/201, **128**, **20**)、右列 (1777, 153/177, 128, 20) | `#23272A` |
| 属性栏分块 | (1542, 244, **360**, **96**) rx8，内图 (1654, 252, 240, 80) | `#23272A` / `#1C2022` |
| 属性栏行 | (1542, 348/404/460/516/572/628, 360, **48**) rx8；进度条 `#5AED5A`、开关 rx16 64×32 | — |
| 底部 | **无**（与用户已收起的下边栏一致） | — |

## Acceptance criteria

- **AC-1** 顶部四行各就位且高度正确（布局后测量，±2px）：标题条 **36**、菜单行 **64**、选项卡行 **64**、子行 **64**。
- **AC-2** 主区选项卡 **6 个**，每格宽 **120**（±2）、高 64（±2）；标签依次为 资源管理/方块构筑/单位构筑/星球战役/科技研究/扩展脚本；「扩展脚本」`isDisable()==true`。
- **AC-3** 资源管理页有：类型条（9 个分类芯片，选中态有独立样式类）、分页圆点行（60px 带）、卡片网格
  （卡片高 64±2、色条宽 16±1、列距 248±2、可见列数随视口变化）、中央「预览效果」框（480×560±4）。
- **AC-4** 方块构筑页有：240±2px 宽左侧分类栏（行高 48±2）、中央节点画布、子行内的类型选择器（240×32±2）。
- **AC-5** 右侧属性栏宽 396±2：头部 128±2（标题 + 类型/名称/ID 与 中文/英文 共 5 个输入框）+ 主体分块。
- **AC-6** 无框窗口：`stage.getStyle() == StageStyle.UNDECORATED`；标题条内 3 个按钮（最小化/最大化/关闭）
  文本来自 `Messages`；关闭按钮与标题栏 X 走同一条 `confirmExit()` 未保存守卫；双击标题条切换最大化。
- **AC-7** `gradlew test` 0 失败 0 跳过（当前基线 279 例；本轮新增/改写后不得减少既有断言覆盖面，
  只允许按新几何**显式更新**）。
- **AC-8** 六个选项卡 + 无框标题条各出一张快照，逐张肉眼复核（Read 看图）。
- **AC-9** 便携包 `make-portable.bat` 重打后 `--snapshot` 出图仍渲染（防"双击跑旧 jar"）。

## RALPLAN-DR

### Principles

- baseline「最小代码」：能复用现有 pane 就复用，不新建 UI 组件框架。
- baseline「外科手术式改动」：**不动** `ProjectController` 命令栈、`ProjectIo` 落盘、`NodeCanvasPane` 交互内核。
- **不丢能力**：25 个菜单动作、画布交互、属性编辑、科技树、星球预览全部仍可达。
- **可验证**：每一条 AC 都能在 `UiSmokeTest` 里二值断言 + 出图复核。
- **版面为准**：几何以本文实测的 SVG 坐标为唯一依据，不凭感觉写数值。

### Decision drivers

1. **保真度**：用户明确「继续对齐参考 SVG 的版面」，几何与分区必须照做。
2. **可分批落地**：外壳结构（标题条/菜单行/选项卡/子行/属性栏）与两个页面内容可以分阶段，每阶段保持全绿。
3. **风险可控**：无框窗口是唯一无法用现有快照通道完全验证的改动（系统级），必须把验证点放在
   `StageStyle`、按钮回调与拖拽逻辑上。
4. **不引入新框架**：拒绝"为 6 个选项卡重写一套 UI 组件层"。

### Viable options

**Option A（favored）：外壳就地重排 + 复用现有 pane + 单位页用同一套资源网格**
- 实现思路：`AppShell.rebuild()` 把顶部改成 `VBox(标题条36, 菜单行64)`，中央改成
  `BorderPane{ center: VBox(选项卡行64, 子行64, 视图), right: 属性栏396 }`；资源管理/单位构筑两页共用一个
  「资源网格」视图（按 `ContentType` 过滤），方块构筑页把现有 `NodeCanvasPane` 连同左侧分类栏一起搬进该
  选项卡内部；属性栏沿用现有 `RecordInspector`，只换外壳与分块样式。
- 改动文件：`ui/AppShell.java`、`ui/ItemsPane.java`、`ui/RecordInspector.java`、`core/fmt/Workspace.java`、
  `App.java`、`src/main/resources/app.css`、`messages(_zh_CN).properties`、`src/test/.../UiSmokeTest.java`。
- Pros：改动集中在"外壳 + 两个页面"；画布/命令栈/落盘零触碰；每阶段可出图验证。
- Cons：`Workspace.of(type)` 的路由语义要改（unit 从 canvas 分出去），会影响 `addContentRouted` 与
  `ItemsPane` 的过滤条件（3 处调用点，均在 `core`/`ui` 内，可一次改完）。

**Option B：给「单位构筑」开第二个 `NodeCanvasPane` 实例（真·双画布）**
- Pros：两个构筑页都是真画布，符合"构筑"二字；用户可在两页间切换看不同起点。
- Cons：两个视图挂在同一个 `controller.board()` 上，需要把拖拽、框选、端口连线、边缘选择等交互状态
  在两份实例间全部对齐（现有实例是**按节点缓存卡片**并注册了监听器），双份实例的刷新时序与性能
  都是新的回归面；**改动面远超收益**。`NodeCanvasPane` 978 行，本轮不该动它。

**Option C：自定义选项卡条（不 `TabPane`），让方块/单位两页共用**同一个**画布节点**
- Pros：真正做到"一个画布、两个入口"，几何最贴近 SVG（SVG 画的本来就是自定义 120px 格）。
- Cons：`TabPane` 是现有 `rebuild()` 的选区恢复、`showWorkspace()`、以及 4 条既有断言
  （`getCenter() instanceof TabPane`、`centerTabs.getTabs().size()`、`workspaceTabSelected`）的共同依赖；
  换成自定义容器等于把这些验证面清零，且 JavaFX 单父节点要求"每次切页搬运节点"，容易踩到布局/焦点坑。

> Option B/C 的 invalidation rationale：本轮的驱动是**版面**，不是"再造一个画布"。B 把 978 行的交互内核
> 置于双实例回归风险中；C 为了一个视觉细节清零 4 条既有断言并改写选项卡机制。A 能用最小改动交付
> 全部版面要求，且失败面被限制在"外壳 + 两个页面"。

## Planner draft（v1）

选定 Option A，分 5 阶段：① 外壳结构重排（标题条/菜单行/选项卡行/子行/属性栏就位）② 资源管理页
（类型条 + 圆点 + 4 列网格 + 预览框）③ 方块构筑页（分类栏搬入选项卡 + 子行选择器）④ 单位构筑页 +
扩展脚本占位 ⑤ 无框窗口 + 出图。**v1 缺口**：没说明 `Workspace.of(type)` 改路由后
`ItemsPane`/`addContentRouted` 的行为；没定义"分页圆点"到底是装饰还是真分页；无框窗口的
拖动/缩放/最大化语义与失败面没写。

## Architect challenge（v1）

### Steelman against Option A

最强反驳：**"对齐 SVG 版面"与"不丢能力"在这几处直接冲突** ——
① SVG 没有底部面板，而我们刚按用户要求把校验页/日志页/精灵页/字符串表收进开关；本轮若继续无视，
这四页在界面上**没有任何入口**（`selectTab` 只是切一个没挂到窗口的 `TabPane`），等于静默丢功能。
② SVG 的右侧「属性栏」是一个**固定 396px 的全局栏**，而科技研究页现在自带"左树 + 右编辑器"，两块
编辑区会同时存在、互相抢眼。③ SVG 的「资源管理」把**方块/单位**也列进类型条，而它们同时又是
「方块构筑/单位构筑」两页的对象 —— 同一份记录有两个入口，必须在 plan 里说清"点卡片之后发生什么"，
否则实现者会各自发明。
若反驳成立，plan 必须：给四页底部面板一个**明确去向**；给科技研究页一个**二选一**；给"同一记录两个
入口"定一条**不矛盾**的规则。

### Tradeoff tensions

- **版面保真 vs 能力可达**：SVG 无底部面板；四页仍在代码里。取舍：**保留开关并明确记录"这四页当前
  无界面入口"**，同时把"校验"以属性栏分块的形式保留可见（已有），并在 ADR 的 Follow-ups 里写明
  "底部面板需要一个正式归宿（属性栏分块 / 浮动面板 / 快捷键弹层）"。
- **一致性 vs 最小改动（单位构筑）**：两个构筑页要么都是画布（B，风险高）要么一个是画布一个是网格
  （A，不对称）。取舍：**选 A 的不对称**，理由是单位的引用语义本就与方块同处一块画板，拆出第二块画布
  会割裂连线视图；单位页定位为"单位清单 + 属性栏"，与资源管理页同构。
- **无框窗口 vs 可用性**：无框后系统不再提供边缘缩放与系统标题栏。取舍：**本轮只做拖动 + 三按钮 +
  双击最大化**，不做八向边缘缩放（记入 Open questions），因为应用默认最大化、且本项目此前也一直
  以最大化为主用法。

### Synthesis path

把"同一记录两个入口"综合成一条规则：**资源管理/单位构筑两页是"浏览器"（点卡片 = 选中，右侧属性栏
编辑，不跳页）；方块构筑页是"流程编辑器"（画布 + 左侧分类栏，点分类栏 = 新建并落到画布）**。两处共享
同一个 `controller.select(...)`，因此选中状态天然一致。

## Critic verdict（v1）

| 维度 | 状态 | 备注 |
|---|---|---|
| Principle consistency | ✓ | 与「最小代码 / 不丢能力」一致 |
| Alternative exploration | ✓ | B/C 均有 invalidation rationale |
| Risk mitigation clarity | ✗ | "底部四页去向""科技研究页双编辑区"只在 Architect 段落，未进 Risks 表 |
| AC testability | ✓ | AC-1~6 均可二值断言 |
| Verification concreteness | ✗ | 无框窗口的验证只写了 `StageStyle`，没说按钮回调/拖拽/最大化的验证方式 |
| File/line coverage | ✓ | 步骤均 cite |
| Pre-mortem | n/a | 非 deliberate |

### Verdict: REVISE

待改项：
1. Risks 表补「底部四页无界面入口」「科技研究页与属性栏双编辑区」「无框窗口不可边缘缩放」三条 + mitigation。
2. Verification 补无框窗口的具体验证口径（`StageStyle` 断言 + 三按钮回调触发 + 双击标题条切换最大化 +
   `confirmExit()` 被调用的既有测试复用）。
3. 「分页圆点」给出可判定口径（只在网格超过一页时出现；页数 = 卡片数 / 单页容量；点击切页）——
   否则实现者会做成纯装饰。

## Planner draft（v2，已合入反馈）

- Risks 表补 3 条 + mitigation（见下）。
- Verification 段补无框窗口四项（含 `confirmExit()` 复用既有 `AppShell.confirmExit()` 测试）。
- 分页圆点改为可判定：**单页容量 = 视口能放下的卡片数；卡片数 ≤ 容量时不显示圆点**；圆点=页数，点击切页。
- AC-3 增加"圆点数量 == 页数"的可断言口径。

## Critic verdict（v2）

| 维度 | 状态 | 备注 |
|---|---|---|
| Principle consistency | ✓ | — |
| Alternative exploration | ✓ | — |
| Risk mitigation clarity | ✓ | 9 条 risk 逐条有 mitigation |
| AC testability | ✓ | 含圆点页数、无框三按钮、几何尺寸 |
| Verification concreteness | ✓ | 命令 + 断言口径 + 出图清单 |
| File/line coverage | ✓ | 全部步骤 cite 到文件与行 |
| Pre-mortem | n/a | 非 deliberate |

### Verdict: APPROVED（合入 3 处措辞修正）

### Reservations

- 我保留意见：**"分页圆点"仍是我对 SVG 的推断**。参考里它是 15 个等距圆点，没有任何文字说明它分页；
  也可能是"每页 N 条"的静态装饰。缓解：实现为"仅当卡片数超过单页容量才显示、圆点=页数、可点击"，
  并把这条判断写进 ADR，出图时肉眼比对；若你本意不是分页，改起来只是隐藏一个 60px 行。
- 我保留意见：**无框窗口是唯一"测试网之外"的改动**。`Snapshotter` 只截 `Scene`，看不到系统标题栏是否
  真的消失；`StageStyle.UNDECORATED` 只能断言属性，不能证明视觉。缓解：手工实跑一次 `run.bat`
  并在小结里附截图路径；同时保留 `App.java` 里一行开关式注释，回退只需删两行。

## Implementation steps

阶段一：外壳结构（标题条 / 菜单行 / 选项卡行 / 子行 / 属性栏）
1. `ui/AppShell.java`：
   - 新增 `buildTitleStrip()`（36px：左侧拖动区 + 右侧 3 个窗口按钮），删除 `buildHeader()`(222-236) 与
     `buildActionRow()`(238-265)；`BOTTOM_STRIP_VISIBLE`(61) 保持 `false` 并补注释说明四页去向。
   - `rebuild()`(150-166) 改为：`setTop(new VBox(buildTitleStrip(), buildMenuBar()))`；
     `setCenter(new BorderPane(centerColumn, null, inspector, null, null))`，其中
     `centerColumn = new VBox(buildTabStrip(), buildSubHeader(), viewFor(selected))`；删掉 `setLeft(...)`。
   - `buildCenter()`(195-203) 改为 6 个 `workspaceTab(...)`，「扩展脚本」用 `tab.setDisable(true)`。
   - 新增 `buildSubHeader()`：按当前选项卡给 64px 子行（资源管理=类型芯片；方块构筑=类型选择器；
     星球战役=添加行星/添加区块；其余留空）。
2. `core/fmt/Workspace.java:9-25`：新增 `units`（单位构筑）与 `script`（扩展脚本，不参与 `of`）；
   `of()` 改为 `item/liquid/weather/status/team → assets`、`block → canvas`、`unit → units`、
   `planet/sector → cosmos`。
3. `ui/AppShell.java:328-342` `addContentRouted`：`Workspace.canvas` 分支保持不变（画布新建 + 级联），
   其余走 `addContent(record)` + `showWorkspace`。
4. `src/main/resources/messages.properties` / `messages_zh_CN.properties`：
   `workspace.assets=Resources` / `资源管理`、`workspace.canvas=Block Builder` / `方块构筑`、
   `workspace.units=Unit Builder` / `单位构筑`、`workspace.cosmos=Campaign` / `星球战役`、
   `workspace.techTree=Research` / `科技研究`、`workspace.script=Scripting` / `扩展脚本`、
   `panel.properties=Properties` / `属性栏`、`panel.preview=Preview` / `预览效果`、
   `window.minimize/maximize/close`、`script.placeholder`（禁用页的说明文案）。

阶段二：资源管理页（参考(2)）
5. `ui/ItemsPane.java`：改成「类型芯片行（9 个，选中态样式类 `chip.active`）+ 分页圆点行 + 卡片网格
   （232×64、色条 16、列距 248）+ 中央预览框（480×560）」；卡片复用现有
   `tile/tile-thumb/tile-name/tile-id/tile-tag` 命名体系但换尺寸；圆点 = 页数（卡片数 ≤ 单页容量时隐藏）。
6. `ui/AppShell.java` `buildSubHeader()`：资源管理的 64px 子行放类型芯片（与 `ItemsPane` 共享选中类型）。

阶段三：方块构筑页（参考(1)）
7. `ui/AppShell.java` `buildPalette()`(416-435) / `paletteRow()`(437-467)：从 `setLeft` 改为搬进
   「方块构筑」选项卡内的 `HBox(palette 240px, canvas)`；行高 48、行宽 220、左侧 16px 色条、右侧
   `folderName · 数量`（保持现状文案）。
8. `ui/NodeCanvasPane.java`：**不动**（只换父容器）。

阶段四：单位构筑页 + 扩展脚本占位
9. `ui/ItemsPane.java`：加一个"只看某类型"的构造参数/过滤入口，单位构筑页复用它（`ContentType.unit`）。
10. `ui/AppShell.java`：`viewFor(Workspace)` 把 6 个选项卡映射到 视图（assets→资源网格、
    canvas→HBox(分类栏, 画布)、units→单位网格、cosmos→`CosmosPane`、techTree→`TechTreePane`、
    script→占位 Label）。

阶段五：无框窗口
11. `App.java:36-50`：`stage.initStyle(StageStyle.UNDECORATED)`；`stage.setMaximized(true)` 保留；
    新增 `shell.setWindowActions(stage::setIconified, () -> stage.setMaximized(!stage.isMaximized()),
    () -> { if (shell.confirmExit()) stage.close(); })`；标题条拖动与双击最大化在 `AppShell` 内实现
    （拖动时忽略最大化态）。
12. `src/main/java/dev/modmaker/ui/AppShell.java`：新增 `setWindowActions(Runnable, Runnable, Runnable)`
    与标题条鼠标处理（`setOnMousePressed`/`setOnMouseDragged`/双击）；三按钮加 Tooltip。

阶段六：样式与验证
13. `src/main/resources/app.css`：按上表写入新几何（36/64/64/64、396/128、120×64 格、240/220×48、
    卡片 232×64、色条 16、预览框 480×560、属性栏输入 128×20、分块 360×96、行 360×48），并更新顶部
    token 注释表。
14. `src/test/java/dev/modmaker/ui/UiSmokeTest.java`：
    - 改写 `prototypeThemeSizesHeaderTabsAndPalette`（36/64/64/64 + 6 选项卡/120 格宽 + 属性栏 396/128）。
    - 改写 `assetsGridReplacesTheTableAndSelectsTiles`（卡片 232×64、色条 16、圆点=页数）。
    - 新增 `paletteLivesInsideTheBlockBuilderTab`（`getLeft()==null`、画布页内存在 240px 分类栏）。
    - 新增 `scriptTabIsADisabledPlaceholder`（6 个选项卡、最后一个 `isDisable()`）。
    - 新增 `windowActionsAreWiredToTheStage`（三按钮回调被调用、`confirmExit()` 复用既有守卫）。
    - 既有 `shellRendersProjectCards` / `techTree...` / `cardsOutsideTheCanvasAreClipped` 按新几何**显式更新**。

## Workspace setup

- 实施前运行 `git status --short`（上次 **101** 项）与 `git branch --show-current`（**`main`**）。
- **本轮不建 worktree**，与第十二/十三轮同因：HEAD(`0dabb0c`) 落后工作区十余轮改动，从 HEAD 切出的
  worktree 会缺掉本 plan 依赖的代码（科技树、UI 重构、无框窗口前的 `App.java`）。继续原地实现。
- 用户一贯要求「暂不提交」；每阶段结束跑 `gradlew test` 并按需出图。

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| **底部四页（字符串表/精灵/校验/日志）本轮仍无界面入口** | 保持 `BOTTOM_STRIP_VISIBLE=false` 但把"去向未定"写进 ADR Follow-ups；属性栏的 `.report` 分块继续承担"当前记录的校验"，全局日志暂时只能从菜单「打开测试目录」间接看 |
| **科技研究页自带右编辑器，与全局属性栏并置** | 本轮**不改** `TechTreePane` 内部结构（外科手术式改动），在 drift 中记录"该页会出现两个编辑区"；若要合并，另开一轮 |
| **同一记录两个入口（资源管理芯片含方块/单位，同时有构筑页）** | 定规则：浏览器页点卡片 = 只选中（属性栏编辑），不跳页；构筑页分类栏点击 = 新建 + 落到画布 |
| **无框窗口后系统不再提供边缘缩放** | 默认最大化 + 只做拖动/三按钮/双击最大化；八向缩放列入 Open questions，不做 |
| **`Stage.setMaximized` 在无框 + 多显示器/DPI 下铺满异常** | 只用系统 `setMaximized`，不手动算 x/y/w/h；实跑一次核对不遮任务栏 |
| **拖动逻辑在最大化状态下误移动窗口** | 拖动/双击前先判 `stage.isMaximized()`，最大化时忽略拖动、双击执行还原 |
| **关闭按钮绕过未保存守卫** | 关闭按钮回调复用既有 `AppShell.confirmExit()`（`App.java` 里 X 的那条），并加断言 |
| **`Workspace.of()` 改路由影响 3 处调用点** | `addContentRouted`(328)、`ItemsPane.listed()` 过滤、`searchNext`(391) 三处同一次改完并各加/改断言 |
| **`setLeft` 移除导致既有断言失败** | 只允许"按新几何显式更新"，不得删除断言；新增 `paletteLivesInsideTheBlockBuilderTab` 锁住新位置 |
| **24px 字号 + 7 个菜单 + 6 个选项卡的宽度预算** | 菜单 ≈7×120=840px、选项卡 6×120=720px，均 < 1920；出图复核一次 |

## Verification steps

- **AC-1/2/3/4/5（可判定）**：`gradlew test --tests "*UiSmokeTest*"`；断言前一律
  `applyCss(); layout();`（未布局读到 0 会假绿，见 receiving §8）。尺寸口径：
  标题条 36±2、菜单行 64±2、选项卡行 64±2、子行 64±2、格宽 120±2、属性栏 396±2、头 128±2、
  分类栏 240±2、分类行 48±2、卡片 64±2、色条 16±1、预览框 480×560±4。
- **AC-3 圆点**：卡片数 ≤ 单页容量时圆点行不可见；卡片数 > 容量时圆点数 == 页数。
- **AC-6 无框**：`assertEquals(StageStyle.UNDECORATED, stage.getStyle())`（在 `onFx` 内造一个
  `Stage` 走同一段 `initStyle` 代码路径，或断言 `App` 暴露的静态配置）；三按钮回调触发计数 == 1；
  关闭回调触发 `confirmExit()`（复用既有 `confirmExit` 测试）；再**手工实跑 `run.bat`** 确认系统
  标题栏消失、三按钮可用、拖动与双击最大化生效。
- **AC-7**：`.\gradlew.bat test --console=plain` → 0 失败 0 跳过（基线 279）。
- **AC-8**：六个选项卡 + 标题条各出一张快照（`snapshots/ui-*.png`），用 Read 逐张肉眼比对 SVG。
- **AC-9**：`.\make-portable.bat` 或 `gradlew jar` + 复制进 `dist\ModMaker\app` 后
  `--snapshot` 出图。

## ADR

- **Decision**：按 Option A 落地 —— 外壳就地重排成 SVG 的四行结构（36 自绘标题条 / 64 菜单行 /
  64 选项卡行 / 64 子行）+ 右侧 396px 属性栏；资源管理与单位构筑复用同一套"资源网格"，方块构筑把
  现有画布连同左侧 240px 分类栏搬进选项卡内部；窗口改 `StageStyle.UNDECORATED` 并把三按钮画进标题条。
- **Drivers**：保真度（用户明确对齐 SVG）> 可分批 > 风险可控 > 不引入新框架。
- **Alternatives considered**：
  - Option A（**chosen**）；
  - Option B 单位页用第二个画布实例 —— **rejected**：双实例交互/性能回归面过大，远超版面收益；
  - Option C 自定义选项卡条共用单画布 —— **rejected**：清零 4 条既有断言并重写选项卡机制。
- **Why chosen**：A 是唯一能在不改画布内核、不删断言的前提下交付全部版面要求的选项。
- **Consequences**：
  - 正面：版面与参考一致；外壳结构清晰（四行 + 一栏）；画布/命令栈/落盘零改动。
  - 负面：单位构筑页与方块构筑页不对称（网格 vs 画布）；底部四页暂无界面入口；无框窗口失去系统
    边缘缩放；`Workspace.of()` 语义变化会波及 3 处调用点。
- **Follow-ups**（本轮明确不做）：
  - 底部四页的正式归宿（属性栏分块 / 浮动面板 / 快捷键弹层）。
  - 无框窗口的边缘缩放（八向 resize 手柄）。
  - 科技研究页与全局属性栏的编辑区合并。
  - 「分页圆点」若经确认为装饰而非分页，改为静态指示。

## Review trail

- Planner draft v1：Option A，5 阶段；缺口 = 未写 `Workspace.of` 影响面、未定义圆点语义、未写无框失败面。
- Architect challenge v1：steelman = **"对齐版面"与"不丢能力"冲突**（底部四页入口、科技研究双编辑区、
  同记录双入口）；tension = 版面保真 vs 能力可达 / 单位页一致性 vs 最小改动 / 无框 vs 可用性；
  synthesis = 浏览器页"只选中不跳页"、构筑页"点击即新建"。
- Critic verdict v1：**REVISE** —— 三条风险未进表、无框验证口径缺失、圆点不可判定。
- Planner draft v2：补 9 条 Risks + 无框四项验证 + 圆点可判定口径（页数 = 卡片数/单页容量，超出才显示）。
- Critic verdict v2：**APPROVED**，合入 3 处措辞修正，留 2 条 Reservation（圆点语义是我对 SVG 的推断；
  无框窗口在现有快照通道之外，只能属性断言 + 手工实跑）。
- Final iterations: 2 / 3
