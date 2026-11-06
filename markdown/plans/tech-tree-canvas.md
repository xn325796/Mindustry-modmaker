# 科技研究面板:游戏式画布树状图(2026-10-25)

> 来源:用户请求「科技研究面板做成和游戏一样的画布树状图效果,对齐源码实现,并功能化(读取与添加等)」
> 前置:weapons 结构化编辑器按用户决定搁置

## 对齐的源码事实(160.5)

- 游戏的科技树对话框是 **ResearchDialog**(v8 才改名 TechTreeDialog)。布局为 **BranchTreeLayout**(abego TreeLayout 移植,ui/layout/BranchTreeLayout.java),节点 60px、层级/节点间距均 20(ResearchDialog.java:333/341/351)。
- **根节点子代按左右对半分**:前 `ceil(n/2)` 用 `TreeLocation.top`(层级向下生长),其余用 `bottom`(向上),随后 `shift` 把上半树对齐到根的最终 Y(ResearchDialog.java:336-355)。层级轴为 Y、兄弟沿 X 展开(BranchTreeLayout.isLevelChangeInYAxis)。
- **边线只画 parent→child**(objectives 不画线);|dx|≈|dy| 且距离 ≤ 3×节点宽 时画斜线,否则画 L 形(先横后竖)(ResearchDialog.drawChildren:786-813)。
- **格式里没有 position 键**:ContentParser 的 research 对象只读 parent/requirements/objectives/planet/root/name/requiresUnlock(1337-1419),位置永远自动计算——编辑器同样不存位置。
- 游戏端尚无工具能对应的 "hidden" 字段;root 由 `root:true` 声明,planet 决定归属(Planet.init 以 `roots.find(n -> n.planet == this)` 自动认领)。

## 实现

### 核心层(纯 Java,可测)

- `core/layout/BranchTreeLayout`:游戏 BranchTreeLayout 的逐行移植(firstWalk/apportion/executeShifts/secondWalk/calcSizeOfLevels),Node 接口抽象掉 arc TreeNode;仅保留对话框用到的路径(均匀方块节点、awayFromRoot、top/bottom——left/right 逻辑仍在)。
- `core/layout/TechTreeCanvasLayout`:ResearchDialog.treeLayout 的等价封装——左右对半、top/bottom 两次布局、shift 对齐;常量 `NODE_SIZE=60`、`SPACING=20`。Box 节点带 payload(面板用它挂 TechTreeIndex.Entry)。

### UI 层(TechTreePane 重写)

- 画布:平移(拖拽空白)+ 缩放(滚轮,锚定光标,0.3–2.5×),单 Affine 保证映射确定;节点拖拽不触发平移。
- 读取:TechTreeIndex 森林逐根布局,多根树纵向堆叠;原版父节点组(灰)、问题节点(unresolvedParent/ignored/patchRefused,橙红)照常显示并带 tooltip。
- 节点:60px 方块,贴图优先(SpriteStore 按记录名取区域,如方块/单位),无贴图用首字母;下方名称标签;选中=白色边框(样式切换,不重建画布,平移缩放不丢)。
- 边线:游戏规则(斜线/L 形);模组边线 accent、原版边灰、问题边橙红。
- **功能化(全部经 `controller.setField(record, "research", …)`,与检查器 ResearchEditor 同一条写路径、可撤销)**:
  - 「挂到选中节点」:未接入记录 + 画布选中的父节点 → 写入 `parent`(模组节点用 `<mod>-<name>` 前缀拼写,游戏解析的第一优先且不会撞到同名原版;原版父节点用裸名)。
  - 「设为根节点」:未接入记录 → `{root:true}`;项目恰好只有一个星球时自动补 `planet`(游戏靠 planet 自动认领,缺省永远无法被 Planet.init 选中)。
  - 「移出科技树」:选中节点 → research 字段整体清空。
  - 补丁记录不可入树(游戏拒绝,ContentParser.java:1338-1341)。

