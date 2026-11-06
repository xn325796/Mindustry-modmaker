# UI 层重构（对齐 Mindustry 模组工作台原型）Implementation Plan

> Status: APPROVED
> Source: 用户请求（2026-10-13）`markdown/Mindustry模组工作台原型.html` + 经 AskUserQuestion 澄清的范围：
> **视觉 + 信息架构**、**原样照搬 24px 级尺度**、用 dev-plan 出方案
> Mode: (default) 完整 Planner → Architect → Critic loop
> Iterations: 2 / 3
> Author: 用户（AI 代拟）
> Last updated: 2026-10-13

## Requirements summary

把 UI 层重构成原型 `Mindustry模组工作台原型.html` 的视觉与信息架构：圆角取代零圆角、主色由琥珀
`#ffb454` 换成 `#FFA347`、字号从 12.5px 基号提升到**原型的绝对尺度**（菜单/选项卡/分类名 24px）、
左分类栏改为「16px 色条 + 24px 中文名 + 右侧 `en · 数量`」、中央资产视图由表格改为**卡片网格**、
右侧详情面板改为**分块 + 预览框 + stat-list + 报告条目**、弹窗改为原型的深色模态样式。
功能、数据流、画布交互不变；不新增原型里的模拟面板（工厂/战役/脚本）。

## Acceptance criteria

- **AC-1** `app.css` 全量换 token：圆角 4/6/7/8/20px、主色 `#FFA347`、面板色族与原型一致；文件顶部以注释登记完整 token 表（JavaFX 无 CSS 变量，色值只能就地书写，故必须有这张表）。
- **AC-2** 主区选项卡（`center-tabs`）为 64px 高、选中项底部 4px `#FFA347` 下划线、标签 24px；底座色 `#1C2022`、选中 `#121516`。
- **AC-3** 左分类栏每行 48px：左侧 16px 宽的分类色条、24px 中文名、右对齐 13~16px 的 `en · 数量`；选中行加 3px 右侧琥珀条 + 加粗。
- **AC-4** 中央「物品与液体」由 `TableView` 改为**卡片网格**（`minmax(228px,1fr)` 自适应列 + 16px 间隙），卡片含 56px 缩略区、19px 名称、13px ID、彩色分类标签，有错误时右上 8px 红点。
- **AC-5** 右侧详情面板为「标题块（22px 名称 + 13px 副题）→ 分块内容（18px 内边距的 `.block`）→ 预览框（88px 缩略）→ `.stat-list` 键值行 → `.report` 报告条目（3px 彩色左边框）」。
- **AC-6** 顶部新增 36px 应用头栏（左侧 `MINDUSTRY MOD STUDIO` 标题，右侧项目名）与 64px **主操作行**（新建/导入/保存/校验/打包/实机 六个大按钮 + 右侧脏标记状态）。
- **AC-7** 弹窗（35 处 `new Alert`）视觉由 `.dialog-pane`/`.header-panel`/`.button-bar` 选择器统一定制为原型深色模态，**调用点零改动**。
- **AC-8** 画布卡片几何随字号放大后，`AutoLayout` 的间距同步，画布仍完整可交互（拖拽/连线/框选/删除）。
- **AC-9** `ContentType.color()` 对齐原型分类色；已知副作用：`JsonCanvas.java:43` 会把该色写入 panel 的 `board.json`，需确认无 golden 往返断言受影响。
- **AC-10** 全量 `gradlew test` 0 失败 0 跳过；`UiSmokeTest` 的既有像素/场景图断言全部仍然通过（或按新几何**显式**更新，不得删除）。
- **AC-11** 逐屏出图复核：`ui-smoke` / `ui-assets` / `ui-techtree` / `ui-cosmos` / `ui-sprites` / `ui-appearance` / `ui-system` 七张快照全部重出并肉眼比对原型。

## RALPLAN-DR

### Principles

