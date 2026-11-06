# Mindustry ModMaker — 交付文档（HANDOFF）

> 本文是项目的**自包含蒸馏**：任何没有历史上下文的开发者或大模型，只读这一份即可接手开发、验证与交付。
> 更详细的推导过程见 `architectureCN_ZH.md`（分层决策、格式契约逐条源码依据、各轮迭代记录），本文不重复推导，只保留可执行的事实。
> 最后更新：2026-11-05（对齐当日实测：**491 个测试 / 61 类，0 失败、28 跳过**——须给测试 JVM ≥2g 堆，见 §2；HEAD `9d720aa`（11-05 文档提交）、仓库已重建，旧提交号失效；**真实夹具当前不在本机**，28 条跳过中 25 条依赖夹具，金标往返尚未真正执行）。

---

## 1. 项目是什么

节点画布式的 Mindustry 模组构建器（Java + JavaFX 桌面应用，**非 Electron**）：

- **读入**已有模组包（.zip / 文件夹）→ 解包为可编辑项目，画布上按类型分列卡片、按引用关系连线；
- **编辑**：schema 驱动的字段检查器（全量中文）、字符串表、校验面板、撤销/重做、类自适应下拉；
- **输出**：完整可加载的模组（文件夹或 .zip），**未改动过的文件字节级原样保留**。

项目位置：`C:\Users\Administrator\Desktop\MindustryIDE\Mindustry-modmaker`（独立 git 仓库，HEAD `9d720aa`）；Obsidian 笔记库在其 `markdown/` 子目录（入口 [[首页]]）。**当前本机没有真实开发对象**：项目从 `Desktop/Mindustry/` 迁到 `Desktop/MindustryIDE/` 后，`../mod`（Exoprosopa）不再存在；参考源码在 `../Mindustry-160.7`（只有源码树，无 `Mindustry.jar`）。恢复开发/金标验证时，把模组放回 `../mod`，或用 `-Dmodmaker.fixture=<目录>` / `MODMAKER_FIXTURE` 指过去。

## 2. 环境与命令（本机硬编码事实）

| 事实 | 值 |
|---|---|
| JDK | 构建固定 **Java 25**（工程目录内自带 `JDK25/`）。`gradle.properties` 用**相对路径** `org.gradle.java.home=JDK25` 钉住，项目整体挪盘无需改；`JDK25/` 不入库（`.gitignore`），**新克隆缺它会直接报 "Java home supplied is invalid"**（Gradle 在加载项目之前失败），需先放入 JDK 25 或用绝对路径覆盖。`gradlew.bat` 自身仍需 `JAVA_HOME` 或 PATH 上的任一 JVM。`run.bat` 的提示写 "Java 21+"，但应用按 toolchain 25 编译，**运行实际需要 Java 25** |
| 构建 | Gradle wrapper（已缓存可离线）；JavaFX **25.0.5** + gson 2.11.0 + JUnit 5.10.2 来自 Maven Central（首次需联网）。**JavaFX 平台按本机 OS 推导**（win / mac / mac-aarch64 / linux / linux-aarch64），交叉构建用 `-PjavafxPlatform=<classifier>` |
| 运行 | `run.bat`（需 `JAVA_HOME`/PATH 上的 Java 25；首次或 `build/install` 缺失时自动 `gradlew installDist`，再调 `build/install/mindustry-modmaker/bin/mindustry-modmaker.bat`）；源码直接跑用 `./gradlew run`（构建侧固定用 `JDK25/`） |
| 测试 | `./gradlew test`（**491 个 / 61 类，0 失败、28 跳过**，`--offline --rerun-tasks` 实跑 2m10s；测试 JVM 堆 2g 已写入 `build.gradle.kts`——默认 512m 会在 `core.io` 重负载用例后被 Gradle 杀掉 worker；**28 条跳过不等于通过**，其中 25 条依赖真实夹具，交付前看「执行数」而非仅全绿） |
| schema 再生成 | `./gradlew schemaBootstrap`。源树**自动探测**：参数 > `-PmindustrySrc` / `-Dmodmaker.mindustrySrc` / `MINDUSTRY_SRC` > 常规相对位置（`../Mindustry-160.7/core/src/mindustry`、`../Mindustry-160.5/...`）> 兄弟目录浅扫描。当前 schema 标记与 `source` 均为 **160.7**；`schemas/` 生成物已随仓库存在，不重新生成也能运行；生成数据里的路径一律相对 |
| 启动脚本 | `run.bat`（**内容必须纯 ASCII + CRLF**——cmd 在中文 Windows 用 GBK 解析 .bat，UTF-8 中文注释会吞换行符）。**没有"无 JVM 自动回退便携包"逻辑**：无 Java 直接报错退出；便携包 `dist/ModMaker` 需手动 `make-portable.bat` 刷新 |
| 测试夹具 | **按探测定位**：`-Dmodmaker.fixture=<dir>` / `MODMAKER_FIXTURE` > 工作区约定位置 `../mod` > 项目父目录下浅扫描"**能解析出非空 name** 的 meta 文件夹"（`mod.hjson`/`mod.json` 同等有效，容忍一层包装目录；仅文件名是 `mod.json` 不算）。**本机当前没有夹具**：`../mod` 不存在，`../Mindustry-160.7` 里只有源码；此前误把 `tools/extra-schemas/mod.json` 当模组的问题已由 `6629ca2` 修复。夹具缺失时相关用例干净跳过（28 条），别当全绿 |
| 实机游戏 | 探测顺序：`-Dmodmaker.gameJar` / `MINDUSTRY_JAR` > `../Mindustry/Mindustry.jar` > `Mindustry/Mindustry.jar` > `../../Mindustry/Mindustry.jar`。**本机均不存在**（`../Mindustry-160.7` 没有 jar），实机测试需自备 160.7 的 `Mindustry.jar` 并指过去；支持 `MINDUSTRY_DATA_DIR` 隔离数据目录（`ClientLauncher.java:42`） |

命令行钩子（自动化验证用）：`--open=<项目目录>`、`--import=<模组包>`、`--projectDir=<落盘位置>`、`--snapshot=<png>`（截图后自动退出）。

## 3. 架构（两层，格式内核零 JavaFX 依赖）