### i18n / CSS

- 新键(中英双份):`techtree.pick.prompt`、`techtree.addChildTitle`、`techtree.addRootTitle`、`techtree.removeChildTitle`、`techtree.canvas.hint`、`techtree.canvas.empty`、`techtree.needsNode`。
- app.css 新增 `.tech-canvas/.tech-node(+.vanilla/.problem/.selected)/.tech-node-label/.tech-edge(+-mod/-vanilla/-problem)` 令牌,取色与主题表一致。

## 测试

- `TechTreeCanvasLayoutTest`(新,3 条):单链向下每层 80px、四子左右对半且镜像对称、布局确定性。
- `UiSmokeTest.techTreeTabShowsTheResearchForestAndEditsANode`(改):断言画布节点数、单子链保持根的 x、层距=80、选中高亮;原 ResearchEditor 的 root 切换/撤销断言保留。
- `UiSmokeTest.techTreeToolbarAddsAndRemovesResearchNodes`(新):挂接子节点(断言前缀拼写)/设为根节点/移除(字段整体消失),每步断言画布节点数。
- 快照:`snapshots/ui-techtree.png`(肉眼复核:accent 根节点、选中白边、L 形边线、标签、工具栏)。

## 追加:适配视图与真机验证(2026-10-25 晚)

真实模组(Exoprosopa,337 个研究节点、两棵根树,整树 2900×3377)验证发现:布局与渲染本身正常,但**初始视口停在树的左上角空白处**——根在树中央,分支上下展开,一块 60px 的角落里什么都没有,看起来像"画布是空的"。修复:

- `fitView()`:重建后把整片森林缩放居中进视口(上限 1×,下限 0.05×),窗口尺寸变化时重算;工具栏新增「适应视图」按钮。这与游戏打开科技树即见全树的行为一致。
- 快照 `snapshots/ui-techtree-real.png`(`--open=../mod --workspace=techTree --snapshot=…`):两棵根树完整可见,形态与游戏 ResearchDialog 相同。
- 新增测试 `UiSmokeTest.techTreeCanvasRendersTheRealModForest`:真实夹具模组下画布节点数 > 100 并出快照(依赖 `SearchPaths` 探测的夹具,缺失自动跳过)。
- 调试期间确认的机制细节,留作备忘:节点尺寸依赖 FX 布局脉冲,一次性 `shell.layout()` 对深层子树需要第二遍才落定(测试环境无脉冲,双 `layout()` 后世界区干净);CLI 快照的 2.5s 等待对大模组加载偏紧,验证时优先用测试夹具而非 CLI 快照。

## 追加:启动自动打开工作区模组(2026-10-25)

用户反馈「读取模组的科技树似乎被掠过了」。诊断结论:**数据读取从未缺失**——对夹具模组(Exoprosopa)的核查:561 条记录中 337 条带 research(159 裸字符串 + 176 对象式 + 2 根),176 个对象式 parent **全部解析成功**,整模组链成 2 棵根树(`11k-01-core-apex` 18 个子树、`29i-01-core-astral` 7 个子树),画布 337 个节点全部渲染。真正的原因是:**普通启动不加载任何项目**,科技研究页自然是空的(旧截图是此前手动打开过项目的会话)。

修复:

- 新增 `core/io/FixtureProject.probe()`:探测「启动时该打开的模组项目」——`-Dmodmaker.fixture`/`MODMAKER_FIXTURE` 显式指定优先,其次文档布局的 `../mod`,最后浅扫描工作目录旁的元文件夹。`TestFixtures.realModRoot()` 改为委托它(单一事实来源:应用演示与金标准测试必须指同一个模组)。
- `App.loadFromArguments`:命令行未给 `--open`/`--import` 时自动 open 探测到的项目并记日志;什么都找不到则照旧空启动。
- 测试:`FixtureProjectTest.probeFindsTheWorkspaceFixtureMod`;端到端验证:无参数启动 + `--workspace=techTree` 快照直接显示完整科技树(`snapshots/ui-techtree-auto.png`)。

