# Mindustry ModMaker 架构设计

节点画布式的 Mindustry 模组构建器：在画布上把内容（物品/方块/液体/单位/炮塔…）摆出来、用连线表达引用与科技树，
并且**能直接读入已有的模组包继续编辑**，**能输出可被游戏加载的完整模组**。

实现语言是 Java + JavaFX（与游戏同栈），不是 Electron。架构图见同目录的 `架构图.canvas`（Obsidian 打开即可）。

---

## 一、分层总览

| ui (JavaFX)                                                                                                                                                                      |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| AppShell 菜单/工具栏/状态栏<br>NodeCanvasPane节点画布<br>ContentCar<br>RecordInspector(schema 驱动)<br>StringsPane<br>IssuesPane<br>GameLauncher(隔离目录实机测试)<br>ProjectController=唯一致变入口，视图只订阅通知 |
| core.io (纯 Java)                                                                                                                                                                 |
| ModPackageReader 读模组包<br>→ModImporter 建项目<br>→ProjectIo存盘<br>→ModExporter 构建/打包<br>**AutoLayout · JsonCanvas**                                                                   |
| core.fmt / core.json / core.schema / core.validate                                                                                                                               |
| 容错 HJSON 读取<br>值域规则(命名/目录/精灵/bundle)<br>SchemaRegistry(201 类 + 原版内容名录)<br>Validator                                                                                              |
| core.model                                                                                                                                                                       |
| ModProject / ContentRecord / Board / CanvasNode / Edge /StringTable / AssetRef <br>—— 导入与导出共用的原因                                                                                 |
**为什么这样分层**：导入、导出、校验都是纯粹的数据变换。把它们关在 `core` 里（零 JavaFX 依赖）意味着
可以在没有界面的情况下用 JUnit 跑真机模组的往返测试——这正是 Electron 架构最薄弱的地方
（格式逻辑缠在渲染层，只能靠 CDP 冒烟）。界面层只做「模型 ↔ 视图」绑定。

---

## 二、五个关键设计决策

### 1. 项目文件夹 = 真实模组文件夹 + `modmaker/` 创作层

```
<项目>/
├── mod.json / mod.json        真实 meta 文件（未编辑则原样保留）
├── content/**                  真实内容文件（未编辑则原样保留）
├── bundles/**                  真实 bundle 文件
├── sprites/ scripts/ sounds/ music/ maps/ schematics/ icon.png
└── modmaker/
    ├── project.json            画板列表、记录 id ↔ 路径映射、来源
    └── boards/*.canvas         节点与连线（JSON Canvas 1.0）
```

好处是三件事同时成立：
- **无损**：磁盘上的模组文件就是真相，没动过的文件保持字节一致；
- **可实机跑**：项目文件夹本身就能直接放进 `mods/`，不需要"导出"才能测试；
- **构建 = 复制**：`ModExporter` 只是把项目里除 `modmaker/` 之外的一切复制/打包出去，没有第二套生成逻辑。

### 2. 未编辑的文件按字节回写

`ContentRecord` 同时持有解析后的字段树与原始文本：未编辑 → 导出时原文写出（注释、格式、自定义键全留）；
编辑过 → 输出规范 JSON。bundle 文件同理。于是"导入一个别人的模组改一行再导出"不会污染其余 900 多个文件。

### 3. 只有 9 类内容能文件化，其余是内联对象

游戏 `ContentParser` 的解析器表（`ContentParser.java:577`）只注册了 **block / unit / weather / item / liquid /
status / sector / planet / team** 九种。`bullet`、`unitCommand`、`unitStance` 没有解析器，独立成文件会直接抛
`No parsers for content type`。所以：

- 画布上这 9 类节点各自对应一个文件；
- 子弹这类节点只作为**内联对象**存在（炮塔 `ammoTypes` 里的那个对象），编辑它会写回宿主文件；
- 导入时自动识别嵌套对象并生成子弹节点（真实模组里识别出 45 个）。

### 4. 引用即边

schema 里类型为 `content` / `stack` / `stack[]` 的字段（`requirements`、`research`、`ammoTypes` …）在画布上就是连线，
`research` 单独处理成科技树边。导入真实模组自动产出 531 条边——画布于是不只是白板，而是科技树与配方关系图。

### 5. 验证手段适配"没有 DOM 的环境"

JavaFX 无法像 Electron 那样用 CDP 检查 DOM，因此改用两条路：
`scene.snapshot()` 存 PNG（我直接读图复核界面）+ JUnit 场景图断言；核心逻辑则完全靠离线单测与真机往返测试覆盖。

---

## 三、必须遵守的模组格式契约（逐条从 160.5 源码核实）

| 规则 | 依据 |
|---|---|
| 装载来源：`mods/` 下的 zip/jar 或文件夹 | `Mods.java:518` |
| meta 文件优先级 `mod.json → mod.hjson → plugin.json → plugin.hjson`，**四种都按 HJSON 解析** | `Mods.java:35`、`ContentParser.java:1026` |
| zip 内**恰好一层**单目录会被自动下沉 | `Mods.resolveRoot`，`Mods.java:1080` |
| 内容扫描 `content/` **递归**，类型由目录决定，含历史别名 `status`/`weathers` | `Mods.java:877-885` |
| 内容名 = 文件名；游戏内注册名 = `<内部模组名>-<文件名>` | `ContentParser.java:591` 等 |
| 内部模组名 = `name` 小写、空格转连字符 | `Mods.ModMeta.cleanup`，`Mods.java:1427` |
| 补丁：文件名与既有内容重名即修改它；**方块补丁必须省略 `type`** | `ContentParser.java:583-592, 1041` |
| 精灵：`sprites/` 下**仅小写 `.png`**，区域名 = 文件名 + 模组前缀（已带分类前缀则不加）；`sprites-override/` 不加前缀 | `Mods.java:168-169, 404-412` |
| bundle：`bundles/bundle*.properties`，基名即 locale；键 `<类型>.<模组名-内容名>.<name\|description\|details\|credit>`，bundle 优先于 JSON 内联字段 | `Mods.java:641-663`、`UnlockableContent.java:90` |
| 科技树：没有独立文件，靠内容 JSON 的 `research`（父节点解析失败只警告） | `ContentParser.java:1337-1418` |
| weather **必须**显式声明 `type` | `ContentParser.java:909-918` |
| 未知字段仅警告；未知 `consumes` 子键、未知 `type` 值是硬错误 | `ContentParser.java:58, 571, 1038` |
| 矿石名是派生的：`new OreBlock(Items.titanium)` → `ore-titanium` | `OreBlock.java:26-28` |

---

## 四、三条数据流

**导入已有模组**：`ModPackageReader`（zip/文件夹 → 原始字节 + 文本，识别单层包装与噪声）→
`ModImporter`（内容/补丁/内联子弹/bundle→字符串表+保留自定义键/资产/自动布局+连线）→
`ProjectIo.unpack`（原样落盘为项目文件夹 + 写 `modmaker/`）。

**编辑**：画布选中卡片 → `RecordInspector`（schema 驱动控件）或 `StringsPane` →
`ProjectController` 改模型并置 `dirty` → `ProjectIo.save` 只重写改动过的文件（改名保留原目录）。