```
core/（纯 Java，可离线单测）
  json/    HjsonReader(容错读:注释/缺逗号/裸键/单引号/BOM,深度上限256,逐条对照arc Jval) Json(规范写)
           HjsonWriter(树→HJSON:根对象无大括号/安全时无引号/往返无损是硬不变量) JsonPath
           JsonTextPatch(在原文里定位某个路径的值并只替换那一段,内联编辑据此保注释)
  fmt/     ContentType(9类可文件化+3内联,历史别名status/weathers) NameRules(名字/前缀/精灵区域)
           PropertiesFile(bundle编解码,UTF-8原文) BundleCodec(键约定+locale) TechTreeRules ReferenceOps
  model/   ModProject(唯一真相) ContentRecord(raw+dirty+inline*) Board/CanvasNode/CanvasEdge(DETERMINISTIC_ORDER)
           StringTable(bundleRaw原文+dirty) AssetRef
  schema/  SchemaRegistry(三层合并:精编schema.json > 扫描fields.json; 双父类!见§5) FieldDef/FieldType
  io/      ModPackageReader(读模组包:zip/文件夹/单层包装/噪声过滤/safeResolve防穿越)
           ModImporter(补丁/内联子弹识别,自动布局,确定性排序) LinkGraph(连线方向唯一化:派生/差异/重建)
           ProjectIo(存盘/重开,原子写,资产保护剪枝)
           Autosave(崩溃恢复:2 分钟脏快照→modmaker/autosave/,启动比较时间并提示恢复)
           ModExporter(构建=复制,zip单层包装+固定时间戳,含空目录) JsonCanvas(Obsidian兼容) AutoLayout
  validate/Validator(错误=游戏会挂,警告=可疑; 消息=key+参数,en/zh 双语) Issue(level+key+args+contentId)
ui/（JavaFX）
  ProjectController  唯一致变入口（开/导入/增删改/连线/重建连线/撤销栈/构建），视图只订阅通知
  ProjectActions     动作编排（打开/导入/保存/校验/构建/实机等，149 行；从 ProjectController 抽出）
  主区选项卡         TabPane(节点画布/物品与液体/星球与区块) Workspace.of(type) 路由"添加"动作
  NodeCanvasPane     画布:world Group(pan+zoom transform) + clip + pressAt/dragTo/releaseAt 可测交互核心
  ItemsPane          物品/液体管理表格(类型/名称/文件/类/颜色色块/描述 + 可编辑标量列:颜色/硬度/爆炸性/可燃性/粘度/温度,列按类声明决定是否出现)
  CosmosPane         行星→区块编排树(区块按 planet 字段分组,原版行星只读分组)
  PlanetPreview      星球预览工作台(三选项卡):①区块网格(测地网格+占用高亮+索引,容量=10*3^size+2)
                     ②星球外观(MeshBuilder.buildHex 逐参数移植:SimplexNoise 与 arc 反射比对逐位一致、
                       噪声着色/高度抬升、cloudMesh 云层、大气边缘,拖拽旋转) ③星系位置(轨道半径按
                       Planet 构造公式推算的示意图:恒星+原版行星上下文+本行星+卫星)
  PlanetOrbits       orbitRadius = parent.totalRadius + spacing + totalRadius 公式实现(原版:太阳 r=4/spacing=2)
  SimplexNoise       arc.util.noise.Simplex.noise3d 逐行移植(seed hash/skew 完全一致,反射比对测试锁定)
  PlanetMeshes       buildHex 族移植:NoiseMesh/SunMesh/HexSkyMesh 的 getHeight/getColor/skip 语义
  PlanetTemplates    新建行星自动套 serpulo 模板(/templates/planet-serpulo.json):
                     网格=NoiseMesh(游戏 JSON 解析器不支持 serpulo 自带的 HexMesh!)、大气、云层、
                     战役开关、startSector=170 等;每次应用都是深拷贝;sectorSize=3
  MeshTypes/MeshEditor mesh/cloudMesh 结构化子表:仅提供 parseMesh 可解析的 5 种类型,
                     每种类型固定字段集(逐参数对照源码),只写用户编辑过的键,未触碰的键走解析器默认值
  ContentCard        卡片:左右端口(锚点是常量,不依赖layout)  RecordInspector  StringsPane  IssuesPane
  Labels/Messages    标识符中文标签(数据文件驱动,未翻译回退原名) / UI文案
  GameLauncher       实机验证:构建进临时数据目录+MINDUSTRY_DATA_DIR启动游戏+解析last_log.txt
```

**核心不变式：项目文件夹 = 真实模组文件夹 + `modmaker/` 创作层**（`modmaker/project.json` 画板与 id 映射 + `boards/*.canvas` JSON Canvas 1.0）。由此推出三件事：
1. 未编辑的记录/meta/bundle 按**字节原文**回写（`raw`/`metaText`/`bundleRaw` 含 BOM）；
2. 构建 = 复制（排除 `modmaker/`），没有第二套生成逻辑；
3. 项目文件夹本身可直接放进游戏 `mods/` 实机跑。