- baseline「最小代码」：优先改样式与尺寸，不新建 UI 组件框架。
- baseline「外科手术式改动」：**不动**画布交互、`ProjectIo` 落盘、`Controller` 命令栈；本次 diff 只应包含视觉/布局/文案。
- **不丢能力**：原型的 6 个大按钮之外，应用的 25 个菜单动作必须全部仍可达。
- **测试网不许削**：既有断言只能按新几何更新，不能删。
- 尺度照抄：原型是 1920×1020 设计，用户已选「原样照搬」，因此字号/行高**直接使用原型的绝对像素值**，不做等比收敛。

### Decision drivers

1. **保真度**（用户选了"视觉 + 信息架构"）—— 结构必须动，不只是换色。
2. **不丢功能**（这是真实编辑器，不是原型演示）—— 菜单/画布/检查器的能力面不能缩。
3. **可验证**（每屏都能出图比对；像素断言是唯一可靠的肉眼通道）。
4. **改动可分批**（≈7300 行 UI 层，必须能分阶段落地并逐阶段保持全绿）。

### Viable options

**Option A（favored）：样式主导 + 就地结构改造**
- 实现思路：整篇重写 `app.css` 为原型 token；在各 pane 内部**就地**改尺寸与分块（`AppShell` 加头栏/操作行、`ItemsPane` 表格→网格、`RecordInspector` 分块、调 `buildPalette`/`buildToolBar`/卡片几何）。
- 改动文件：`app.css`(703)、`AppShell`(969)、`ContentCard`(257)、`CarrierCard`(163)、`CanvasCard`(24)、`ItemsPane`(285)、`RecordInspector`(671)、`TextTreePane`(367)、`CosmosPane`(245)、`SpritePane`(195)、`StringsPane`(154)、`IssuesPane`(61)、`ContentType`(fmt)、`UiSmokeTest`、中英词条。
- Pros：不新增抽象层；每屏可独立出图验证；既有行为（画布/撤销/落盘）零触碰。
- Cons：样式规则分散在 CSS 与各处 `setStyle` 里，后续改色仍需全量替换（JavaFX 无 CSS 变量）。

**Option B：先建一套 UI 组件层（`SectionBlock`/`Pill`/`PrimaryButton`/`CardGrid`），再让所有 pane 改用它**
- Pros：样式集中、复用度高，后续新面板自动统一。
- Cons：要重写 8 个 pane 的构造（≈3000 行），与 baseline「最小代码/外科手术式改动」正面冲突；把 5 轮迭代积累的行为（
  画布交互、行内编辑、无损内联）一次性置于回归风险中。**改动面远超收益**。

**Option C：只重写 `app.css`，Java 结构一行不动**
- Pros：最小、最安全。
- Cons：**达不到用户选定的层级** —— 左分类栏的 `en · 数量`、卡片网格、分块面板、大按钮行都依赖结构改动；纯 CSS 只能得到"颜色和圆角变了"的假象。**被否**。

> Option B 的 invalidation rationale：本项目的 UI 层已有 5 轮沉淀的交互行为与断言网，重写构造等于把验证面清零；
> 而 Option A 能让每一步都落在"改完即出图可比对"的闭环里。Option C 的 invalidation rationale：用户明确选择了信息架构层，
> 纯 CSS 无法交付 AC-3/4/5/6。

## Planner draft（v1）

选定 Option A，按 6 个阶段落地：① token 与全局色 ② 顶部头栏 + 操作行 + 菜单 ③ 左分类栏 ④ 中央（卡片网格 + 选项卡）
⑤ 右侧分块面板 ⑥ 其余 pane 校对 + 弹窗 + 出图。**v1 缺口**：未说明"资产页表格→网格会丢 P4-1 行内编辑"这一回归，
也未定义 24px 字号下左分类栏 260px 宽度是否够用。

## Architect challenge（v1）

### Steelman against Option A