**构建**：`Validator` 先跑一遍规则 → `ProjectIo.save` → `ModExporter` 复制到
`<目标>/<内部名>/` 或打成 zip（单层包装目录 + 固定时间戳，重复构建产物一致）。

---

## 五、构建与运行

```bash
# 需要 JDK 21（本机 C:/Program Files/Java/jdk-21.0.12.1，已写入 gradle.properties）
./gradlew schemaBootstrap    # 扫 Mindustry 源码 → schemas/fields.json + vanilla-content.json
./gradlew run                # 启动应用（首次会联网下载 JavaFX）
./gradlew test               # 45 个测试，含真机模组往返
./gradlew run --args="--import=../格雷工业v167W45d/格雷工业 --projectDir=build/ui-demo --snapshot=snapshots/x.png"
```

命令行参数（便于自动化验证）：`--open=<项目目录>`、`--import=<模组 zip 或文件夹>`、`--projectDir=<落盘位置>`、
`--snapshot=<png>`。

---

## 六、验证与实测数据

用真实模组「格雷工业 v1.6.7」（955 个文件、541 张精灵、267 个 JSON、中文文件名、缺逗号的 HJSON）作为金标：

| 项目 | 结果 |
|---|---|
| 内容导入 | 265 个内容文件，解析失败 0，警告 0 |
| 内联子弹识别 | 45 个 |
| 补丁识别（`原版修改/`） | 5 个 |
| 自动连线 | 531 条（含 `research` 科技树边） |
| 资产保留 | 688 个（含游戏不加载的 `aaa.properties`、`---bundle.properties---`） |
| **导入 → 项目 → 构建** | **955 个文件，缺失 0 / 内容不同 0 / 多余 0（字节级一致）** |
| zip 打包 | 955 个文件，单层包装目录，可回读，27,477,776 字节 |
| 单文件编辑 | 只有被编辑的那个文件变化，其余 954 个字节不变 |
| 内联对象编辑 | 值写回宿主文件，且不产生多余文件 |
| 校验器 | 0 错误、47 警告（全部是"该方块没有对应精灵"的真实发现） |

---

## 七、稳定性校验（2026-10-02）

在功能验收之外做了一轮专门的稳定性校验，新增 `StabilityTest`（10 项）与 `UiSmokeTest`（真实 JavaFX 工具链上的场景图断言 + 截图）。**过程中发现并修复了 7 个真实缺陷**——这正是"只测功能不测稳定性"会漏掉的东西：

| # | 缺陷 | 后果 | 修复 |
|---|---|---|---|
| 1 | 重开项目时恢复记录 id 的顺序破坏了内联记录→宿主的链接 | **重开后再编辑内联子弹会静默丢失**；画布出现"(missing content)" | 先恢复文件型记录并建立旧→新 id 映射，再重连内联链接、恢复内联 id |
| 2 | 导入未携带 meta/Bundle 的原始文本 | 重开+保存会把 mod.json 的注释格式化掉、bundle 重排 | 导入器携带原文，未编辑时字节回写 |
| 3 | zip 内 `x/../../evil.txt` 类路径穿越 | 解包可写到项目目录之外 | `safeResolve` 归一化校验，越界路径跳过并报告 |
| 4 | 保存直接覆写目标文件 | 写盘中途崩溃会留下半个文件 | 写临时文件后原子 move |
| 5 | 保存的剪枝逻辑会删掉"未知 content 子目录"里的文件 | 非标准目录的模组文件在保存时丢失 | 剪枝跳过项目资产 |
| 6 | HJSON 解析无嵌套深度上限 | 恶意文件可栈溢出 | 深度上限 256 |
| 7 | 「添加内容」只建记录不建画布卡片 | 点添加按钮画布无反应（UI 冒烟测试抓到） | 新记录同时创建节点并落位 |

**校验结论**（全部通过，连续 3 遍全绿无抖动）：

| 检查项 | 结果 |
|---|---|
| 重复保存幂等 | 项目树逐字节不变 |
| 重开→保存循环 | 逐字节不变（修复 #1/#2 后） |
| 重复打包 zip | 两次构建的 zip 字节相同（固定时间戳） |
| BOM 保留 | 带 BOM 的内容文件在解包与构建后 BOM 均保留 |
| 损坏输入 | 假 zip / 非 zip 文件抛 IOException；空目录、坏 meta 正常导入并告警，内容不受影响 |
| 恶意 zip | 路径穿越条目被拦截，不落盘 |
| 删除一条内容 | 只删它的文件与 modmaker/ 对应项，其余 955 文件字节不变 |
| 改名 | 文件在原目录内改名，不搬目录 |
| 未知目录文件 / 重开后外部新增文件 | 均保留 |
| 重名内容 | 校验器报 error |
| 性能 | 955 文件导入+解包 2.4~2.7 秒 |
| UI 冒烟 | 真实工具链上建项目+加内容，场景图卡片数正确，快照 45KB |
| 应用启动 | 导入 955 文件模组启动，stderr 无异常栈；重开后画布 310 节点/531 边，无 missing content |

校验期间测试夹具（格雷工业模组文件夹）被移动过位置，测试已改为**多候选自动探测**（`TestFixtures`），
夹具在 workspace 内任意常见位置都能找到。

---

## 八、交互层（2026-10-02 第二轮迭代）

首版只验证了"渲染正确"，用户实测发现**画布无法交互**。根因是一个典型的 JavaFX 陷阱：

> 鼠标事件从卡片内的子节点（标签、圆点）冒泡到画布时，`event.getX()/getY()` 始终是相对**事件源节点**的坐标，
> 不是相对画布的。点卡片时坐标是"卡片内部某标签的局部坐标"，点选/拖拽判定全部失效。

**修复与新增**：

- 交互层统一改为 `event.getSceneX/Y() → sceneToLocal → toWorld` 三步换算；交互核心拆成
  `pressAt / dragTo / releaseAt`（接收画布局部坐标），鼠标事件层只做坐标转换——**整个交互逻辑可以直接单测**，
  不需要模拟操作系统级输入。
- **越界裁剪**：JavaFX 的 Region 默认不裁剪子节点，卡片拖到负世界坐标后会绘制到工具栏/侧面板上方
  （用户实测报告）。画布加了绑定自身尺寸的 clip 矩形；并配了**像素级回归测试**——复现"顶栏+左面板+画布"
  布局，把卡片拖出边界后对截图采样，断言溢出区域仍是面板颜色。
- **端口拖拽连线**：卡片右缘绿色 ID 端口 + 左缘灰色输入端口（带字段名标签，最多 4 个）。从一个卡片的
  ID 端口拖到另一卡片的输入端口（反向亦可），落点命中端口即写入引用：标量字段写裸内容名（游戏会先按当前
  模组解析），`stack[]` 字段追加 `{item: 名, amount: 1}`；同一 (from,to,field) 重复连线被拒绝。
- **引用写入/回退进 core**：`ReferenceOps.apply/revert/remove` 纯数据操作，JUnit 直测。
- **撤销/重做**：命令栈覆盖 添加/删除内容、连线/断开、拖拽移动（Ctrl+Z / Ctrl+Y，工具栏与「编辑」菜单同步禁用态）。
- **边选择与删除**：每条边带 12px 宽透明拾取曲线，点击选中（红色高亮），Delete 断开并回退字段值。
- **面板拖放**：从内容类型面板拖到画布任意位置落卡；「添加」按钮把新卡放到**当前视口中心**（原来放到最低卡片
  下方，大项目里根本看不见）。