**2026-10-06 增量**（详见 [[时间线]] 第 4 节）：
- **Validator 游戏性校验补齐**：validateBulletReach / validateDrillTiers / validateEnvironmentSprites / validateCrafterOutputs / validateRecipeCapacity / validateSoundReferences / validatePlanets（generator 类型、mesh 可解析性、sector 越界）+ validateEncoding（BOM）。
- **双格式转换**：`FormatConverter` 批量 json↔hjson（脏标记强制重序列化），菜单「格式」两项，转换后立即保存并在下次保存时清理旧 meta 文件；`ProjectIo`/`writeMeta` 按 ext 分流。
- **安全加固 S1–S5 闭环**：readZip 拒危险条目名、unpack 全分支 safeResolve、NameRules.isFolderSafe 两侧校验 meta.name、zip 导入预算（2 万条目/单条 256MiB/总量 1GiB）、writeMeta 兜底 mod.hjson。测试 `SecurityHardeningTest`。
- **UI 审计修复 10 项**：undo 命令捕获创建时 board（commandBoard）、MeshEditor 深拷贝进出、打开/新建/导入三入口 confirmDiscardUnsaved、ProjectIo.save synchronized、ModExporter.requireOutside 拒项目内构建目标等。
- 解析审计修复：HjsonWriter 数组内嵌套对象引号规则（镜像 Jval isArray 复位怪癖）、parseFailed 记录跳过转换、大小写扩展名防误删、''' 多行串按 arc readMlString 剥缩进。

## 4. Mindustry 格式契约（写 core 时必须遵守，均有源码依据，详见 `architectureCN_ZH.md` §3）

- **只有 9 类内容可文件化**：block/unit/weather/item/liquid/status/sector/planet/team（`ContentParser.java:577` 解析器表）。**bullet/unitCommand/unitStance 无解析器**，只能内联在宿主文件（如炮塔 `ammoTypes`）→ 编辑内联对象写回宿主，不产生独立文件。
- 内容类型由**目录**决定（`content/<folder>/`，历史别名 status↔statuses、weathers↔weather），**递归**扫描 `.json|.hjson`（都是 HJSON 方言）。
- 内容名 = 文件名（允许中文、禁止空格/非法字符）；注册名 = `<内部名>-<文件名>`；内部名 = meta.name 小写+空格转连字符。
- 补丁：文件名与既有内容重名即"修改它"；**方块补丁必须省略 `type`**。矿石名是派生的（`new OreBlock(Items.x)` → `ore-x`）。
- 精灵：`sprites/` 仅小写 `.png`，区域名 = 文件名+模组前缀（分类前缀如 `block-x-full` 除外）；`sprites-override/` 不加前缀。
- bundle：`bundles/bundle*.properties`（`---bundle.properties---` 这类游戏不加载但须原样保留为资产）；键 `<type>.<模组名-内容名>.<name|description|details|credit>`；`bundle.properties` 是英语基包（**没有 bundle_en.properties**）；bundle 优先于 JSON 内联字段。
- weather 必须显式 `type`；未知字段仅警告；未知 `consumes` 子键/未知 `type` 是硬错误。
- 科技树无独立文件，靠内容 JSON 的 `research` 字段。
- 术语汉化以游戏官方 `Mindustry-160.7/core/assets/bundles/bundle_zh_CN.properties` 为准（曾因自译踩坑：Duct=物品管道不是导管、Router=路由器不是分导器）。

### 4.1 夹具探测与 `mod.json` 校验（2026-11-05 修复）

**背景**：启动时自动打开的项目、以及 25 条金标/往返用例，都靠 `core/io/FixtureProject.probe()` 找真实模组。探测"找不到"应干净跳过；探测"找错"会把 Mindustry 自带的 schema 片段当成模组，表现为打开空名项目、金标全红。

**探测顺序**（`FixtureProject.probe()`）

1. `-Dmodmaker.fixture=<dir>` 或 `MODMAKER_FIXTURE`（`SearchPaths.override`）；
2. 工作目录下的 `../mod`；
3. `SearchPaths.find(working.getParent(), 3, hasMetaFile)`：最多 3 层、由浅到深、同深度按绝对路径字符串排序（确定性）。

每一步都过 `asModRoot`：目录本身合格就返回；否则**只穿透一层包装目录**，与游戏 `Mods.resolveRoot` 行为一致。

**单个 meta 文件的判定**（`FixtureProject.hasMetaFile`，全部是硬条件）

| 条件 | 实现 |
|---|---|
| 文件名 | 按游戏 `Mods.java:34` 顺序试 `mod.json` / `mod.hjson` / `plugin.json` / `plugin.hjson`（`ModMeta.META_FILES`） |
| 存在且是常规文件 | `Files.isRegularFile`；目录或缺失直接跳过 |
| 可解析 | UTF-8 读全文 → `HjsonReader.readObject` → `ModMetaCodec.fromJson`。HJSON 是 JSON 超集，`mod.json` 里写 `//` 注释也能过，**"能解析"≠"是模组"** |
| 有名字 | **`meta.name != null && !meta.name.isBlank()`**；空串或全空白不算 |
| 失败处理 | `IOException` 或任何 `RuntimeException` 都视为该文件不合格，继续下一个文件名；四个都不合格 → 该文件夹不是模组 |

**根因（修复前）**：旧实现只看 `Files.isRegularFile(folder.resolve(metaFile))`，即"有同名文件就算模组"，不看内容。父目录 3 层浅扫会命中 `../Mindustry-160.7/tools/extra-schemas/mod.json`——它是游戏自带的 schema 片段（`{doc: extra schemas, amount: 3}`，无 `name`）——于是 `probe()` 返回该目录，项目 `meta.name` 为空，校验报 `mod name is empty`，连带 14 条金标用例失败，另有 `RealModImportTest` 6 条初始化失败未执行。

**修复方案（`6629ca2`）**

1. `FixtureProject.hasMetaFile` 改为"**解析 meta 并校验非空 `name`**"（核心改动，`FixtureProject.java:54-77`）。
2. 保留 `asModRoot` 的**单层包装**穿透：不要把根目录下任意深层子目录都当模组。
3. `RealModImportTest` 的 `assumeTrue` 从"目录存在"收紧为"**目录存在且含 `content/`**"，避免 name-only 桩目录触发无意义金标。
4. 回归测试：`FixtureProjectTest.rejectsAMetaFileWithoutAName`（写无 name 的 `mod.json`，必须 false）与 `acceptsAMetaFileWithAName`（有 name，必须 true）。
5. 修复前后实测：**466 执行 / 14 失败 / 5 跳过 → 491 / 61 类、0 失败、28 跳过**（`--rerun-tasks`，约 2m10s）。**跳过变多不是退步**：旧代码误开假项目后硬跑失败；现在正确识别"本机无夹具"并干净跳过，真实夹具放回 `../mod` 后这些用例会转为通过。

**再次出现"打开空项目 / name is empty"时的排查顺序**

1. 强制指定：`-Dmodmaker.fixture=<模组根>`（根目录或仅一层包装目录，且必须有可解析、有 name 的 meta）。
2. 检查该目录的 meta：`name` 是否为空；是否把 `plugin.json` 工具目录或 `tools/extra-schemas/` 误认成模组。
3. 放回 `../mod` 后跑 `FixtureProjectTest.probeFindsTheWorkspaceFixtureMod`，确认 `../mod` 优先于浅扫。
4. 若测试从 skip 变 fail，先确认该目录含 `content/` 且 meta 可解析，再排查其它初始化错误（别先改探测逻辑）。

**有意保留的边界**：只有 `name`、没有 `content/` 的空模组会被**启动探测**接受（游戏本身允许空模组），但金标不跑；超过一层包装目录不自动穿透，需显式指定；多个候选按"浅层优先 + 路径字典序"取胜，这是 `SearchPaths.find` 的确定性契约，改探测前先看调用方是否依赖该顺序。

## 5. 八个容易做错的关键实现决策

1. **双父类**（`ClassSchema.parent` vs `hierarchyParent`）：精编 schema.json 的 `extends` 是为字段合并简化的（Battery 直接写 Block），会污染层级判定。字段合并走 `parent`（精编可覆盖），同族判定/祖先链走 `hierarchyParent`（源码扫描真实链，**祖先链必须在类型根处截断**，否则 UnlockableContent 让全类型"同族"）。见 `SchemaRegistry.relatedClasses`。
2. **坐标换算**：JavaFX 鼠标事件冒泡时 `event.getX()` 是相对事件源子节点的坐标。画布交互必须 `sceneToLocal → toWorld`；交互核心拆成 `pressAt/dragTo/releaseAt(localX,localY)` 纯入口供测试直接驱动（不模拟 OS 输入）。
3. **重开项目恢复 id**：先恢复文件型记录并建旧→新 id 映射，再重连内联记录的 `inlineOwnerId`、恢复内联 id——顺序错了会导致重开后编辑内联子弹**静默丢失**。
4. **保存剪枝必须保护资产**：`pruneManaged` 会删除 content/ 下不再被记录引用的 .json——但未知 content 子目录的文件是**资产**（`project.assetPaths()`），永不剪。写文件用临时文件+move（崩溃不留半个文件）。
5. **确定性**：内容记录按 `DETERMINISTIC_ORDER` 排序、zip 固定时间戳、画板名存 project.json 不存 canvas——保证重复保存/重开/构建**逐字节稳定**（有测试锁住）。
6. **连线方向唯一化**：一条边的含义固定为「`from` = 被引用的内容（画布上其 ID 输出端口），`to` = 持有该引用字段的记录（`fieldKey` 所在的输入端口）」——即箭头从"被依赖者"指向"依赖者"。`LinkGraph` 按此派生/重建，`NodeCanvasPane` 渲染、`ProjectController.connect/disconnect` 读写都遵循它。**2026-10-11 前导入器把方向写反了**（字段落在 `from`），后果是导入模组的箭头全部反向、且删除导入边不会清字段值——画布与记录成了两份真相。
7. **内联编辑走文本补丁，不走重序列化**：HJSON 的注释无法从解析树恢复，所以只重写"变化的那一段"（`JsonTextPatch` + 逐叶字段替换），并在写盘前把拼接结果重新解析、与应有的字段树比对，不符就回退。**`HjsonWriter.write` 是整文件形式（根对象无大括号），拼接嵌套值必须用 `HjsonWriter.writeNested`（保留大括号）**——用错会写出结构损坏的文件，本轮真实踩到，正是靠那步自校验才发现。
8. **自动保存必须写"分离快照"，恢复必须复用正常保存**：`Autosave.write` 接收 `ModProject.snapshot()`（detached 副本），以自动保存目录为根调 `ProjectIo.plan`——若直接传活模型，`plan` 会改掉真实 root 与脏标记。快照落在 `modmaker/autosave/`（导出器与导入器都忽略该目录，因此绝不会打进包里），时间戳 marker **最后写**。`restore` 把快照当普通项目打开并 `ProjectIo.save` 回真实根（因此删除过的内容会被正常剪枝），资产（sprites/sounds/maps）不写进快照、恢复后从真实目录重读；手动保存成功后 `discard`。

## 6. 验证协议（交付前全跑）

1. `./gradlew test --rerun-tasks` 全绿（**491 个 / 61 类，0 失败、28 跳过**，实跑约 2m10s；`build.gradle.kts` 已把测试 JVM 堆设为 2g——默认 512m 会中途杀掉 worker；不加 `--rerun-tasks` 时 Gradle 可能只恢复缓存结果）。**28 条跳过里 25 条是夹具用例，必须先用真实模组（`../mod` 或 `MODMAKER_FIXTURE`）把它们跑成通过，才算金标验证完成。** 分层：格式规则/容错解析（含 Exoprosopa 458 内容文件解析）→ 导入报告 → **金标往返**（Exoprosopa 2059 文件 导入→落盘→构建 逐字节比对 缺失/不同/多余=0；`modmaker/` 前缀过滤）→ 编辑/内联/删除/改名幂等 → 恶意输入（假zip/路径穿越/坏meta/条目洪流）→ 游戏性校验（`ValidatorPitfallsTest` 14 用例）→ **UI 场景图+像素断言**（卡片数、clip 越界、电池下拉无管道类、拖拽+连线+撤销全链路）→ **`LiveEditProbe` 实机联调链**（在探测到的真实模组上提交 orbitRadius/cloudMesh 编辑，断言星系视图逐像素变化 + 标脏）。
2. 界面改动：`UiSmokeTest` 里 `controller.select(...)` 后 `Snapshotter.write(scene, snapshots/ui-smoke.png)`，**用 Read 工具亲眼看图**（渲染对≠能交互，截图是唯一可靠的肉眼通道）。
3. 实机：`GameLauncher.buildAndLaunch`（隔离数据目录）或手动放 mods/；解析游戏日志找 `Error loading content`/`Sprite not found`。
4. 修 bug 必须同时加回归测试（本轮修的 7+2 个缺陷全有对应测试）。

## 7. 状态清单

**已完成**：导入（zip/文件夹，真实模组 265 内容+45 内联+6 补丁+531 边+688 资产，0 失败 0 警告）；构建/打包（955 文件字节一致往返）；**主区六个工作区选项卡**（第十四轮定稿：资源管理/方块构筑/单位构筑/星球战役/科技研究/扩展脚本；`Workspace.of(type)` 路由 item/liquid→资源网格、block→节点画布、unit→单位网格、planet/sector→编排树，科技树是全局研究索引，脚本页禁用占位；物品/液体仍保留画布轻量载体卡——它们是 requirements/research 连线的目标）；**星球预览工作台三选项卡**（区块网格/星球外观/星系位置——外观渲染移植 MeshBuilder.buildHex + arc Simplex 噪声（反射比对逐位一致），星系按 Planet 构造公式推算轨道；三页均拖拽旋转、像素守卫测试防空白回归）；**行星 serpulo 模板**（新建行星自动预填 serpulo 默认数据：sectorSize=3/大气/云层/战役开关等，见 `templates/planet-serpulo.json`）；**mesh/cloudMesh 结构化子表**（`MeshTypes` 固定字段清单 + `MeshEditor`：类型下拉限 5 种可解析类型、固定字段集、只写编辑过的键、MultiMesh 子网格列表、移除按钮）；画布（缩放/平移/拖拽/框选/端口拖拽连线/边选择删除（Delete 或 Backspace）/双击连线改堆叠数量/undo+redo/面板拖放/越界裁剪）；schema 检查器（全量中文：1258 字段+201 类名）；字符串表；精灵选项卡（资产列表/预览/缺失清单）；实验性类自适应（同分支收窄）；中英 UI；实机测试闭环；稳定性加固；**免 Java 便携包**（jlink `java.se`+`jdk.unsupported` `--compress=zip-6` → `dist/ModMaker` 48.5MB，`--snapshot` 验证整屏渲染）；**两个启动脚本**（2026-10-12 补回，均纯 ASCII）：`run.bat` 先探 JVM（实际需 Java 25）——有则按需 `gradlew installDist` 并启动 install 布局，**无 JVM 直接报错、不回退**，`dist/ModMaker` 只能手动启动；`make-portable.bat` 重建便携包（`gradlew jar` → 替换 `app` 下 jar → 便携 runtime 出图自检 → runtime 缺失时 jlink 重建）；**窗口启动即最大化**（`stage.setMaximized(true)`——外壳很密（工具栏/内容树/中央选项卡/检查器），写死的默认尺寸在大屏或缩放环境下会被挤扁甚至裁切）；**路径可移植化（2026-10-08）**：源树、游戏 jar、测试夹具、arc jar 全部改为探测（`SearchPaths`：属性/环境变量 > 约定相对位置 > 兄弟目录浅扫描），JavaFX 依赖按本机 OS 推导平台，仓库不再钉绝对 JDK，`schemas/*.json` 里的 `source`/`file` 改为相对路径（类集合与字段与旧数据完全一致，仅路径形式变化）。

**Validator 精度修复（2026-10-08）**：在真实模组上警告 1371 → **35**（0 错误），测试 171 → 193。三类误报（占 97%）按游戏源码逐条纠正——① 引用裸名不再告警（`ContentParser.locate()` 会自动补前缀），只在裸名与香草内容**重名**时告警（裸名被香草抢走）；② 精灵判定接受游戏自己的变体/部件约定（`name+N`、`name-后缀`，vanilla 也这么写），判定收敛到 `SpriteRegions.hasSprite/matchesFamily`（精灵面板共用）；③ 类名按游戏规则先大写首字母（`beamDrill`→`BeamDrill`）。另修两处同源误报：单位 `type` 是**闭集合**（`ContentParser.unitType` 的 switch，缺失合法、非法是硬错误，字段退回 `UnitType`；检查器下拉改用该集合），bundle 键允许覆盖香草条目（模组 bundle 合并进游戏 bundle 链）。`schemas/fields.json` 新增源扫描得到的 `unitTypes`，`vanilla-content.json` 补上香草队伍名。回归网：`ValidatorPrecisionTest`（14 用例，每条误报都有"不得误报"反例）。

**bundle 行模型原位替换（2026-10-08，第四轮，测试 193）**：`PropertiesFile` 从"解析成 map、写回时全量重排"改为**行模型**——保留 BOM、注释、空行、键序、分隔符（`=`/`:`）、缩进、行尾（CRLF 与 LF 各留各的）、续行与重复键；只有值真的变了的**那一行**被重写，未动过的行逐字节原样输出，新键追加，消失的键才丢行。`ProjectIo.renderBundle` 相应改为**从模组原本的 bundle 文本出发**（此前是空文件重建并按字母排序，这正是注释/空行全丢的根因），无原文的 locale 仍从零渲染；文件里本来就空值的键（`key = `）不再被顺手删掉。实测真实模组改一个字符串：1298 行 → 1298、注释 62 → 62、空行 389 → 389、diff 仅 1 行（改前：1298 → 845、注释 0、空行 0）。回归：`FmtRulesTest` 5 个行模型用例 + `ProjectIoDataSafetyTest.editingOneStringKeepsTheBundlesCommentsAndLayout`（导入→改一个字符串→保存，含"原文空值键保留"断言）。

**源码一致性校对（2026-10-08，第二轮）**：把应用写死的游戏规则逐条对回 `Mindustry-160.5`（20 项）。查出并修掉两处功能偏差：① **补丁的注册名**——`NameRules.fullName`/`ContentRecord.fullName` 原先无条件加 `<mod>-` 前缀，而游戏对补丁保留被覆盖内容的名字（`readBundle` 用 `cont.name`），于是补丁的 bundle 键/重命名迁移/检查器引用候选全是游戏不认的名字（Exoprosopa 0 补丁，所以金标没测出来）；② **音效扩展名**——只允许 `.ogg`，但 `DataAssetType.sound` 同样接受 mp3。另校正 3 处引用行号漂移（Mods.java 35→34、1427→1435、390→393）。方法学教训：`item` 的解析器用 `parser(...)` 辅助方法注册、`GenericMesh` 是接口、`CubemapMesh` 不在 `g3d/`——只按正则扫声明会误判，核对必须看声明处。

**MeshTypes 逐字段核对（2026-10-08，第三轮）**：以 `ContentParser.parseMesh`（mesh 类的 JSON 键全在这里，类本身没有公开字段）为准逐键核对五张表——SunMesh 8/8、HexSkyMesh 9/9、MultiMesh/MatMesh 由编辑器特例处理，均一致；**NoiseMesh 漏了 `color` 别名**（parseMesh 读 `color1`/`color2` 时各自回退到 `color`，预览侧已支持、编辑器字段表没有）已补。另修三处：mesh 类型名按游戏规则 `Strings.capitalize` 规范化（`noiseMesh` 合法、`noisemesh` 非法）并接进校验器/编辑器/预览；校验器里重复定义的 5 类清单改为引用 `MeshTypes.PARSEABLE`；类注释纠正表外的键会被忽略或崩—实际未知键被静默忽略、只有未知类型才抛错。测试 193 个全绿（第四轮 bundle 行模型后为 193）。

**画布与记录一致性（2026-10-11，第五轮，测试 245）**：新增 `core/io/LinkGraph` 作为连线的唯一真相——`derive`（按记录派生引用边）、`diff`（孤立卡片 / 缺失连线 / 失效连线）、`rebuild`（重建并保留指向无记录卡片的手绘连线，不会误删）。修掉一处真实缺陷：**导入器生成的边方向与画布/`connect` 相反**（字段落在 `from`），使导入模组的箭头全部反向、且删除导入边不清字段值。`ModImporter` 改为直接调用 `LinkGraph.derive`，导入与重建不可能再各说各话。`openProject` 加载时算一次 `canvasDrift`，不一致即写日志并在状态栏提示（不再静默接受陈旧创作层）；视图菜单新增「重建连线」（可撤销）。回归：`LinkGraphTest`（5 例：方向、确定性、diff 三类、原版载体连线不被重建删除）、`RealModImportTest` 方向校验、`ProjectControllerInteractionTest.rebuildLinksRestoresWhatTheCanvasLostAndUndoesCleanly`。

**校验消息 key+args 与汉化（2026-10-11，第六轮）**：`Issue` 从「英文句子」改为「key + 参数」——`Issue(level, key, args, contentId)`，工厂 `error/warning(key, args…)` 与保留 contentId 的 `errorOn/warningOn(contentId, key, args…)`（面板靠 contentId 点击定位）。44 条消息全部进 `messages.properties`（英文）与 `messages_zh_CN.properties`（中文），校验面板 `IssuesPane` 按 UI 语言渲染；`Issue.message()` 保留英文渲染供日志/控制台/既有断言使用（core 不依赖 `Messages`）。替换器 `core/fmt/TextTemplate` 用朴素 `{n}` 替换而非 `MessageFormat`（后者会把 `game's` 吃成 `games`），`Messages.t` 同步改用同一实现。**顺带修掉一个既有缺陷**：本机默认 locale 为 zh_CN 时，`ResourceBundle.getBundle("messages", Locale.getDefault())` 会让语言菜单切「英语」回退到中文包——新增**有意为空**的 `messages_en.properties` 锚定英语链。回归：`ValidatorLocalisationTest`（en/zh key 齐全、双语渲染、缺 key 回退）；既有 `ValidatorPitfallsTest`/`ValidatorPrecisionTest` 未改一字，继续锁定每条英文渲染。

**内联编辑只改那一段（2026-10-11，第七轮，测试 257）**：`ContentRecord.raw` 只在宿主自身未被编辑时逐字节回写，而内联子弹一改动，此前会触发宿主**整文件重序列化**——注释、空行、手工对齐全丢。新增 `core/json/JsonTextPatch`：在原文里按路径定位某个值的文本跨度（镜像 `HjsonReader` 的语法：裸键、无逗号、`//` `#` `/* */`、`'''`、数组里逗号终止而对象里逗号是字面量的一部分），只替换那一段。`ProjectIo` 据此先用**逐叶字段替换**（只改真正变化的叶子值，宿主内与被编辑对象自身的注释都保留），形状变化（增删成员）退回整对象替换，再退回整文件重序列化；每一步之后都把拼接结果重新解析、与 `mergedTree`（该文件应有的字段树）比对，不一致即回退——**补丁的正确性由构造保证**。回归：`JsonTextPatchTest`（7 例）、`HjsonWriterTest.writeNestedKeepsAnObjectsBracesWhileAWholeFileStaysBraceless`、`AuthoringTest.editingAnInlineBulletKeepsTheHostFilesComments`（断言保存结果 = 原文仅那一个值被改写）。

**物品/液体表格行内编辑（2026-10-11，第八轮，测试 258）**：`ItemsPane` 的常调标量字段（颜色 + 硬度/爆炸性/可燃性/粘度/温度）改成**就地可编辑**：提交走 `ProjectController.setField`，因此脏标记、撤销栈、重序列化提示与检查器编辑完全一致；输入非数字只记一条日志、不改数据；颜色列非编辑态仍显示色块。数值列**按「该记录所属类是否声明此字段」决定是否出现**（纯物品项目不会挂空的粘度/温度列）；单元格只在「类声明了该字段、且现值是标量」时才允许进入编辑（如写成数组的颜色不可文本编辑）。其余字段（含记录当前还没有的字段）仍由检查器负责——表格是快速微调，不镜像整个 schema。回归：`UiSmokeTest.assetsTableEditsScalarFieldsInPlace`（可编辑性、列随类型出现、提交落值+标脏、非数字被拒、`#` 前缀被去掉）+ 快照 `snapshots/ui-assets.png`（已肉眼复核）。