## 追加:布局对齐与自动打开撤下(2026-10-25 深夜)

用户反馈:① 启动自动打开只是验证读取用的,暂时去掉;② 科技面板相对其他选项卡有左下角偏移。

- **偏移根因**:其他工作区的 48px 子标题行由 `AppShell.buildSubHeader` 加在面板**外面**(零内边距、齐边),而科技面板把工具栏放在自身 `.panel` 内部(10/8px 内边距)。修复:工具栏移出面板,`buildSubHeader` 对 `techTree` 直接返回它;面板去掉 `.panel` 内边距,画布与宇宙页一样齐边。
- **修复途中踩的坑(留档)**:箭头 switch 语句里 `case techTree -> techTreePane.toolbar();` 把返回值当语句丢弃、最终 `return row` 返回了空行——工具栏实例从未挂进场景。已改为方法开头直接返回。另注意:TabPane 只在**布局时**才把选中标签的内容挂进场景,测试里先 `applyCss()+layout()` 再做 `lookupAll`。
- **自动打开撤下**:`App.loadFromArguments` 恢复为普通启动空工程;`FixtureProject.probe()` 保留(金标准测试经 `TestFixtures` 委托仍在用),注释注明它可作为「记住上次项目」功能的备选。
- UI 测试 `techTreeToolbarAddsAndRemovesResearchNodes` 相应调整(布局先行 + 从 shell 查找按钮)。

## 追加:右面板文本/校验页去黑框(2026-10-25)

`StringsPane`/`IssuesPane` 原带 `.panel` 内衬(内边距 + 更深背景),那是为隐藏底栏时代设计的;进右面板后内衬绕表格/列表一圈,看起来像黑框且与属性页的留白不一致。改为:筛选行/摘要行自带 10px 外边距,表格与列表齐边铺满。快照 `snapshots/ui-context-strings.png`(由 `contextPanelExposesValidationAndTextTabs` 输出)。

## 追加:卡片重建(ContentCard 风格)与根树切换(2026-10-25 深夜二)

用户反馈「卡片的可读性几乎为零,重建卡片,参考画板的卡片效果」:

- **节点卡片重建**:60px 图标方块 → 190×64 的 ContentCard 风格卡片——左侧 4px **类型色条**(`ContentType.color()`,与方块构筑画布同源)、44px **贴图**、**显示名**(本地化)+ **内部名**(弱化,问题条目染橙)。复用 `.card` / `.card.selected`(选中=accent 边框)样式;原版父节点组半透明灰。布局尺寸随卡片调整(层级距 = 64+20)。
- **根树切换器**:与游戏 ResearchDialog 的根选择一致——工具栏首位「全部科技树 / <每棵根树>」下拉;数百张卡的整体森林只能缩到不可读,而单棵树视图的卡片保持可辨认。选择在重建间保留。
- 布局适配:布局 Box 按卡片尺寸传入(BranchTreeLayout 本就按逐节点宽高计算,间距 20 不变)。

## 追加:整套 ContentCard 重构已回滚(2026-10-25 深夜三)

用户澄清:卡片重构的目标板块是**方块构筑**(结构化引用端口本就通过 schema 生效于方块卡),科技研究面板**回滚到上一版**。已执行:

- `TechTreePane` 恢复为 60px 节点 + 下方名称标签 + 游戏 L 形边线的画布(保留适配视图、根树切换器、子标题工具栏与全部工具栏功能);
- 撤掉本轮为此加的 `ProjectController.connectReference/disconnectReference` 与 `ReferenceOps.references()`;
- CSS `.tech-node` 规则恢复小卡样式;测试几何断言恢复 80px 层距。
- 撤下的整套 ContentCard 版本的交互(端口拖线/边选中删除)记录在此,待明确需求后可作为独立任务重启。