**验证**：`UiSmokeTest` 在真实 JavaFX 工具链上驱动同一套 pressAt/dragTo/releaseAt——拖卡片断言节点位移、
ID 端口拖到 requirements 端口断言字段写入+边生成、undo/redo 断言往返；另加 `ReferenceOpsTest` 与
`ProjectControllerInteractionTest`（连接/断开/移动/删除 × 撤销重做 × 落盘）。67 个测试全绿。
附带修复：UI 测试导致 Gradle 测试进程挂起（FX 非守护线程），用 `LauncherSessionListener` 在测试会话结束时
关闭工具链。

---

## 九、界面汉化（2026-10-02 第三轮迭代）

针对"属性面板与节点面板大量原始键值"的反馈，加了一层**标识符显示标签**：

- **`Labels` 助手 + 两个数据文件**：`field-labels_<locale>.properties`（字段名 → 中文标签，**全量覆盖
  schema 中全部 1258 个唯一字段名**）与 `class-labels_<locale>.properties`（类名 → 中文，**全量覆盖 201 个类**）。
  全局按名字映射——同名字段在所有类里含义一致；术语对照游戏官方中文本地化。未翻译的标识符仍会回退原键名，
  schema 更新（重新扫描源码）后新增字段也自动回退，可增量补翻译。
- 原始键名不丢：schema 字段行的**悬停提示**是 `craftTime — 合成时间` 形式（原始键 + 英文描述），
  因为导出文件里写的仍是原始键。
- 内容类型全部中文化（物品/方块/液体/状态/单位/天气/区块/行星/队伍/子弹/单位指令/单位姿态），
  用于调色板、工具栏下拉、卡片副标题、检查器头部。基类与类型同名时卡片副标题不再重复
  （"方块"而不是"方块 · Block"）。
- 检查器的固定文案（标识/本地化/字段分区标题、名称（文件名）、修补现有内容（补丁）、（新文件）、
  添加字段、原始 JSON 等）全部走 Messages 资源；字符串表列头"键"。
- 可编辑类下拉用 **StringConverter** 显示中文（这是可编辑 ComboBox 的按钮渲染路径，buttonCell 不生效），
  输入既接受中文标签也接受原始类名，写回文件的永远是原始类名。

**尚未汉化**：校验面板的消息（核心层产生英文句子，需要把 Issue 改成 key+args 结构再做显示层翻译）、
schema 字段描述 tooltip 的英文正文、构建日志的技术性行。

---

## 十、类自适应（实验性，2026-10-02 第四轮迭代）

按源码语义，不同方块子类有不同字段；此前类下拉把 155 个方块类全部列出，电池可以随时变成物品桥。
新增**实验性自适应**（工具栏琥珀色开关「自适应（实验）」，默认开）：

- **同族切换**：`SchemaRegistry.relatedClasses(type, current)` 沿真实 Java 继承链（`hierarchyParent`，
  来自源码扫描，不被精编 schema 的简化 `extends` 覆盖）判定"同分支"——选中电池后下拉只提供
  Battery → PowerDistributor → PowerBlock 这条链的类、其同门子类（电力节点等）以及基类 Block（可随时
  回退为通用方块）。ItemBridge、Wall 等其它分支不再出现。
- **实现要点**：精编 schema 的 `extends` 是当年为字段合并简化的（Battery 直接写 Block），会污染层级
  判定。因此 `ClassSchema` 拆成两个字段：`parent`（字段合并用，精编可覆盖）与 `hierarchyParent`
  （源码扫描的真实父类，层级判定专用）；祖先链在类型根处截断（`UnlockableContent`、`Content` 为所有类
  共有，否则整个类型都会被判为同族）。
- **字段自适应显示**本就由 `fieldsOf`（沿继承链合并）实现；字段区现在会标注来源
  （"通用合成器 的字段（含继承）"），让"字段随类变"可见。
- 关闭开关即恢复完整的 155 类下拉；手动输入类名仍可任意切换（下拉约束、输入自由）。
- 测试：`SchemaRegistryTest.adaptiveClassSwitchingStaysInTheSameBranch` 断言 电池↔电力节点互通、
  电池↛物品桥、炮塔族互通、炮塔↛钻头、基类时给出全列表；`UiSmokeTest.batteryRecordHasNoTransportClassesInItsDropdown`
  在真实场景图上断言电池记录的下拉候选项无 导管/物品桥/墙/钻头/路由器。
- **术语校准**：类名标签对照游戏官方中文包（`bundle_zh_CN.properties`）修正——物品管道（Duct，此前误译
  "导管"与液体导管 Conduit 撞车）、路由器（Router，此前误译"分导器"）、流体路由器/流体交叉器、装卸器、
  物品/液体/电力黑洞（*Void）等。
- **基类时显示全列表是有意的**：记录还是通用"方块"时没有任何分支信息可供收窄；选定子类（如电池）后
  列表立即收窄到同族。

---

## 十一、已知限制与后续

**已实现**：导入 zip/文件夹、完整打包（文件夹/zip）、可交互画布（缩放/平移/拖拽/框选/端口连线/边删除（Delete 或 Backspace）/
双击连线改堆叠数量/撤销重做/面板拖放）、schema 驱动检查器、字符串表、校验面板、中英界面、实机测试脚本。

**尚未实现**（按价值排序）：
1. **校验面板消息汉化**（Issue 需改为 key+args 结构）——**已完成，见 §十三**；
2. **精灵面板**——精灵只作为资产保留与校验，还不能在卡片上预览 PNG 或导入新图；
3. **迷你地图、便签与分组的新建入口**（分组只能从 JSON Canvas 文件带入）；
4. **打包分发**（jpackage 单文件 exe）；
5. 大规模项目的异步导入（目前 955 文件约 3 秒，同步在 UI 线程上执行）；
6. 连线时选择数量（stack 目前固定 amount=1，需在检查器里改）——**已完成，见 §十六**。

---

## 十二、连线方向唯一化与画布一致性（2026-10-11 第五轮）

§二.4 只说了"引用即边"，没说一条边朝哪边——两套代码各自解释，于是埋了一个真实缺陷：`ModImporter` 把被引用的字段写在 `edge.from` 上，
而 `NodeCanvasPane` 的端口约定（右端口 = 本内容的 ID，左端口 = 本内容的引用字段）、`ProjectController.connect()` 与 `disconnect()`
都要求字段落在 `edge.to` 上。后果是导入模组的箭头全部反向，且在画布上删掉一条导入边时 `disconnect` 找不到对应字段，
只删线不清值——画布与记录成了两份真相。

本轮把方向收敛为**一个**定义并写进 `core/model/CanvasEdge` 的注释：

> `from` = 被引用的内容（画布上其 ID 输出端口）；`to` = 持有该引用字段的记录（`fieldKey` 所在的输入端口）。箭头由"被依赖者"指向"依赖者"。