最强反驳：**"原样照搬 24px"与"不丢功能"直接冲突**。原型的左分类栏固定 260px 宽、中文名 24px、右侧 `en · 数量` 72px；
应用的中文分类名更长（"星球与区块"、"单位指令"、"物品与液体"），24px 下 136px 的 `.zh` 放不下 → **必然截断**。
同理，24px 的选项卡标签 4 个就占 720px，加上 22px 的右侧面板标题，窄屏必然挤压。
若反驳成立，plan 必须给出**溢出时的处置顺序**（先放行高/栏宽、再降字号），而不是等到实现完才发现截断。

### Tradeoff tensions

- **尺度保真 vs 可用性**：24px 中文在 260px 栏内需要 ~5 个汉字即溢出。取舍依据：**先按原型照搬落一屏**，
  实测溢出位置记录在案，只对实测溢出的元素做最小让步（放宽栏宽 → 仍然溢出才降该处字号），并把让步逐条记入 drift。
- **信息架构保真 vs 能力不缩水**：原型把菜单压成 6 个按钮、没有菜单栏；应用有 25 个菜单动作。
  取舍依据：**保留 MenuBar**（紧凑重排），另加原型的操作行承载 6 个主操作。
- **卡片网格 vs 行内编辑**：P4-1 刚做完的表格内联编辑会在表格消失后失效。取舍依据：原型本身就是"网格 + 右侧面板编辑"，
  检查器（schema 驱动）**已能编辑全部字段**（含颜色/硬度/粘度），所以能力不丢、只是入口从行内变为右侧。

### Synthesis path

把"表格→网格"做成**替换而非删除**：网格卡片复用 `ContentType.color()` 与既有的"缺失精灵/错误"信息源，
点击卡片 → `controller.select(record)` → 右侧检查器（既有、已覆盖全部字段）承接编辑。

## Critic verdict（v1）

| 维度 | 状态 | 备注 |
|---|---|---|
| Principle consistency | ✓ | — |
| Alternative exploration | ✓ | B/C 均有 invalidation rationale |
| Risk mitigation clarity | ✗ | "24px 溢出"只被 Architect 提出，未进 Risks 表；"P4-1 行内编辑失效"未进 AC/风险 |
| AC testability | ✗ | AC-2/3/4/5 全是视觉描述，**不可二值判定** |
| Verification concreteness | ✗ | 只说"出图复核"，没给可执行的断言口径 |
| File/line coverage | ✓ | 步骤均 cite |
| Pre-mortem | n/a | 非 deliberate |

### Verdict: REVISE

待改项：
1. Risks 表补「24px 中文在固定栏宽下溢出」+ mitigation，并写明**处置顺序**。
2. AC 增加"行内编辑入口迁移"一条（能力不丢，入口改为右侧面板），避免被当成静默回归。
3. 把视觉 AC 转成**可判定**口径：给出具体像素阈值断言（行高/圆角/主色/网格列宽），由 `UiSmokeTest` 直接断言样式类与布局尺寸，
   而不是"看起来像原型"。
4. Verification 段给出具体测试方法（`lookup` 样式类 + 布局尺寸断言 + 快照肉眼复核）。

## Planner draft（v2，已合入反馈）

- Risks 新增「24px 溢出」+ 处置顺序；新增「行内编辑入口迁移」条目。
- AC-4 改为可判定：断言网格列宽数量随宽度变化、卡片存在 56px 缩略区与分类标签节点。
- AC-2/3 改为可判定：断言选项卡/分类行的 `getHeight()` 与选中态样式类。
- 新增 AC：右侧面板编辑入口可用（选中卡片后检查器显示该记录字段）。

## Critic verdict（v2）