**连线堆叠数量可编辑 + 选中连线双键删除（2026-10-11，第九轮，测试 263）**：`ReferenceOps` 新增 `amountOf`/`setAmount`——连线写引用时 `amount` 固定为 1 的问题，改为可由用户改写：`setAmount` 定位该来源名所在的栈条目（数组里只改命中那一项），裸名条目升级为显式 `{item:name, amount:n}` 对象，返回**整字段深拷贝快照**作为撤销信息（`Revert` 复用 `appended=false` 分支）。`ProjectController` 新增 `edgeAmount(edge)`（非栈引用返回 -1，供画布判断/预填）与可撤销的 `setEdgeAmount(edge, n)`。画布上**双击一条连线**弹出数量对话框（仅栈引用有此入口；非数字/非正数只记日志）；`NodeCanvasPane` 的键处理放宽为 **Delete 与 Backspace** 均可删除被选中的连线（原先只认 Delete）。回归：`ReferenceOpsTest` 3 例（裸名升级并还原、数组内只改命中项、缺失项是 no-op）、`UiSmokeTest.selectConnectionIsRemovedByDeleteAndBackspace`（Backspace 与 Delete 各删一次并回退字段）与 `UiSmokeTest.connectionAmountIsEditableAndUndoable`（默认 1 → 改 5 → 撤销回 1 → 重做回 5）。