新类 `core/io/LinkGraph` 成为连线的唯一真相：
- `derive(project, board, registry)`：按记录的引用字段派生边（含内联子弹的包含关系与 `research` 科技树边），**只连两端都能解析到记录的卡片**；
- `diff(...)`：报孤立卡片（`contentId` 已无对应记录）、缺失连线、失效连线（两端口都有记录但记录已不这么说）；
- `rebuild(...)`：以记录为准重算两端都有记录的边（复用已有 `CanvasEdge` 对象以保持 id 稳定），**保留指向无记录卡片的手绘连线**（原版载体），不误删。

`ModImporter` 不再自己拼边，改为直接调用 `LinkGraph.derive`，导入与重建不可能再各说各话。
`ProjectController` 在 `openProject` 时算一次 `canvasDrift`（陈旧创作层不再被静默接受，状态栏与日志都会提示），
并提供可撤销的 `rebuildLinks()`；界面入口在「视图 > 重建连线」。

回归网：`LinkGraphTest`（方向 / 确定性 / diff 三类 / 原版载体连线不被重建删除）、`RealModImportTest`（逐边校验"目标记录确实引用了来源"）、
`ProjectControllerInteractionTest.rebuildLinksRestoresWhatTheCanvasLostAndUndoesCleanly`。

---

## 十三、校验消息 key+args 与汉化（2026-10-11 第六轮）

校验消息原先是一句写死的英文，`Issue.message()` 直接返回它——面板因此永远显示英文，汉化无从下手。本轮把 `Issue` 改成
`record Issue(Level level, String key, List<Object> args, String contentId)`：工厂 `error/warning(key, args…)`，以及保留 contentId 的
`errorOn/warningOn(contentId, key, args…)`（面板用 contentId 做"点击定位到内容"，不能丢）。44 条消息的英文与中文分别落在
`messages.properties` / `messages_zh_CN.properties`，`IssuesPane` 与构建日志用 `Messages.t(issue.key(), issue.args())` 按 UI 语言渲染。
core 不依赖 `Messages`：`Issue.message()` 用 `ResourceBundle`(ENGLISH) 自行渲染英文，供日志、控制台与既有测试断言使用。

两个坑，都真实踩到：

1. **不能用 `MessageFormat`**：它把单个单引号当作引号段开始，`game's` 会渲染成 `games`。新增 `core/fmt/TextTemplate.fill` 做纯 `{n}` 替换，
   `Messages.t` 一并改用它（现有词条本就只用 `{n}`，行为不变）。
2. **`getBundle("messages", Locale.ENGLISH)` 会回退到默认 locale 包**：本机默认 locale 是 zh_CN，请求英语时 `messages_en` 不存在 →
   回退 `messages_zh_CN`，于是 `Issue.message()` 输出中文、英语断言全挂；同一个原因也让语言菜单的「英语」实际显示中文。
   修法是增加一个**有意为空**的 `messages_en.properties`（只写注释）作为英语链锚点，所有 key 经父链回退英文基包。
   （这是本轮顺带修掉的既有缺陷。）

回归网：`ValidatorLocalisationTest`（en/zh key 齐全、双语渲染、缺 key 时回退为 key 本身）；既有 `ValidatorPitfallsTest` /
`ValidatorPrecisionTest` 一字未改，仍按英文渲染断言每一条 finding，等于同时锁住了迁移前后的语义不变。

---

## 十四、内联编辑的文本补丁（2026-10-11 第七轮）

`ContentRecord.raw` 只保证"宿主自身没被编辑"时逐字节回写；内联子弹一改，宿主进 `serialize()` 整体重排，
手写 HJSON 里的注释、空行与手工对齐全部丢失——这与"无损编辑"的承诺相悖。本轮的解法是把内联改动做成
**原文的文本补丁**，而不是重写整棵树。

新增 `core/json/JsonTextPatch`：给一条路径（`ammoTypes.copper.damage` 或 `ammoTypes[0]`），在原文里定位
该值的字符跨度，只替换那一段。它是**定位器而非解析器**，只负责正确地跳过字符串与注释；接受的语法镜像
`HjsonReader`（裸键、无逗号、`//` `#` `/* */`、`'''` 原始串；关键差异见 `parseBare`：数组里逗号终止值，
对象里逗号只有在该跨度是 true/false/null/数字时才终止，否则是字符串的一部分，例如
`description: The rampant, filled with danger`）。

`ProjectIo` 的写入层次，逐级降级，每一级之后都做同一项校验（把结果重新解析，必须等于 `mergedTree`——该文件
应有的字段树）：

1. **逐叶字段替换**：把旧值（原文 span 解析出的树）与新值递归对比，只为真正变化的叶子生成补丁 →
   宿主内与被编辑对象自身的注释都保留。
2. **整对象替换**：成员增删这类"形状变化"无法用值替换表达 → 替换整个内联对象，宿主其余部分仍逐字节保留。
3. **整文件重序列化**：路径定位失败等 → 回到旧行为（数据正确，格式有损）。

这步校验不是可选项：本轮正是靠它抓到"拼接用了 `HjsonWriter.write`（整文件形式，根对象无大括号）而不是
`writeNested`（带大括号）"这个错误——没有它就会写出结构损坏的文件。因此新增了 `HjsonWriter.writeNested`
并单独锁住两种拼写的差异。

回归网：`JsonTextPatchTest`（7 例：成员/索引/嵌套定位、字符串与注释跳过、对象内逗号、缺失路径为 null、
缩进衔接）、`HjsonWriterTest.writeNestedKeepsAnObjectsBracesWhileAWholeFileStaysBraceless`、
`AuthoringTest.editingAnInlineBulletKeepsTheHostFilesComments`（断言落盘结果 = 原文仅那一个值被改写）。

---

## 十五、物品/液体表格的就地编辑（2026-10-11 第八轮）

`ItemsPane` 原本只读。物品与液体的常调标量（颜色/硬度/爆炸性/可燃性/粘度/温度）值得在表里直接改，
但**表不该镜像 schema**：`Item` 有几十个字段，全铺成列既宽又难用，而检查器已经能编辑任何字段
（包括记录当前还没有的字段）。所以表格只收这几个高频项，其余仍归检查器。

两条规则决定了"什么时候能编"：

1. **列是否出现**看内容：某数值字段只有被列表中至少一个记录的类声明时才显形——纯物品项目不会挂
   空的粘度/温度列。可见性在 `refresh()` 里按当次行集重算。
2. **单元格是否可进入编辑**看行：类必须声明该字段，且现值必须是标量——写成数组的颜色（`[r,g,b]`）
   不能当文本改。判定在 `EditableCell.startEdit()` 里，挡在 super 之前，所以按不下去就是按不下去。

提交一律走 `ProjectController.setField`，于是脏标记、撤销合并、重序列化提示与检查器编辑**完全同源**；
输入不是数字时不写任何东西，只记一条日志（`controller.log`），避免把 `2x` 静默变成 0。数字的拼写
也照顾到文件：整数写成整数（不是 `5.0`），与 `HjsonReader` 把整数读成 `Long` 的做法一致。

回归网：`UiSmokeTest.assetsTableEditsScalarFieldsInPlace`（可编辑性、列随类型出现、提交落值+标脏、
非数字被拒、`#` 前缀被去掉），并出快照 `snapshots/ui-assets.png` 肉眼复核。

---

## 十六、连线堆叠数量与双键删除（2026-10-11 第九轮）