| 维度 | 状态 | 备注 |
|---|---|---|
| Principle consistency | ✓ | — |
| Alternative exploration | ✓ | — |
| Risk mitigation clarity | ✓ | 6 条 risk 逐条有 mitigation |
| AC testability | ✓ | 尺寸/样式类/节点存在性均可二值判定 |
| Verification concreteness | ✓ | 命令 + 断言口径 + 快照清单 |
| File/line coverage | ✓ | 全部步骤 cite 到文件与行 |
| Pre-mortem | n/a | 非 deliberate |

### Verdict: APPROVED（合入 2 处措辞修正）

### Reservations

- 我保留意见：**AC-2/3 的"布局尺寸断言"在 JavaFX 里需要一次真实的 `layout()` 才会生效**，而 `UiSmokeTest` 的既有用例
  都是手工搭 Scene 的。若我在断言前漏掉 `shell.layout()`，断言会读到 0 而"假通过"。实现时必须先 `applyCss()+layout()`
  再读尺寸；这一点我写进了 Verification，但它是本轮最可能出假绿的地方，特此保留。
- 我保留意见：`ContentType.color()` 一旦改值，`JsonCanvas` 写出的 `board.json` 颜色随之变化。我没有在 plan 里确认
  是否存在 golden 往返断言依赖旧的 `#3f3f46`；**实现时若命中就必须显式更新**，不能默默改数据。

## Implementation steps

阶段一：token 与色彩地基
1. `src/main/resources/app.css`（703 行）整篇重写为原型 token：
   - 顶部注释登记 token 表（色族 `#07080A/#0B0C0F/#0E1113/#121516/#16181D/#171B1C/#1C2022`、主色 `#FFA347`、
     分类色 `#40BFBF/#5489F2/#F27C54/#61BAE6/#A37AF5/#8282EB/#77CF77/#F57AB8`、圆角 `4/6/7/8/20`、
     字号 `12/13/15/16/19/22/24`）。
   - 选择器映射：`.root`(12-29) → 新基号；`.menu-bar`(37-68) 紧凑化；`.tool-bar`(98-112) 64px；
     `.tool-button`(116-137) 38px + `.key`(实心琥珀) / `.danger` + `.mini`(30px)；`.panel`(291-294)；
     `.palette-row`(303-310)/`.type-dot`(313-319)/`.palette-label`(321-328) → 48px 行 + 16px 色条 + 右 `en · 数量`；
     `.card`(338-388) → 8px 圆角 + 1px `#21272C` + hover 上浮；`.center-tabs`(533-566) → 64px + 4px 琥珀下划线；
     `.tree-view`(642-662)/`.list-view`(620-640)/`.table-view`(570-618) → 新 token；
     新增 `.app-header` / `.action-row` / `.block` / `.field` / `.stat-list` / `.preview-box` / `.report` / `.chip` / `.tile`。
   - 新增 `.dialog-pane` / `.dialog-pane > .header-panel` / `.dialog-pane > .button-bar > .container > .button` 三组选择器（AC-7）。
2. `core/fmt/ContentType.java:56` `color()` 对齐原型分类色（AC-9）。

阶段二：顶部与主操作行
3. `ui/AppShell.java`
   - 新增 `buildHeader()`（36px：左标题 `header.title` + 右侧项目名），`buildActionRow()`（64px：六个大按钮
     `toolbar.new/import/save/validate/build/launch`（复用既有私有方法 `newProject`(437)/`importZip`(515)+`importFolder`(529)/
     `save`(552)/`validate`(577)/`buildZip`(617)/`launchGame`(636)）+ 右侧脏标记）。
   - `rebuild()`(137-157) 的 `setTop(new VBox(buildMenuBar(), buildToolBar()))` → `setTop(new VBox(buildHeader(), buildActionRow(), buildToolBar()))`，
     **MenuBar 保留**（AC-6 的 deviation，理由见 ADR）。
   - `toolButton`(1021-1033) 增加变体参数（`key`/`danger`/`mini`）。

阶段三：左分类栏
4. `AppShell.buildPalette`(355-404) 与 `paletteRow`(376-404)：行高 48px、色条 16px、中文名 24px、
   右侧 `en · 数量`（新词条 `palette.count`）；计数来源 `controller.project().contents` 按 `ContentType` 分组。