**新建项目目录预检（2026-10-11，第十轮，测试 265）**：用户实测新建项目时报 `AccessDeniedException: C:\my-mod` —— 父目录选择框未设初始目录，很自然地停在**盘符根 `C:\`**，而系统拒绝在根下建文件夹；异常又经 `runIo` 的通用兜底直接弹成一句英文。修法两处：① `DirectoryChooser.setInitialDirectory` 默认落到**用户主目录**（`System.getProperty("user.home")`）；② 新增 `AppShell.canPrepareProjectFolder(root)`——在 FX 线程上先 `createDirectories` 再写一个临时探针文件并删除（`createDirectories` 对已存在的目录是 no-op，证明不了可写），失败则弹「无法创建项目文件夹 / 该位置不可写，请改选普通目录」而非原始异常。回归：`UiSmokeTest.projectFolderProbeRefusesAPathThatIsNotAFolder`（已存在的同名文件必须被拒、可写目录下的新文件夹必须通过）与 `UiSmokeTest.newProjectChooserStartsAtTheUserHome`。

> 默认目录为何不用桌面（2026-10-11 追加实测）：用户改选 `C:\Users\XN325\Desktop\Mindustry\` 后新建**成功**，而桌面根下 `…\Desktop\my-mod` 被拒——说明拦的不是"桌面不可写"，而是"**不允许在桌面这一层新建条目**"（写入已存在的子目录不受影响）。这与 ACL 检测一致（桌面 NTFS 上当前用户 FullControl、非 junction、应用也不改属性），典型成因是外部安全软件对桌面的保护规则（该机 Defender 服务 Stopped、由火绒接管）。故默认目录从桌面改为**主目录**，避开被保护的位置。

**科技树工作区（2026-10-12，第十一轮，测试 278）**：新增第四个主区选项卡「科技树」（`Workspace.techTree`），用于管理**每个实例的研究索引**。规范逐条取自源码而非泛化理解——`ContentParser.java:1268-1419` 把 `research` 从 JSON 里 `remove` 后单独处理，故它**不是任何 class 的字段**，只在 `UnlockableContent` 上生效；`TechTreeRules` 因此扩展成完整的读写模型：`Node` 记录 7 个键（`parent`/`requirements`/`objectives`/`planet`/`root`/`name`/`requiresUnlock`），`write` 在「只写了 parent」时仍写**裸字符串**（源码 1348-1354 的第一条分支），`requirements` 按 `"item/amount"` 文本写（源码 466-470），objectives 开放源码全部 5 种类型（`Research`/`Produce`/`SectorComplete`/`OnSector`/`OnPlanet`，目标键分别为 `content`/`preset`/`planet`）。新类 `core/io/TechTreeIndex` 把全部记录派生成科技树森林，并标出游戏会警告/拒绝的三类局面：父节点找不到（1403-1405）、既非 root 又无 parent（1414-1416）、**补丁带 research（1338-1341，游戏直接拒绝）**；另把「本模组节点 / 已知原版名」之外的父名一律只标「未找到」而不断言游戏里不存在（vanilla 名清单来自 schema 生成物，可能滞后）。新 UI `TechTreePane`：左树右编辑器，编辑一律走 `ProjectController.setField`（撤销/脏标记/重序列化提示与检查器同源）。父名解析**忠实于源码 1401 的扫描顺序**：vanilla 树先建立，所以与香草重名的裸名绑定 **vanilla** 节点，只有 `<内部名>-名` 才落到模组自己的节点——这正是校验器 `vanillaFirst` 警告的那条规则，已单独测。实现期抓到一个真缺陷：「兜底成根」的循环原本排在 vanilla 分组之前，导致挂到原版父节点下的记录会**重复出现**（一次在原版分组下、一次作多余的根）。回归：`TechTreeIndexTest`（9 例）、`FmtRulesTest` +3 例、`UiSmokeTest.techTreeTabShowsTheResearchForestAndEditsANode` + 快照 `snapshots/ui-techtree.png`（已肉眼复核）。

**UI 层重构对齐原型（2026-10-13，第十三轮，测试 279）**：按 `markdown/Mindustry模组工作台原型.html` 重做视觉与信息架构（方案 `.claude/artifacts/plans/ui-refactor-prototype.md`，含 7 条实施 drift）。**主题**：`app.css` 整篇换成原型的 token（主色 `#FFA347`、分类色 8 个、面板色族 `#07080A/#0B0C0F/#121516/#16181D/#171B1C/#1C2022`、圆角 4/5/6/7/8/20），文件顶部登记完整 token 表（JavaFX 无 CSS 变量，改色只能全量搜字面值）。**信息架构**：顶部新增 36px 应用头栏（`MINDUSTRY MOD STUDIO` + 右侧当前项目）与 64px 主操作行（新建/导入/保存/校验/打包/实机 六个大按钮 + 右侧未保存标记）；`MenuBar` 保留在头栏与操作行之间（25 个菜单动作一个不少），`buildToolBar` 瘦身为画布相关控件（校验/打包/实机已上移到操作行）。左分类栏改为 48px 行：16px 分类色条 + 24px 中文名 + 右侧 `folderName · 数量`。主区选项卡 64px、选中项底部 4px 琥珀下划线、标签 24px。**资产页表格→卡片网格**：`ItemsPane` 由 `TableView` 换成响应式 `TilePane`（最小 228px + 16px 间隙、列数随视口变化），卡片含 56px 缩略区、19px 名称、13px ID、分类色标签、有错误时右上红点（错误源与校验页同源 `validateQuietly()`）；点击卡片驱动共享选择，编辑入口移到右侧检查器——**能力不丢、入口迁移**（详见 drift 3）。**检查器分块化**：`RecordInspector` 由 `ScrollPane` 改为 `BorderPane`（固定头栏 96px：22px 名称 + 13px 副题），主体为 88px 预览框 + `.stat-list` 键值行 + `.block` 分块 + 新增 `.report` 校验块（只列当前记录）。**弹窗**：只加 `.dialog-pane`/`.header-panel`/`.button-bar` 三组选择器，35 处 `new Alert` 调用点零改动。回归：`UiSmokeTest.assetsGridReplacesTheTableAndSelectsTiles`（网格取代表格、点击选中、列数随宽度变化）、`UiSmokeTest.prototypeThemeSizesHeaderTabsAndPalette`（36/64/64/48px 与 `.palette-bar`/`.preview-box`/`.stat-list`/`.block` 存在性，断言前 `applyCss()+layout()`），七张快照（`ui-smoke`/`ui-assets`/`ui-techtree`/`ui-cosmos`/`ui-sprites`/`ui-appearance`/`ui-system`）全部重出并肉眼复核，另用便携包 `--snapshot` 复核了一次打包路径。