连线的字段值里，栈引用写的是 `{item:name, amount:1}`——`amount` 恒为 1（`ReferenceOps.stackEntry` 写死），
用户只能绕到检查器里把整段文本改成 `name/5`。本轮把这条路径接到画布上。

**模型侧**（`core/fmt/ReferenceOps`，纯数据 + JUnit 直测）：新增
- `amountOf(target, field, sourceName)`：命中条目的数量，缺失返回 -1（非栈字段由调用方先挡掉）；
- `setAmount(target, field, sourceName, amount)`：定位来源名所在的条目——数组里**只替换命中那一项**
  （逐项 `matches`，与 `remove` 同一套匹配），裸名条目升级为显式 `{item:name, amount:n}` 对象；
  返回的撤销信息复用 `Revert(appended=false, previous=整字段深拷贝快照)`：数量是**就地改一个键**，
  不像 `apply` 那样追加/删除，整字段快照是最省心的可逆表示（`revert` 的 `appended=false` 分支原样放回）。

**控制器侧**（`ProjectController`）：`edgeAmount(edge)` 供画布判断"这条连线有没有数量"并预填（非栈引用返 -1）；
`setEdgeAmount(edge, n)` 走 `DirtyFlags` + 命令栈，撤销/重做与其它编辑同源。

**画布侧**（`NodeCanvasPane`）：
- **双击连线**弹出 `TextInputDialog` 改数量（仅当 `edgeAmount >= 0`，即栈引用；非数字/非正数只记日志）；
- 键处理从 `!= DELETE` 放宽为 `!= DELETE && != BACK_SPACE`：**Delete 与 Backspace 都能删除被选中的连线**，
  删除后 `event.consume()`（画布本身没有文本输入，退格不会与输入框抢事件）。

回归网：`ReferenceOpsTest` 3 例（裸名升级并还原、数组只改命中项、缺失项 no-op）、
`UiSmokeTest.selectedConnectionIsRemovedByDeleteAndBackspace`（Backspace 与 Delete 各删一次且字段一并回退）、
`UiSmokeTest.connectionAmountIsEditableAndUndoable`（1 → 5 → 撤销回 1 → 重做回 5）。

---

## 十七、新建项目的落盘位置预检（2026-10-11 第十轮）

用户实测「新建项目」直接弹出一个只有 `AccessDeniedException: C:\my-mod` 的「错误」框。静态定位到三处叠加：

1. `AppShell.newProject` 的 `DirectoryChooser` **只设了标题，没有初始目录**，于是对话框停在 Windows 的
   "上次位置"——很容易正好是盘符根 `C:\`；用户选根目录 → 目标 `C:\my-mod`。
2. `ProjectFactory.create` → `ProjectIo.save` 的第一句就是 `Files.createDirectories(root)`；在系统盘根目录下
   新建文件夹被 OS 拒绝，`AccessDeniedException` 的 message 恰好是**目标目录本身**，与弹窗逐字吻合。
3. 异常经 `runIo` 的通用兜底原样上抛（`getClass().getSimpleName() + ": " + getMessage()`），用户拿到的
   是一句英文类名 + 路径，既看不出原因也不知道怎么办。

改法（都落在 `AppShell`，core 不动）：

- `chooser.setInitialDirectory(defaultProjectParent())`：默认**用户主目录**（`user.home`）——起点落在用户自己的可写区域，
  而不是上一次偶然停留的根目录。**特意不用桌面**，理由见下。
- 新增 `static boolean canPrepareProjectFolder(Path root)`：先 `createDirectories(root)`，**再写一个临时探针
  文件并删除**。后半步不能省：`createDirectories` 对"已存在的目录"是 no-op，完全证明不了可写（这正是
  `Files.isWritable` 在 Windows 上对目录不可靠之外的另一个坑）。失败时不抛，弹
  `dialog.projectDirNotWritable(.header)`（中英双语），提示改选普通目录。
- 预检在 **FX 线程**上先跑，通过后才交给 `runIo` 执行保存，所以失败路径不会再走通用兜底。

**追加实测（用户复验，2026-10-11）**：把父目录换成 `C:\Users\XN325\Desktop\Mindustry\` 后新建**成功**，而
桌面根下 `…\Desktop\my-mod` 仍被拒。二者只差"是否在桌面这一层"——说明拦的不是"桌面不可写"，而是
**"不允许在桌面这一层新建条目"**（往已存在的子目录里写不受影响）。这与 ACL 检测结果一致：桌面 NTFS 上
当前用户是 FullControl、非 junction、应用也不改属性，所以**不是应用的权限逻辑**；典型成因是外部安全软件
对桌面的保护规则（该机 Defender 服务 Stopped、由火绒接管）。因此默认目录从"桌面"改成"主目录"，避开被
保护的位置。

回归网：`UiSmokeTest.projectFolderProbeRefusesAPathThatIsNotAFolder`（同名已存在的文件必须返回 false；
可写目录下的新文件夹必须返回 true）与 `UiSmokeTest.newProjectChooserStartsAtTheUserHome`（默认目录必须是
主目录，不许是桌面）。前者用"路径已是文件"制造失败，跨平台确定，不依赖 ACL。

一般化教训（已进 receiving §8 陷阱备忘录）：**任何"由用户指定落盘位置"的新入口，都要先探测再写，别把原始
`IOException` 抛给用户。**

---

## 十八、科技树工作区与研究索引的读写规范（2026-10-12 第十一轮）

第四个主区选项卡「科技树」（`Workspace.techTree` / `ui/TechTreePane`），用于管理**每个实例的研究索引**。
它把 §二.4「引用即边」里那条 `research` 边从"画布上的一根连线"提升为一个可读可写的工作台。

### 读写规范：以 `ContentParser` 为准

`readFields` 第一句就把 `research` 从 JSON 里 **`remove` 掉**（ContentParser.java:1270），所以它**不是任何 class
的字段**——schema 里把它标成 `content` 只是"能渲染"的意思，检查器因此只能写裸字符串。真正的处理在
ContentParser.java:1337-1418，本轮的读写模型逐条对齐：

| 规范 | 依据 |
|---|---|
| 仅 `UnlockableContent` 生效；值可为**字符串**（parent 名）或**对象** | 1337、1348-1353 |
| 对象键 `parent`/`requirements`/`objectives`/`planet`/`root`/`name`/`requiresUnlock` | 1352-1396 |
| `root:true` → 独立树根，此时才读 `name`（默认内容名）、`requiresUnlock`（默认 false） | 1394-1397 |
| 非 root 且无 `parent` → 游戏警告并忽略 | 1414-1416 |
| 父节点在 `TechTree.all` 里按 `name == parent` 或 `name == <mod名>-parent` 匹配 | 1399-1405 |
| **vanilla 树先建立** → 与香草重名的裸名绑定 vanilla，只有 `<mod名>-名` 到模组自己 | 1401、ContentLoader.java:72-79 |
| **补丁设 `research` → 直接拒绝** | 1338-1341 |
| `requirements` 缺省 → 走 `researchRequirements()`（Block 用 `researchCost`/公式、UnitType 用 requirements×倍率） | 1386-1388 |
| 物品/液体自动获得 `Produce` 目标 | 1377-1379 |
| `requirements` 的 `ItemStack` 可写 `"item/amount"` 文本 | 466-470 |

落地：`core/fmt/TechTreeRules` 扩成 `Node` + `read`/`write`（只写 parent 时仍写**裸字符串**）、
`allowsResearch(patch)`、`objective(TYPE, target)` 与 `objectiveTargetKey`（5 种目标类各自的目标键
`content`/`preset`/`planet`，取自 `Objectives.java`）。**既有 `parent/isRoot/isWellFormed` 一字未改**——
`LinkGraph`（§十二）与 `Validator`（§十三）都依赖它们，`FmtRulesTest` 原有的 5 条断言继续锁定语义。

### 派生视图：`core/io/TechTreeIndex`

放在 `core/io` 而不是 `core/fmt`：它要 `ModProject` + `SchemaRegistry`，而 `core/fmt` 是零模型依赖的纯格式层；
同族的派生器 `LinkGraph` 本来就在 `core/io`。它把全部记录派生成森林并标出**游戏会警告/拒绝的三类局面**：

- `unresolvedParent`：父名既不是"本模组里带 research 的记录"（注意：**不带 research 的记录不在 `TechTree.all` 里**，
  所以它当父节点同样找不到）也不是已知原版名 → 对应 1403-1405；
- `ignored`：既非 root 又无 parent → 对应 1414-1416；
- `patchRefused`：补丁带 research → 对应 1338-1341，且**不进入树**（游戏连节点都不建）。

此外 `unassigned` 列出"完全不带 research"的记录——它们不在游戏树里，但用户得能找到并给它们设研究索引。
`Forest` 里 `unresolvedParents` / `ignored` 与 `roots` 共享同一批 `Entry` 对象，因为它们的节点确实存在于
`TechTree.all`（构造函数就注册了，TechTree.java:88），子节点仍会挂在它们下面。

标记文案刻意写成"**父节点未找到**（游戏会警告）"，**不**断言该内容在游戏里不存在——原版名清单来自 schema
生成物，可能滞后于游戏版本。

### UI：`ui/TechTreePane`

左 `TreeView`（默认只展开根，节点标题带问题后缀）+ 右详情面板。详情面板跟随 `controller.selected()`（与检查器
同一约定），所以画布上选中的卡片也能在这里编辑研究。**所有编辑走 `ProjectController.setField`**，撤销、脏标记、
重序列化提示与其它编辑同源。提交按**按钮**而非逐键：一次提交会触发 `fireProjectChanged` → 重建树，逐键提交会
在每敲一个字符时丢光标。树的选中与展开按 contentId 还原（手法同 `NodeCanvasPane.refresh`）。

### 实现期抓到的缺陷

「兜底成根」的循环原本排在 vanilla 分组之前，导致**挂到原版父节点下的记录重复出现**：一次在原版分组下，
一次作多余的根。已把该循环移到 vanilla 分组之后。

回归网：`TechTreeIndexTest`（9 例：挂载、两种父名拼写、父节点非树节点、缺失父、无 parent 非 root、补丁、
vanilla 只读节点、重名裸名绑定 vanilla、未参与清单）、`FmtRulesTest`（+3：读写往返、目标类型/目标键、
空节点不写 + 补丁拒绝）、`UiSmokeTest.techTreeTabShowsTheResearchForestAndEditsANode`（4 个选项卡、树 2 节点、
设为根后字段落为对象且置脏、undo 回退）+ 快照 `snapshots/ui-techtree.png`。

## 十九、UI 主题与信息架构对标原型（2026-10-13 第十三轮）

视觉与信息架构按 `markdown/Mindustry模组工作台原型.html` 重做；功能、数据流、画布交互不变。方案书
`.claude/artifacts/plans/ui-refactor-prototype.md`（Option A「样式主导 + 就地结构改造」，含 7 条实施 drift）。

### 为什么是「整篇重写 CSS + 就地改结构」

- JavaFX **没有 CSS 变量**，改色只能全量替换字面值 → `app.css` 顶部登记完整 token 表（面板色族、8 个分类色、
  圆角 4/5/6/7/8/20、字号 12~24px、主色 `#FFA347`）。**改色请全量搜索这些字面值**，它们只存在于 `app.css`
  与 `ContentType.color()` 两处。