阶段四：中央
5. `ui/ItemsPane.java`(285) 的 `TableView` 段替换为 `GridView`（`ScrollPane` + `TilePane`/`GridPane`，卡片 228px 最小宽）：
   - 卡片节点：56px 缩略区（`SpritePane` 的像素图渲染能力若不可复用则先放分类色块）、19px 名称、13px ID、
     分类色标签、错误红点；
   - 点击卡片 → `controller.select(record)`（编辑入口迁到右侧检查器，AC-4 与 Risks 里的"入口迁移"）。
6. `AppShell.buildCenter()`(179-187) / `workspaceTab`(189-193)：选项卡 64px + 4px 下划线。

阶段五：右侧面板
7. `ui/RecordInspector.java`(671)：外层改为「标题块 + 可滚动分块体」；字段行改为 `.field`（12px 暗标签 + 输入）；
   长文本改 `textarea`；新增 88px 预览框与 `.stat-list` 摘要；校验结果条目改 `.report` 样式。

阶段六：其余 pane 与验证
8. `ui/TechTreePane`(367)/`ui/CosmosPane`(245)/`ui/SpritePane`(195)/`ui/StringsPane`(154)/`ui/IssuesPane`(61)：
   按新 token 校对间距、分块、列表行高（结构不动）。
9. `ui/ContentCard`(257)/`CanvasCard`(24)/`CarrierCard`(163)：卡片几何与字号放大；`core/io/AutoLayout` 间距同步。
10. `src/main/resources/messages*.properties`：新增 `header.title`/`palette.count`/`toolbar.*`/`panel.*` 等键，中英同步。
11. `src/test/java/dev/modmaker/ui/UiSmokeTest.java`：更新 `cardsOutsideTheCanvasAreClipped`(479-524) 的硬编码点击坐标
    与像素采样点以匹配新卡片几何；新增样式/尺寸断言（见 Verification）。

## Workspace setup

- 实施前运行 `git status --short`（上次为 **92 项**）与 `git branch --show-current`（**`main`**）。
- **本轮不建 worktree**，与第十一轮同因：HEAD(`0dabb0c`) 落后于工作区十余轮改动，从 HEAD 切出的 worktree 会缺掉
  本 plan 依赖的代码（`TechTreePane`、`LinkGraph`、可移植化后的 `SearchPaths` 等）。
- 继续原地实现，与用户「暂不提交」的一贯要求一致。
- 每阶段结束跑一次 `gradlew test` 并**重出快照肉眼复核**；阶段之间不得留下"样式半新半旧"的中间态。

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| **24px 中文在 260px 分类栏内溢出**（"星球与区块"等 5 字 ≈ 120px + 色条 16 + `en · 数量` 72 > 260） | 处置顺序固定为：① 先放宽栏宽 ② 再让 `en · 数量` 换行/截断 ③ 最后才降该处字号。每次让步逐条记入 plan 的 drift 段 |
| 卡片几何变大破坏 `cardsOutsideTheCanvasAreClipped`(479-524) 的硬编码坐标与像素采样点 | 按新几何**显式更新**测试常量（断言口径不变：卡片仍被 canvas 裁剪、不侵占顶栏与左栏） |
| `AutoLayout` 间距与新卡片尺寸不匹配 → 「整理」后卡片重叠 | 阶段六同改 `core/io/AutoLayout` 的间距常量并补/改其单测 |
| 表格→网格丢掉 P4-1 行内编辑（用户可能已习惯） | 明确这是"入口迁移"而非能力删除：右侧检查器已覆盖全部字段（含颜色/硬度/粘度）；plan 的 AC 与 drift 均记录 |
| 35 处 `new Alert` 依赖 JavaFX 默认 dialog 皮肤 | 只在 CSS 里定制 `.dialog-pane` 系列，调用点零改动；出图验证一次 `confirmExit` 弹窗 |
| `ContentType.color()` 改值会改变 `JsonCanvas.java:43` 写出的 `board.json` 颜色 | 实现时若命中 golden 往返断言就显式更新，并在 drift 段记录"这是数据变化不是纯视觉" |
| JavaFX 无 CSS 变量，改色需全局替换 | `app.css` 顶部登记完整 token 表 + 注释指明"改色请全量搜索这些字面值" |
| 断言在未 `layout()` 时读到 0 → **假绿** | Verification 里所有尺寸断言前必须 `applyCss(); layout();`（Critic Reservation 1） |