> **暂时隐藏底部栏（2026-10-13，用户要求）**：`AppShell.BOTTOM_STRIP_VISIBLE = false` 时，底部「字符串表/精灵/校验/构建日志」选项卡条**与状态栏**都不挂到窗口，中央工作区拿到全部垂直空间。四个面板仍会构建，`AppShell.selectTab(key)` 与 `UiSmokeTest` 的 `bottomTabSelected` 改为直接读 `bottomTabs` 字段（不再依赖 `getBottom()`），所以语言切换保持选中的断言照旧。把常量改回 `true` 即完整恢复。注意：这段时间校验/日志/精灵/字符串表在界面上不可达（构建完成与"打开测试日志"仍会尝试 `selectTab`，但看不到）。

**SVG 版面与无框窗口（2026-10-14，第十四轮）**：按用户提供并实测坐标的两张参考 SVG 重排外壳（方案 `.claude/artifacts/plans/svg-layout-and-frameless-window.md`）。顶部改为「36px 自绘标题条 + 64px 菜单行」，其下是「64px 主区选项卡行 + 64px 子行」，内容区右侧固定 396px 属性栏（头 128px + 分块）。主区选项卡从 4 个扩到 **6 个 ×120px**：资源管理 / 方块构筑 / 单位构筑 / 星球战役 / 科技研究 / 扩展脚本（最后一个禁用占位）。资源管理页是「9 个类型芯片 + 分页圆点 + 响应式紧凑卡片网格 + 中央 480×560 预览框」；方块构筑页是「240px 左侧分类栏 + 节点画布 + 子行类型选择器」。`Workspace` 新增 `units`（单位从 canvas 分出）与 `script`（不参与 `of()` 路由）。窗口改为 `StageStyle.UNDECORATED`，标题条内画最小化/最大化/关闭三个按钮，关闭与标题栏 X 共用 `confirmExit()` 未保存守卫，双击标题条切换最大化，启动即最大化。回归：`UiSmokeTest` 新增六页几何/快照断言（几何断言前必须 `applyCss(); layout();`）；便携包 `--snapshot` 复核打包路径。参考 SVG 已在实现后删除（历史见 git `be43896`）。

**第二轮缺陷审计与 FX 线程模型（2026-10-16，第十五轮，测试 286 / 37 类）**：对 10-08 缺陷报告之后新浮现/遗留的问题做独立复检（方案 `.claude/artifacts/plans/defect-remediation.md`，P0/P1 外科修复 + P2 清理）。**数据完整性**：保存拆成 `plan`（FX 线程生成文本并就地回写模型）/ `write`（io 线程纯文件 I/O）；`buildZip` 改临时文件 + `ATOMIC_MOVE`；目录列举失败改为走覆盖确认；删边补成可撤销；`sectorSize` 溢出钳制并在校验器报错；meta 非数字字段不再毁整份 meta；补齐 `strings.filter.prompt` / `strings.missing`；重做快捷键统一为 `Ctrl+Y`。**P2 清理**：删死代码/死 CSS、`.center-tabs` 尺寸规则限定到直接子级（不再泄漏到 `.preview-tabs`）、`App.stop()` 关闭 ioExecutor。**结构性收尾**：`open/import` 拆成 `read*`（io）+ `publish*`（FX），`build` 拆成 `planBuild`（FX）+ `writeBuildFolder/Zip`（io）——至此「模型只归 FX 线程」覆盖 save / convert / build / open / import 五条链路，io 线程只剩纯文件 I/O。回归：286 用例 0 失败 0 跳过；便携包重打后 `--snapshot` 出图。

**工具栏精简与仓库基线（2026-10-17）**：工具条只保留画布相关控件（撤销/重做/查找/构建/停止游戏/打开日志/画板）；「添加」入口归左侧调色板，「整理/适应/新画板」保留在视图菜单，删除对应 4 条中英文文案。删除实现后不再需要的两张参考 SVG；`.gitignore` 忽略导出的 `exoprosopa.zip`（11 MB，可由构建复现）。`run.bat` 便携分支新增显式警告：无 JVM 时运行的是 `dist/ModMaker` 的旧 jar，改源码后须用 `make-portable.bat` 刷新。（2026-11-05 复核：该回退分支已从 `run.bat` 移除，现只走 `installDist`；此句仅作历史记录）同日给 `mod/` 建独立 git 基线（2063 文件，含换行锁定 `6fe3787`，`.gitattributes` 写 `* -text` 锁住换行，避免 checkout 改写字节破坏 ModMaker 往返一致性）；ModMaker 提交至 `be43896`。建库后金标测试立即暴露一个边界：夹具里的 `.git/` 被当成 payload 期望值，导致往返/zip 计数失败。修法是把 `.git`、`.gitignore`、`.gitattributes`、`.gitmodules` 纳入导入噪声（与已有的 `.git/`、`.idea/`、`.vscode/` 同一条规则），金标测试用同一个 `isNoise` 过滤磁盘侧期望，并新增回归用例——真实用户的 git 工作树也不会再把 VCS 元数据打进包里（287 用例 / 37 类）。

**UI 冗余治理 P0（2026-10-17）**：见 [[UI冗余分析与优化]]。**校验快照**：`ProjectController.issuesCache` 让检查器/校验面板/两个网格共享一次 `Validator.validate`（此前每次编辑 4 次全量，561 记录实测单次 18–26ms），`validate()`（F5）清缓存强制重算，模型变更在派发监听前失效。**隐藏面板惰性刷新**：`IssuesPane`/`StringsPane`/`SpritePane` 增加 `live/stale`，`BOTTOM_STRIP_VISIBLE=false` 时只置脏不重建；`selectTab` 激活时补刷新并统计 `refreshCount`。**死表面清理**：调色板只保留 9 个可创建类型（移除 bullet/unitCommand/unitStance 三个不可点击行）、单位页移除单 chip 行、星球/科技/脚本页不再预留空 48px 子行；扩展脚本 Tab 保留为有提示的禁用占位。回归：`validationIsCachedUntilTheProjectChanges`、`hiddenBottomPaneStaysIdleUntilItsTabIsOpened`、`paletteOnlyListsCreatableTypes`；全量 **298 / 37 类，0 失败 0 跳过**。