- 否决了"先建 UI 组件层再让所有 pane 改用"（要重写 8 个 pane 构造 ≈3000 行，把 5 轮积累的交互与断言网清零）
  与"只改 CSS"（交付不了左分类栏的 `en · 数量`、卡片网格、分块面板、大按钮行，达不到用户选定的信息架构层级）。

### 信息架构的变化

| 区域 | 变化 | 依据 |
|---|---|---|
| 顶部 | 新增 36px 头栏（产品名 + 当前项目）与 64px 主操作行（新建/导入/保存/校验/打包/实机 六个大按钮 + 未保存标记）；`MenuBar` 仍在（25 个菜单动作一个不少）；工具条瘦身为画布相关控件 | 原型 `#topbar`/`#menubar`/`#toolbar`；保真与"不丢功能"的取舍见 drift 1/2 |
| 左栏 | 48px 行：16px 分类色条 + 24px 中文名 + 右侧 `folderName · 数量` | 原型 `.cat`（`.bar`/`.zh`/`.en`） |
| 主区选项卡 | 64px 高、选中项底部 4px 琥珀下划线、标签 24px | 原型 `.tab`（`.tab.on::after`） |
| 资产页 | `TableView` → 响应式 `TilePane`：最小 228px + 16px 间隙（列数随视口变化），卡片含 56px 缩略区 / 19px 名称 / 13px ID / 分类色标签 / 错误时右上红点 | 原型 `#grid`/`.card`；编辑入口移到检查器（drift 3） |
| 右检查器 | `ScrollPane` → `BorderPane`：固定 96px 头栏（22px 名称 + 13px 副题）+ 88px 预览框 + `.stat-list` 键值行 + `.block` 分块 + 新增 `.report` 校验块 | 原型 `#right-head`/`#right-body` |
| 弹窗 | 只加 `.dialog-pane`/`.header-panel`/`.button-bar` 三组选择器，35 处 `new Alert` 调用点零改动 | 原型 `#dlg` |

### 网格的红点与检查器的校验块同源

两者的"这条记录有问题"都取自 `ProjectController.validateQuietly()`（与校验页 `IssuesPane` 同一来源），
避免出现"第二份关于坏记录的判断"——这是本项目一贯的"单一真相"取向（同 §十二 的 `LinkGraph`）。

### 回归网（防"改样式改坏行为"）

- `UiSmokeTest.assetsGridReplacesTheTableAndSelectsTiles`：资产页不再含 `TableView`、含网格；卡片含
  `.tile-thumb`/`.tile-tag`；点击卡片驱动共享选择；**列数随视口宽度变化**。
- `UiSmokeTest.prototypeThemeSizesHeaderTabsAndPalette`：**断言前 `applyCss(); layout();`**（未布局读到的尺寸是 0，
  会假绿）——36/64/64/48px 与 `.palette-bar`/`.palette-meta`/`.preview-box`/`.stat-list`/`.block` 存在性。
- `cardsOutsideTheCanvasAreClipped` 的硬编码坐标与像素点**未改**：画布卡片几何（`ContentCard` 260×130、
  `CarrierCard` 260×64）与 `AutoLayout` 的间距本来就一致，本轮只放大字号，几何不变（见 drift 5）。