## Verification steps

- **AC-1/7**：`gradlew test --tests "*UiSmokeTest*"`；
  `app.css` 顶部 token 表存在（可用 `grep`/Read 核对），且 `new Alert(` 调用点数仍为 35（`grep -c` 前后一致）。
- **AC-2/3（可判定）**：`UiSmokeTest` 新增用例，`shell.applyCss(); shell.layout();` 之后：
  - 断言 `centerTabs` 内 `.tab` 的 `getHeight()` == 64（±2）；
  - 断言选中 tab 的样式类含选中态；
  - 断言左分类栏首行 `getHeight()` == 48（±2）、行内存在色条节点（宽 16）与计数标签。
- **AC-4（可判定）**：`UiSmokeTest.assetsGridReplacesTheTable` — 断言资产页不再含 `TableView`、含网格容器；
  缩放 scene 宽度后断言列数增加（自适应）；卡片节点含 56px 缩略区与分类标签。
- **AC-5**：断言右侧面板存在 `.block` 分节节点、`.stat-list` 与预览框节点。
- **AC-6**：断言顶部三行的高度（36/64/30±2）与六个主操作按钮的文本来自 `Messages`。
- **AC-8**：`gradlew test --tests "*UiSmokeTest*" --tests "*ProjectControllerInteractionTest*"`；
  画布交互用例全绿 + 出图肉眼确认卡片未重叠。
- **AC-9**：`gradlew test --tests "*JsonCanvasTest*" --tests "*RoundTripTest*"`，命中则显式更新并记录。
- **AC-10**：`.\gradlew.bat test --console=plain` → 0 失败 0 跳过（当前基线 278/35）。
- **AC-11**：七张快照全部重出，用 Read 工具**逐张肉眼比对原型**（这是唯一的视觉验收通道）。

## ADR

- **Decision**：按 Option A 落地——整篇重写 `app.css` 为原型 token，并在各 pane **就地**改造信息架构
  （顶部头栏+主操作行、左分类栏 48px、中央资产卡片网格、右侧分块面板、弹窗深色化）；尺度直接采用原型的绝对像素值。
- **Drivers**：保真度（用户选定"视觉+信息架构"）> 不丢功能 > 可验证 > 可分批。
- **Alternatives considered**：
  - Option A（**chosen**）；
  - Option B 先建 UI 组件层 —— **rejected**：要重写 8 个 pane 构造，与 baseline 冲突且把既有断言网清零；
  - Option C 纯 CSS —— **rejected**：无法交付左分类栏/卡片网格/分块面板/操作行，达不到用户选定层级。
- **Why chosen**：A 是唯一既满足"信息架构要对齐原型"、又不动画布/落盘/命令栈的选项，且每阶段都能出图验证。
- **Consequences**：
  - 正面：视觉与原型一致；改样式集中到 `app.css` 一处；功能面零缩水（MenuBar 保留、检查器承接编辑）。
  - 负面：24px 尺度会让左分类栏/选项卡在窄屏下拥挤（已定处置顺序）；`app.css` 因无变量而必须就地写色值；
    `ContentType.color()` 改值会顺带改变 `board.json` 里的颜色（数据变化，非纯视觉）。