**UI 冗余治理 P1（2026-10-17）**：见 [[UI冗余分析与优化]]。**单一类型目录**：`Workspace.of` 扩展 status/weather/team→assets，新增 `Workspace.assetTypes()`（item/liquid/status/weather/team）；资源页 chips 由 `creatable()`（9）改为 `assetTypes()`（5），block/unit/planet/sector 各有专属页不再重复列出；调色板仍保留全部 9 个可创建类型，`addContentRouted` 对 assets 类型补 `showType`。**全局命令条**：标题条下新增 44px `.command-bar`，承载撤销/重做/搜索/校验/构建/实机/停止/日志 + 右侧琥珀「类自适应」开关；画布工具条只留画板选择器。**UiAction 注册表**：新增 `ui/UiAction`，菜单项与命令条按钮共享同一启用态/快捷键/异常处理，`refreshProjectState`/`refreshGameButtons` 只改一处；撤销/重做/搜索/校验/构建/实机不再只在画布页可达。回归：`WorkspaceTest` 补 2 例、`commandBarActionsShareTheirStateWithTheMenu`；全量 **298 / 37 类，0 失败 0 跳过**。

**UI 冗余治理 P2（2026-10-17）**：见 [[UI冗余分析与优化]]。**ContextPanel**：新增 `ui/ContextPanel`，右侧固定 396px 改为四 tab（属性/预览/校验/文本）；`属性`=检查器，`预览`=新 `RecordPreview`（单一预览面，替代资源页 480×560 与检查器 88px/统计行三重重复），`校验`=IssuesPane，`文本`=StringsPane；校验/文本 tab 首次选中才 `setLive(true)`，底栏只保留精灵/日志。**资源页预览移除**：`ItemsPane` 删除 480×560 预览，网格全宽。**检查器去重**：删除 `previewBox`/`statList`，头栏成为唯一身份展示。**selectTab 重定向**：`bottom.validation`/`bottom.strings` 现导向 ContextPanel 对应 tab（校验结果终于可见）。回归：`contextPanelExposesValidationAndTextTabs`、`assetGridListsOneTypeWithCardsAndPageDots`（改断言网格全宽）、几何快照。全量 **298 / 37 类，0 失败 0 跳过**。星球页并排预览迁移到预览 tab、科技树详情编辑器并入属性为 P3。

**UI 冗余治理 P3（2026-10-17，部分）**：见 [[UI冗余分析与优化]]。**Research 单写入口**：新增 `ui/ResearchEditor`，research 编辑迁入检查器「研究」块（唯一 `setField`），`TechTreePane` 改为只读导航、删除 340px 详情编辑器。**Raw 默认只读**：整文件 raw 区（`.raw-tree`）默认不可编辑 + 「编辑原始 JSON」开关/应用按钮。**字符串表唯一写入口**：检查器本地化块改只读摘要 + 「在字符串表中编辑」，`StringsPane`（文本 tab）成为 name/description/details/credit 唯一编辑面。回归：`rawJsonIsReadOnlyUntilTheAdvancedToggle`；全量 **298 / 37 类，0 失败 0 跳过**。留后续：引用候选统一、星球预览迁移。

**检查器预览对齐游戏贴图（2026-10-20）**：`RecordPreview` 从纯色块改为真实图集贴图合成。新增 `ui/SpriteStore`：按 `NameRules.spriteRegion`（`Mods.packSprites` 规则）把项目 assets 映射为 region→PNG，带 `Image`/团队色 tint 缓存；`sprites-override/` 不前缀。**层次**：item/status 走 `UnlockableContent.loadIcon` 的 ui/full 回退链；block 为 body → `-team`（按 `Team.sharded` `#ffd37f` 乘法着色）→ `-top`（含 `-top-0..4` 与地板 `<name>1` 变体）；unit 为 `-outline` → body（`-preview` 回退）；liquid 无图集图标时画液体 `color` 色块，有 sprite 则用 sprite。舞台 300×200、32px 网格、像素艺术走 nearest-neighbour + 固定 2×（32px→64px；超大贴图回退 fit）（贴图不模糊），名称/ID 移到舞台下方。回归：`previewComposesGameSpriteLayers`、`previewScalesPixelArtWithNearestNeighbour`（0 混合像素）；真机夹具抽查 item/block/unit 均有真实像素、liquid 色块正确。全量 **298 / 37 类，0 失败 0 跳过**。未合成：炮塔炮管/`-glow`/`-heat`、多格单位分格、香草内容贴图（预览只展示本模组记录）。

**2026-11-05 批次 1/3/4 落地**：① **夹具误命中修复**（`6629ca2`）——`FixtureProject.hasMetaFile` 改为解析 meta 并要求非空 `name`，`Mindustry-160.7/tools/extra-schemas/mod.json` 不再被当成模组；`FixtureProjectTest` 新增"假 mod.json 不被接受"，14 条失败清零。② **自动保存/崩溃恢复**（`0b2aeff`）——每 2 分钟把脏项目写成 detached 快照到 `modmaker/autosave/`，启动时比较时间并提示恢复；手动保存后清理；新增 `AutosaveTest`。③ **meta 门禁字段可编辑**——项目信息对话框补 `minGameVersion`/`legacyCompatible` 等字段（此前只能改原始 JSON）。④ **`ProjectActions` 抽出**（149 行）——打开/导入/保存/校验/构建/实机等编排从 `ProjectController` 移出；`RecordInspectorLogicTest`、`PlanetPreviewLogicTest` 补上此前无直接测试的两块。⑤ **schema 升到 160.7**（`SCHEMA_PROFILE` 与生成物 `source` 同步）。全量实跑 **491 / 61 类，0 失败、28 跳过，2m10s**。

**未做（按价值排序，2026-11-05 复核）**：① **恢复真实夹具并跑通金标往返**（当前最高优先：25 条夹具用例在 skip）；② 精灵面板续建：导入 PNG 进项目、卡片上预览精灵、拖拽关联内容；③ 工作区深化（余下）：星球与区块树的拖拽归属、科技树里**拖拽改父节点**；④ 扩展脚本工作区（当前禁用占位，`Workspace.script`）；⑤ 恢复底部栏或把校验/日志/精灵/字符串表入口放回主界面（`BOTTOM_STRIP_VISIBLE=false` 时这四处不可达）；⑥ 迷你地图、便签/分组新建入口；⑦ jpackage 单文件分发；⑧ 大导入的进度/取消；⑨ 撤销栈加上限（当前无界）、`ProjectController` 继续拆分（仍 2204 行）；⑩ 运行期内存上涨复测（2026-10-16 决定暂缓）；⑪ git 历史瘦身（82.6 MB，需 `filter-repo` 批准）。

> 2026-10-11 复核说明：原①②（bundle 行模型、校验误报分析）、首页所列「画布与记录双真相」、原①「校验面板消息汉化」、原①「内联记录宿主文件的注释保留」、以及原①「物品/液体表格行内编辑」与⑥「连线时指定堆叠数量」**均已完成**（分别见「bundle 行模型原位替换」「Validator 精度修复」「画布与记录一致性」「校验消息 key+args 与汉化」「内联编辑只改那一段」「物品/液体表格行内编辑」「连线堆叠数量可编辑」段落），本次剔除并重排编号。

**已知限制**：内联编辑现在只重写变化的那几个值（见上方「内联编辑只改那一段」）；但内联对象增删了成员、或路径定位失败时，仍退回整文件重序列化（宿主注释会丢）。类自适应的"同族"按继承链，跨族需求需手动输入类名；schema 运行时字段（wasShooting 等约 30 个）仍在生成物中；底部栏默认隐藏（见上文）。撤销栈无上限（`UndoManager` 持续增长）；`ProjectController` 仍有 2204 行；`ProjectActions` 只抽走了动作编排，编辑命令仍集中。**验证链路**：本机无真实夹具，25 条金标/往返/实机联调用例处于 skip，不能据此宣称"字节级保真已验证"。版本控制：仓库已重建，HEAD `9d720aa`（11-05 文档提交），8 个提交、信息无意义；`.git` 82.6 MB（2790 松散对象、0 pack），旧文档提交号已失效。