- 七张快照（`ui-smoke`/`ui-assets`/`ui-techtree`/`ui-cosmos`/`ui-sprites`/`ui-appearance`/`ui-system`）全部重出并肉眼复核；
  另用**便携包 `--snapshot`** 复核了一次打包路径（防止"源码改了、双击跑旧 jar"重演）。

## 二十、参考 SVG 版面与无框窗口（2026-10-14 第十四轮）

### 几何来自实测，不靠感觉

本轮的目标不是"再调一版样式"，而是把外壳对齐两张参考 SVG。办法是先用 Edge headless 渲染 SVG，再读 `<rect>` 属性拿到精确坐标（1920×1020 画布）：标题条 36、菜单行 64、选项卡 120×64、子行 64、右侧属性栏 396、资源卡片高 64/色条 16/列距 248、预览框 480×560。所有断言都按这些数字写，不再凭视觉估算。

### 单位构筑页刻意不对称

曾考虑给单位开第二个 `NodeCanvasPane`（Option B）。否决理由：两个画布实例会把拖拽/连线/撤销/性能的回归面翻倍，而收益只是"两页长得一样"。最终单位页复用资源网格，画布仍是方块 + 物品/液体载体卡的流程编辑器。由此定下一条规则：**浏览器页（资源管理/单位构筑）点卡片 = 只选中，编辑在右侧属性栏；构筑页分类栏点击 = 新建并落到画布**。同一记录有两个入口是可接受的，只要语义不同。

### 无框窗口的验证点

系统标题栏无法用快照证明，所以断言放在可测位置：`StageStyle.UNDECORATED`、标题条三按钮的 `Messages` 文案、关闭按钮与标题栏 X 共用 `confirmExit()`、双击标题条切换最大化。默认最大化解决大屏/缩放环境下外壳被挤扁的问题。

### 嵌套选项卡的尺寸泄漏

`.center-tabs` 的 96×48 单元格规则会命中嵌套的 `.preview-tabs .tab`。第十四轮先改注释提醒，10-16 的 P2 用直接子级选择器链彻底限定作用域（见 §二十一）。

## 二十一、第二轮缺陷审计：把模型所有权变成结构性不变量（2026-10-16 第十五轮）

### 为什么选 Option B 而不是共享锁

保存、格式转换、构建、打开、导入原本都在 io 线程里同时碰模型引用与文件 I/O，靠"记得加锁"只能得到约定式不变量。用户否决了 Planner 默认的共享锁方案（Option A），选择结构性方案：**所有会改模型的工作都在 FX 线程完成，io 线程只拿不可变计划写文件**。落地为三组拆分：

- `ProjectIo.save` → `plan`（FX：生成 meta/content/bundle 文本、就地回写 `raw/dirty/rawPath`、算出删除与修剪清单）/ `write`（io：只做 `Files.write`/`deleteIfExists`）；
- `open/import` → `read*`（io：解包+解析）/ `publish*`（FX：赋值 project、清历史、检查 canvas drift、发通知）；
- `build` → `planBuild`（FX：校验+目标+保存计划）/ `writeBuildFolder|writeBuildZip`（io：纯复制/压缩）。

代价是全项目唯一写盘入口被拆开，牵连 convert/build 两条链；收益是"模型只归 FX 线程"成为代码结构保证，而不是一条需要记住的规矩。

### 同批补齐的边界

打包改临时文件 + `ATOMIC_MOVE`（失败不留半个 zip）；目录列举失败改为走覆盖确认而不是当成空目录；删除"目标记录已不存在"的边也进撤销栈；`sectorSize` 用 long 计算并钳制、校验器报超限；meta 的非数字/非布尔字段返回 fallback 并留在 `extra`；补 `strings.filter.prompt`/`strings.missing`；重做统一 `Ctrl+Y`。P2 还删了死代码/死 CSS、把 `.center-tabs` 规则限定到直接子级、退出时 `shutdownNow()` ioExecutor。

### 已知遗留

底部栏 `BOTTOM_STRIP_VISIBLE=false` 是用户有意开关，校验/日志/精灵/字符串表暂时界面不可达；运行期内存上涨复测被用户明确搁置。

## 二十二、仓库与文档基线（2026-10-17）

- **`mod/` 独立 git**：2063 文件（458 内容、1507 精灵、35 地图），`.gitattributes` 写 `* -text` 并关闭 autocrlf。这是硬要求：ModMaker 的往返一致性断言依赖**原字节**，而 autocrlf 会把 LF 在 checkout 时改成 CRLF。
- **ModMaker 提交 `be43896`**：工具栏精简（添加入口归调色板，整理/适应/新画板留在视图菜单）、删除两张参考 SVG、忽略导出的 11 MB zip、`run.bat` 便携分支加"可能落后于 src"警告。
- **文档对齐**：真实路径改为 `Desktop/game/Mindustry/...`，选项卡写 6 个，测试基线 298/37，头部提交号更新；首页删掉"全部未提交"章节，改为仓库状态。
- **VCS 噪声边界**：建库后金标测试把 `.git/` 当成 payload 期望值而变红。`.git`/`.gitignore`/`.gitattributes`/`.gitmodules` 归入导入噪声（包工只应是可加载内容），金标测试复用同一个 `isNoise` 过滤磁盘侧，并新增回归用例；真实用户的 git 工作树也不会把 VCS 元数据打进包里。
- 仍未纳管：游戏本体/源码、`skills/`、顶层压缩包——都是外部资源或可再得物。

## 二十三、UI 冗余治理 P0：校验快照与惰性刷新（2026-10-17）

完整分析、目标信息架构与 P1–P4 方案见 [[UI冗余分析与优化]]；本节只记本轮决策。

### 校验结果从“每个面板自己算”改为“一次计算共享”

此前检查器报告、校验面板、资源网格红点、单位网格红点各自调用 `validateQuietly()`，一次编辑 = 4 次 `Validator.validate`。在 561 条记录的夹具上实测单次 18–26ms，且全部跑在 FX 线程。决策：`ProjectController` 持有 `volatile List<Issue> issuesCache`，首次计算后所有面板共享不可变列表；`fireProjectChanged()` 在派发监听前失效缓存；`validate()`（F5/校验）清缓存强制重算。选择缓存而不是“只让可见面板校验”，是因为构建、状态栏、红点和报告本来就都需要同一份结果，缓存是语义上唯一正确的做法。

### 隐藏面板用 live/stale 而不是“干脆不建”

底部栏隐藏时 `IssuesPane`/`StringsPane`/`SpritePane` 仍会重建表格、重扫 1507 个精灵。决策：面板保留实例（测试与 `selectTab` 仍可访问），但隐藏期间只置 `stale`；`selectTab` 是激活点，`setLive(true)` 时补一次刷新。没有选择“按需构造面板”，因为那样会把 `messages` 重刷、tab 选择恢复和测试钩子都变成条件逻辑；`live/stale` 只改刷新时机，不改所有权。

### 死表面：只删“没有任何行为”的，不删“有明确语义”的

删除调色板里 3 个不可点击的非文件类型行、单位页的单 chip 行、星球/科技/脚本页的空 48px 子行。**扩展脚本 Tab 保留**：它有禁用态与“未实现”提示，是已文档化的路线图占位，不属于死控件；移除它会改变 6 选项卡的版式契约。