## 追加:方块构筑卡片组件化 + 多重引用端口前置 + 每方块链接视图(2026-10-25 深夜四)

用户澄清需求:重构目标是**方块构筑**板块,科技研究保持回滚后的形态。三项落地:

1. **ContentCard v2(组件卡片)**:按 SVG 原型 `.card`/`.field`/`input[type=range]` 样式,把字段预览行换成**真组件**——布尔→CheckBox,数字→TextField+Slider(schema default 决定量程,松手提交防 undo 刷屏),文本→TextField,引用/结构组→数值 chip(在画布上连线编辑)。全部经 `controller.setField` 实时提交;**编辑守卫**:控件持焦点时画布不重建该卡(`isEditing()` + `setOnEditEnd`)。宽度 280。
2. **多重引用端口前置**:`inputPorts` 排序改为 struct → stack → content,`MAX_INPUT_PORTS` 4→6——`consumes`/`ammoTypes`/单位 `requirements`/`plans` 的端口永远可见,不再埋进 "+n"。
3. **每方块链接视图**:画布工具栏新增「整块画布 / <记录>」下拉;选中即显示**该方块的三列链接视图**(右列=它引用的,左列=引用它的,FocusSlot 三列布局,端口对端口连线)。端口拖线写入走 `connectReference`(记录即真相,不写画板边),选中边 + Delete 走 `disconnectReference`,全部可撤销;点击卡片选中记录供检查器编辑。`LinkGraph.referencedNames` 公开为邻居查找的单一来源。

## 追加:卡片按 画板 1.svg 1:1 重绘(2026-10-26)

用户给出 `画板 1.svg`(192×192 单卡 mockup)并要求「完全与画板一样,不是默认样式」。解码 SVG 几何与配色后,ContentCard v3 按 mockup 1:1 重绘:

- **配色**(取自 SVG fill):卡底 `#1b1a33`、行面板 `#1c1b2f`、端口盒 `#131236`、端口点 `#757575`、输入胶囊 `#25243c`、滑轨 `#d9d9d9`+白色圆钮、开关 `#52d33d`(on)/`#25243c`(off)、选中边框 accent。
- **几何**:卡宽 192;类型色头 32px(白色粗体名,patch 徽章);每字段一行 32px——左 20px 端口盒(引用字段的灰点为可连端口)+ 白底区字段名(52px)+ 右侧 120px 行面板(控件胶囊);卡高 = 32 + 行数×32。
- **控件**:数字→滑条(轨 #d9d9d9 白钮,量程由 schema default 与现值推导)+ 数值文本;布尔→胶囊开关(绿/紫,白钮滑动);文本→胶囊输入框(#4744ac 边框聚焦变 accent);引用→已接线值文本。
- 行节点统一登记 `rowNodes` 供 LOD 切换(FAR 只留头部);编辑守卫不变。
- 撤回:v2 的 `.nf-*` 样式保留(单位构筑等处可能复用),v3 新增 `.node-*` 系列。

## 追加:字段标识修正(2026-10-26)

用户反馈「卡片形式保持,但字段标识没了」——v3 初版把 mockup 的白色标签区做成了 52px 的 11px 淡紫小字,信息不可读。修正:标签区加宽到 74px、**白底深字(#1b1a33,12px)**,显示本地化字段名(`Labels.field`),tooltip 保留原始 JSON 键(导出文件可追溯);行面板相应收窄。mockup 的其余几何与配色不变。

## 已知边界(登记)

- 原版父节点组只读不可选中(无记录可选);给模组内容挂原版父节点走检查器的 parent 下拉(含原版名)。
- objectives 连线不画(与游戏一致);目标编辑仍在检查器 ResearchEditor。
- 布局即所见:格式无位置键,游戏也不保存位置,故不提供拖拽改位。