- **Follow-ups**（本轮明确不做）：
  - **toast 提示条**（原型的 `#toasts`）：属于新的交互反馈通道，应用目前用「构建日志 + 状态栏」承载；单独一轮做。
  - 原型里的**工厂/单位/战役模拟/脚本控制台**面板：原型中是假数据演示，真实应用需要重新设计为真实功能。
  - 自定义模态弹窗组件（替换 35 处 `Alert`）：本轮只用 CSS 定制，机制不动。

## Review trail

- Planner draft v1：Option A，6 阶段；缺口 = 未提"行内编辑失效"与"24px 溢出"。
- Architect challenge v1：steelman = **"照搬 24px"与"不丢功能"直接冲突**（中文分类名在 260px 栏内 24px 必截断）；
  tension = 尺度保真 vs 可用性 / IA 保真 vs 能力不缩水 / 网格 vs 行内编辑；synthesis = 表格→网格做成"替换而非删除"，
  编辑入口交给既有检查器。
- Critic verdict v1：**REVISE** —— 风险未进表、视觉 AC 不可二值判定、Verification 无断言口径。
- Planner draft v2：补 Risks 6 条 + 处置顺序；AC 改可判定（尺寸/样式类/节点存在性）；Verification 落到具体断言与命令。
- Critic verdict v2：**APPROVED**，合入 2 处措辞修正，留 2 条 Reservation（`layout()` 前置防假绿；`ContentType.color()` 的数据副作用）。
- Final iterations: 2 / 3

## Plan drift（实施后逐条记录）

1. **MenuBar 与操作行并存**：plan 的 `setTop` 字面替换会丢掉 MenuBar，与"MenuBar 保留"自相矛盾。实际落地为
   `VBox(header, menuBar, actionRow, toolBar)`，25 个菜单动作全部保留。
2. **工具栏瘦身**（plan 未列，属"工具栏紧凑化"）：校验/打包/实机测试从 `buildToolBar` 移除（已在操作行），
   工具栏只留画布相关控件（类型/添加/自适应/撤销重做/整理/适应/搜索/构建到文件夹/停止/日志/画板）。
   能力不丢：三者仍在操作行与菜单里；"构建到文件夹"仍在工具栏与菜单里。
3. **`.field` → `.field-row`**：plan 写"字段行改为 `.field`（12px 暗标签 + 输入，上下布局）"。检查器的既有结构是
   「标签 + 编辑控件」横向行；改成上下布局会把 396px 面板拉高一倍。保留横向行并改用 `.field-row` 的圆角输入外观。
4. **分类色中 `item` 无原型对应**：原型 `--c-*` 只定义 8 个分类（liquid/block/unit/weather/status/planet/sector/team），
   没有 item。item 原值 `#ffb454` 等于旧主色，故按"item = 主色"的既有设计取原型主色 `#FFA347`（记作数据变化，
   会随 `JsonCanvas.java:43` 写进 board.json）。bullet/unitCommand/unitStance 非原型分类，保持原值。
5. **画布卡片几何未动**：`ContentCard` 260×130 / `CarrierCard` 260×64 与 `AutoLayout` 的
   CARD_WIDTH/CARD_HEIGHT/CARRIER_HEIGHT 本来就一致，只有字号从 13→14px（`headerHeight()` 是常量 54，不触发几何变化）。
   因此 AC-8 的"间距同步"无需改动，`cardsOutsideTheCanvasAreClipped` 的硬编码坐标与像素点也无需更新（实测仍通过）。
6. **检查器新增"校验"块**：AC-5 要求 `.report` 报告条目，原面板并无校验结果，故新增一个 `.report` 块，
   只显示当前记录的 findings（与资产网格红点同源：`validateQuietly()`）。
7. **资产网格缩略图为分类色块**：plan 允许"若 SpritePane 的像素图渲染能力不可复用则先放分类色块"，
   采用 56px 分类色块（真实精灵渲染留待后续）。