### 回归与代价

新增 `validationIsCachedUntilTheProjectChanges`（同一状态 `assertSame`、F5 换实例、模型变更失效）、`hiddenBottomPaneStaysIdleUntilItsTabIsOpened`（`refreshCount` 隐藏时不增、打开后 +1）、`paletteOnlyListsCreatableTypes`（行数 = 可创建类型数且每行有点击处理器）。全量 298 / 37 类，0 失败 0 跳过。未做：非当前工作区的 canvas/cosmos/techTree 仍随每次编辑重建（P4 的分域 revision 解决），检查器内部 type/class 重复（P2 已解决）。

## 二十四、UI 冗余治理 P1：单一类型目录与全局命令条（2026-10-17）

### 类型目录收敛到一处

第十四轮之后存在三套“类型→界面”映射：`Workspace.of`、资源页 `typeChips(ContentType.creatable())`、调色板。决策：`Workspace` 成为唯一事实——`of` 把 status/weather/team 归入 assets，新增 `assetTypes()` 派生资源页芯片（5 个），block/unit/planet/sector 各有专属页、不再在资源页重复列出；调色板保留全部 9 个可创建类型，因为它语义是“在画布上新建任意内容”，不是“资源清单”。

### 命令条与菜单共享一个 UiAction

撤销/重做/搜索/校验/构建/实机此前只活在菜单或画布工具条里，启用态要在 `refreshProjectState` 里手工同步 `undoItem/undoButton` 四份。决策：新增 `ui/UiAction`（textKey + run + accelerator + enabled），菜单项与命令条按钮都从它派生；标题条下加 44px 全局命令条，画布工具条退回“画板选择器”。类自适应开关留在命令条右侧（用户可见开关规则：默认开 + 琥珀）。

### 回归

`WorkspaceTest` 补“每个 fileBacked 类型恰好一个规范入口 + 内联类型不进资源网格”；`commandBarActionsShareTheirStateWithTheMenu` 验证命令条按钮随编辑/撤销切换；几何测试顶栏 36→80、标签采样带下移 44px。全量 298 / 37 类，0 失败 0 跳过。

## 二十五、UI 冗余治理 P2：ContextPanel 与预览去重（2026-10-17，部分）

### 右侧从固定检查器改为四 tab 面板

此前右侧固定检查器 396px，预览/校验/文本要么叠在资源页/星球页里，要么藏在隐藏底栏。决策：`ContextPanel`（属性/预览/校验/文本）成为唯一右侧面板；`bottom.validation`/`bottom.strings` 重定向到它，校验结果终于可见。`strings`/`issues` 仍是 P0 的惰性面板——首次选中对应 tab 才 `setLive(true)`，底栏只剩精灵/日志。

### 预览收归一处、检查器去重

删除 `ItemsPane` 的 480×560 预览与检查器的 `previewBox`/`statList`；新 `RecordPreview` 只住在预览 tab，类型/类/名称不再三处重复。资源网格因此拿回全宽（1366 下 2→3 列）。

### 刻意留 P3

星球页 360 预览暂留原地（`PlanetPreview` 由 `CosmosPane` 持有，`LiveEditProbe` 与 3 个快照断言依赖它），科技树 340px 详情编辑器也留到 P3 与 research 结构化块一起收敛——避免同一轮同时改布局和写路径。

## 二十六、UI 冗余治理 P3：research 并入检查器、raw 默认只读（2026-10-17，部分）

### research 唯一写入口

此前 research 有两套编辑器：科技树详情（`setField`）与检查器 raw（`applyRawTree`）。决策：新增 `ui/ResearchEditor`，把 parent/root/planet/requirements/objectives/clear 迁入检查器「研究」块，唯一经 `setField` 提交；`TechTreePane` 变为只读导航（树选中记录）。两个候选/写路径收敛为一个，撤销/脏标记/保存行为与普通字段一致。

### raw 成为逃生口而非第二编辑器

整文件 raw 区（`.raw-tree`）默认只读，勾选「编辑原始 JSON」才可改并出现「应用」按钮；字段级 raw 编辑器保持不变（它只覆盖单个字段，仍受字段 schema 约束）。

### 字符串表唯一写入口

检查器本地化块改为只读摘要 + 「在字符串表中编辑」按钮（`contextPanel.showStrings()`）；`StringsPane`（文本 tab）成为 name/description/details/credit 的唯一编辑面，消除了检查器逐键 `commitString` 与字符串表两套写路径。

### 留后续

引用候选统一（检查器内容下拉 vs 画布载体卡，语义不同：字段值 vs 画板绑定，需单独立项）；星球页并排预览迁入预览 tab（P2 收尾，耦合 `LiveEditProbe`）。


## 二十七、检查器预览：真实贴图与层次（2026-10-20）

### 从“色块预览”到图集 region

此前 `RecordPreview` 只画一个按类型着色的方块。现在新增 `ui/SpriteStore`：扫描 `project.assets`，用与 `Mods.packSprites` 相同的命名规则（`NameRules.spriteRegion`，`sprites-override/` 不前缀）把文件映射成 `region → PNG 路径`，再按 region 懒加载 `Image`。vanilla 内容不需要：预览只展示本模组仓库里的记录。

### 层次规则（逐条对照游戏源码）

- **item / status**：`UnlockableContent.loadIcon` 的回退链——`<type>-<name>-ui` → `<type>-<name>-full` → `<name>-full` → `<name>` → `<type>-<name>` → `<name>1`。
- **block**：body（`Block.region`，地板按 `Floor` 变体 `<name>1..3`）→ `-team`（`BuildingComp.draw`：`Draw.color(team.color); Draw.rect(teamRegion)`，预览用 `Team.sharded` 的 `#ffd37f` 做乘法着色）→ `-top`（`Block.drawTop` 之上层；duct 用 `<name>-top-0..4`）。
- **unit**：`-outline`（`UnitType.outlineRegion`，在 body 之下）→ body（`UnitType.region`；预览优先 `-preview`）。
- **liquid**：香草液体没有图集图标（游戏用颜色绘制），预览画 `color` 字段的色块；若模组提供 `liquids/<name>.png`，则改用贴图。
- 其他类型（planet/sector/weather/team）没有单张图集精灵，保持色卡。

### 渲染细节

Canvas 300×200、固定 2× 缩放（32px→64px，接近游戏内观感；仅超出舞台的贴图回退 fit）；像素艺术用 nearest-neighbour（`setImageSmoothing(false)`），网格按 32px tile × 2 绘制；团队色层是按通道相乘得到的独立 `WritableImage`（与 `Draw.rect` 的 tint 一致），按 region+color 缓存。名称/ID 从覆盖层移到舞台下方，避免遮住多格贴图。region 映射随项目变更重建，解码后的 Image 缓存保留（编辑不改贴图字节）。

### 边界

未合成炮塔炮管（weapon region 由运行时 `weapons`/ammo 决定）、`-glow`/`-heat` 叠加层、多格单位的分格拼装；这些属于后续增强，不影响当前“贴图 + 主层次”的正确性。回归：`previewComposesGameSpriteLayers` 锁定 block 的 body/team/top、item 的 icon、unit 的 outline→body 顺序；真机 Exoprosopa 抽查 item/block/unit 均有真实像素，liquid 色块正确。