> 2026-10-11 复核修正：`.gitignore` 里原写 `.obsidian/workspace.json`——该规则含斜杠、被锚定在仓库根，**匹配不到** `markdown/.obsidian/workspace.json`（Obsidian 每次开启都产生脏项）。已改为 `**/.obsidian/workspace.json`。**2026-10-17 复核**：该文件已不在 git 索引中（`git ls-files` 无匹配），规则已生效，无需再执行 `git rm --cached`。另本轮把测试 JVM 堆设为 2g（见 §2），否则全量 `gradlew test` 会中途杀掉 worker。

## 8. 陷阱备忘录（每条都真实踩过）

- **.bat 文件**：内容必须纯 ASCII；UTF-8 中文注释被 GBK 解析会吞换行符，注释变命令执行。
- **JavaFX**：Region 默认不裁剪子节点（画布必须 `setClip`）；可编辑 ComboBox 按钮显示走 **StringConverter 而非 buttonCell**；FX 应用线程非守护，测试 JVM 会挂死 → 用 JUnit `LauncherSessionListener`（需 testImplementation 的 junit-platform-launcher）在会话结束 `Platform.exit()`，且**工具链每 JVM 只启动一次，测试间严禁 Platform.exit**。
- **中文 properties 文件**：UTF-8 原文读写（游戏官方 bundle 也是 UTF-8 中文，非 \u 转义）；bash 里 heredoc/python 内嵌中文曾静默失败——优先用文件编辑工具。
- **资源文件改动后**必须 `gradlew run`（processResources 会重打包），别用旧进程判断效果。
- **用户会挪动文件**（fixture 曾被移动、README 曾被删）：路径探测要宽容，重要交付物改动前先确认存在。**（2026-10-08 起）**：任何"项目外的位置"都走同一套探测 `core/io/SearchPaths`——先 `-Dmodmaker.*` 属性、再环境变量、再约定相对位置、最后兄弟目录浅扫描；要新增这类定位，先问"能不能探测出来"，不要再写绝对路径。
- **项目迁移断了 `../` 兄弟路径**（2026-10-03 从 `Desktop\mindustry\` 移到 `Desktop\`）：`../格雷工业`、`../Mindustry-160.5`、`../Mindustry/Mindustry.jar` 全部错位，真实资源现在 `..\Mindustry\` 下（深一层）。凡硬编码 `../` 的探测都要补新候选。
- **gold 测试靠夹具**：按 §2 的探测顺序定位；**本机目前没有真实夹具**（`../mod` 不在），25 条夹具用例自动 skip（别把 skip 当全绿，看 "tests completed" 总数）。真实夹具放回 `../mod` 或用 `-Dmodmaker.fixture=<目录>`。
- **仅文件名是 `mod.json` ≠ 模组**（2026-11-05 实测）：Mindustry 源码自带 `tools/extra-schemas/mod.json` 等 schema 片段，旧探测只看文件名，会把它当模组，导致 `mod name is empty`、金标用例 14 条失败。判定必须**解析 meta 且要求非空 `name`**（`FixtureProject.hasMetaFile`）；同批把 `RealModImportTest` 的跳过条件从"目录存在"收紧到"目录里有 `content/`"。完整探测顺序、判定表、根因与排查步骤见 **§4.1**。
- **新克隆缺 `JDK25/`**（2026-11-05 复核）：`gradle.properties` 的 `org.gradle.java.home=JDK25` 是相对路径且属性无 fallback，Gradle 会在加载项目之前报 "Java home supplied is invalid"。先放入 JDK 25（或改绝对路径）再跑 `gradlew`；装 JDK 21 不能构建 25 的 class，也不能支撑运行（`run.bat` 的 "21+" 提示已过时）。
- **`run.bat` 不跑便携包，也回退不了**（2026-11-05 复核）：当前 `run.bat` 只找 `JAVA_HOME`/PATH 上的 Java（提示写 21+，实际需 25）；`build/install` 缺失时先 `gradlew installDist`，再启动生成的 launcher。**没有 JVM 就直接报错退出**，不会回退 `dist\ModMaker`（旧文档与 `make-portable.bat` 注释里的"无 JVM 回退"已过时）。便携包只能手动双击 `dist\ModMaker` 或重打：`make-portable.bat` = `gradlew jar` → 复制进 `dist\ModMaker\app` → **用便携 runtime 出图自检**；runtime 缺失时用 `jlink --add-modules java.se,jdk.unsupported --strip-debug --no-header-files --no-man-pages --compress=zip-6` 重建（`zip-6` 实测 48.5MB/179 文件）。判断"界面没变化"是否因为旧 jar：直接查包的 jar 内容（`[System.IO.Compression.ZipFile]` 看 `dev/modmaker/**` 条目或 `messages_zh_CN.properties` 里的新键），比看界面可靠。
- **项目迁移会同时断掉夹具与游戏 jar**（2026-11-05，第二次迁移）：从 `Desktop\Mindustry\` 到 `Desktop\MindustryIDE\` 后，`../mod` 与 `../Mindustry/Mindustry.jar` 都不在新位置，夹具探测转而命中源码里的假 `mod.json`。迁移后先检查 §2 的夹具/游戏 jar 两项，再跑全量测试。
- **不能在盘符根/桌面根建项目**（2026-10-11 用户实测）：把项目建到 `C:\` 根、或**桌面这一层**（`…\Desktop\my-mod`），会被系统/安全软件拒绝，报 `AccessDeniedException: <目标目录>`；但写入桌面的**已有子目录**（`…\Desktop\Mindustry\`）不受影响。本机 Defender 服务 Stopped、由**火绒**接管，符合"安全软件保护桌面（防勒索/改快捷方式）"的行为。`DirectoryChooser` 若不设初始目录，默认停在 Windows 的"上次位置"，很容易正好是根目录。新建流程已加**初始目录（用户主目录）+ 可写性预检**；凡是"用户指定落盘位置"的新入口，都照此办理（先探测再写，别把原始 IOException 抛给用户）。注意：ACL 正常 ≠ 可写——`Files.isWritable` 对 Windows 目录不可靠，`createDirectories` 对已存在目录又是 no-op，预检必须真的写一个临时文件。
- **JavaFX 类的包归属**（2026-10-13）：布局容器 `TilePane`/`FlowPane`/`GridPane`/`HBox`/`VBox` 在 `javafx.scene.layout`，控件 `TableView`/`TreeView`/`TabPane`/`ScrollPane` 在 `javafx.scene.control`——写错包名只在编译期报"找不到符号"，很容易误以为是 API 不存在（本轮 `TilePane` 踩过，`TilePane.getColumns()` 也不可靠，改用自己记的列数）。
- **UI 尺寸/样式断言必须先 `applyCss(); layout();`**（2026-10-13）：未布局的节点 `getBoundsInLocal()` 是 0，断言会"假绿"或误报。`UiSmokeTest.prototypeThemeSizesHeaderTabsAndPalette` 是这条的范例（36/64/64/48px 全部在布局之后读）。

## 9. 接手约定

- 改 core 必须带 JUnit；改 UI 必须带场景图断言或像素断言（参考 `UiSmokeTest` 现有三例）。
- 消息键：UI 文案进 `messages(_zh_CN).properties`；标识符标签进 `field-labels/class-labels(_zh_CN).properties`（未翻译自动回退原键名，schema 重扫后新字段无需立即翻译）。校验器的消息 key 一律以 `validate.` 开头，**中英两份必须同时补齐**（`ValidatorLocalisationTest` 会校验 key 齐全）；`messages_en.properties` 是**有意为空**的锚文件（只写注释，不要放 key），它让英语请求落到英文基包而不是默认 locale 包。
- 每轮迭代结束更新**本文的"状态清单"、[[首页]] 的当前状态快照/仓库状态**与 `architectureCN_ZH.md`（追加小节，别改写历史章节——它是决策记录）。数字一律写实测值与日期，不用"应该/预计"。
- 用户偏好：中文交流与中文界面；实验性功能做成**可见开关**（默认开+琥珀色标注）；交付前主动验证并给截图/数据，不做"应该可以"的口头承诺。
