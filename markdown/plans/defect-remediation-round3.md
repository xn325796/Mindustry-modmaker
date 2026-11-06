# 缺陷修复（第三轮）实施记录

> 日期:2026-10-25
> 来源:用户请求「检测缺陷,并提出修复计划,并执行」
> 基线:HEAD `d6b7d31` + 工作区未提交改动(cli/、ReferenceRewrite 等),测试全绿(298/37)
> 方式:三路并行缺陷侦查(core 格式层 / validator+schema / ui+近期提交),全部发现均经代码路径核实并对照 Mindustry-160.5 源码,再外科手术式修复

与既有 `markdown/modmaker-defect-report-verified.md`(H/M/L 全部修复)及 `defect-remediation.md`(D1–D13、B2 已完成)不重复;本轮全部为**新发现**。

## 已修复(15 处)

### 批次 A — core 数据正确性

| # | 缺陷 | 证据 | 修复 | 回归用例 |
|---|---|---|---|---|
| A1 | 嵌套数组关闭后 `inArray` 被清零而非恢复,外层数组后续裸值不再按逗号终止:`[[1], a, b]` 解析成 2 个元素(`"a, b"` 合并),与游戏 Jval 分歧 | `HjsonReader.java:185,202-204` | `parseArray` 保存外层 `inArray` 并在 `finally` 中**恢复** | `HjsonReaderTest.aNestedArrayDoesNotStopCommaSeparationInTheOuterOne` |
| A2 | 注释行被当作可续行:游戏(java.util.Properties / PropertiesUtils)对 `#`/`!` 行不续行,`# see docs at C:\` 会吞掉下一行的键,再编辑时写出重复定义 | `PropertiesFile.java:167-179` | 续行判断跳过注释逻辑行 | `FmtRulesTest.aCommentLineIsNeverContinued` |
| A3 | 非法 `\u` 转义解码时丢失反斜杠:`"\uZZZZ"` → `"uZZZZ"`(值被静默改变) | `PropertiesFile.java:261-272` | 保留 `\` 再接 `u` | `FmtRulesTest.anInvalidUnicodeEscapeKeepsItsBackslash` |
| A4 | Windows 工具打包的 zip 用 `\` 作分隔符,reader 规范化成 `/` 后再 `archive.getEntry(name)` 查不到,**文件无提示丢失** | `ModPackageReader.java:131,166-169` | 枚举时保留 `ZipEntry` 引用(`entriesByName`),不再按名字重查 | `ImportAndSaveGuardsTest.zipEntriesWithBackslashSeparatorsAreNotDropped` |
| A5 | 构建目标的祖先/嵌套判断用大小写敏感的字符串比较:Windows/macOS 下大小写变体路径会绕过守卫——`pruneStale` 会删掉项目本体文件、`requireOutside` 漏判嵌套目标导致每次构建套娃 | `ModExporter.java:111,264` | 两个前缀判断改为 `regionMatches(true, ...)`(忽略大小写;Linux 上偏保守方向) | `ExporterPruneTest.aDifferentlyCasedAncestorTargetNeverPrunesTheProject`、`.aDifferentlyCasedTargetInsideTheProjectIsRefused` |

### 批次 B — Validator 误报/漏报

| # | 缺陷 | 证据 | 修复 | 回归用例 |
|---|---|---|---|---|
| B1 | `validate.name.duplicate` 对 patch 记录是**误报 error**:游戏只在**新内容**重复注册时抛异常;patch 复用已定位内容(ContentParser.java:583-591) | `Validator.java:130-134` | 涉及 patch 的重复降为**新 warning** `validate.name.duplicatePatch`;两个新记录同名仍是 error | `ValidatorPrecisionTest.duplicateNamesInvolvingAPatchStayTolerated`、`.twoNewRecordsWithTheSameNameAreStillAnError` |
| B2 | 仅大小写不同的两个记录(`duo`/`Duo`)游戏允许,但 Windows/macOS 文件系统存不下两个文件,保存时**丢记录** | `Validator.java:130`(键大小写敏感)、`ProjectIo.java:322` | 新增 error `validate.name.caseCollision`(大小写折叠分组) | `ValidatorPrecisionTest.namesDifferingOnlyInCaseAreAnError` |
| B3 | `validate.reference.unresolved` 是 warning,但具体 Content 字段解析不到时游戏**抛异常整文件加载失败**;仅 `research` 宽松(只记日志) | `Validator.java:327`;ContentParser.java:505-514,1401-1405 | 非 `research` 字段升为 error;`research` 保持 warning | `ValidatorPrecisionTest.anUnresolvedConcreteReferenceIsAnErrorButResearchStaysAWarning` |
| B4 | 单位 `template` 小写首字母误报(`tankUnitType` 游戏会规范化为 TankUnitType);`type` 路径早已修复,template 漏掉 | `Validator.java:280`;ContentParser.java:1438 | 与 `validateClass` 一致,先 `SchemaRegistry.canonicalClassName` 再查类名表 | `ValidatorPrecisionTest.aLowercaseUnitTemplateIsCanonicalisedLikeTheGameDoes` |
| B5 | 单位 `type` 为对象/数组时被静默放过;游戏抛 "Types must be strings"(ContentParser.java:627-628),整文件失败 | `Validator.java:249`(className() 只读 primitive) | 新增 error `validate.unit.typeNotString` | `ValidatorPrecisionTest.aUnitTypeObjectOrArrayIsReportedInsteadOfSilentlyAccepted` |

i18n:三个新键(`validate.name.duplicatePatch`、`validate.name.caseCollision`、`validate.unit.typeNotString`)已同步补入 `messages.properties` 与 `messages_zh_CN.properties`(由 `ValidatorLocalisationTest` 守护键齐性)。

### 批次 C — UI

| # | 缺陷 | 证据 | 修复 |
|---|---|---|---|
| C1 | 资源网格通过搜索跳转/新增路由切换类型时 `resourceType` 不同步:子标题的「添加」按钮仍创建旧类型(如搜到状态记录后点添加却造出物品),cc8a206 把 status/weather/team 路由进资源页后变得可达 | `AppShell.java:277,574-576,640-641` | 新增 `showAssetsType(type)`,两处调用点同步设置 `resourceType` |
| C2 | 损坏 PNG 被解码为 error Image 后**照常入缓存**;后续 `tinted()` 构造 `WritableImage(0,0)` 抛异常,中断整个选择监听器派发 | `SpriteStore.java:117-124,144` | `image()` 检查 `isError()`/0 尺寸不入缓存;`tinted()` 对 0 尺寸返回 null |
| C3 | 研究目标含非 primitive target(原始 JSON 编辑或导入模组)时 `getAsString()` 抛异常,拖垮 ListView 单元格渲染 | `ResearchEditor.java:213-214` | 非 primitive 走 `toString()` |
| C4 | 游戏启动观察线程:console.log 打不开时 stdout 不再被排水,游戏输出缓冲填满后会**冻住游戏**;`readProblems` 严格 UTF-8,中文 Windows 上非 UTF-8 字节让退出摘要整体丢失 | `GameLauncher.java:109-121,164` | writer 打开失败时继续排水(仅不落盘);日志读取改用 REPLACE 解码器 |

### 批次 D — schema 默认值漂移(策展数据 vs 游戏源码,仅影响检查器展示)

`schema.json` 9 处 default 与游戏真实默认不符(均对照 160.5 源码逐一核实):`PowerNode.maxNodes` 10→3、`ItemTurret.range` 110→80、`UnitType.speed` 1→1.1、`accel` 0.6→0.5、`itemCapacity` 0→-1、`buildSpeed` 0→-1(desc 同步改为 "-1 = cannot build")、`targetPriority` -1→0、`StatusEffect.reactive` true→false、`Liquid.boilPoint` 0.5→2、`Item.cost` 0.5→1、`BulletType.speed` 5→1。

## 顺带修正的既有测试(固化了旧行为)

1. `FmtRulesTest.anInvalidUnicodeEscapeIsReportedInsteadOfSilentlyRekeyed` 期望 `bu12`——这正是 A3 所修的"丢反斜杠"行为被测试固化;期望改为 `b\u12`。
2. `ModMakerCliTest.strictPromotesWarningsToExitTwo`、`.buildStrictBlocksWarningsAndWritesNothing` 夹具用 requirements 引用不存在的物品并期望其只是 warning。经游戏源码核实(ContentParser.java:505-514 对任何 Content 字段 throw,ItemStack 的 item 同样走该路径),这是 **error**,夹具改用真正 warning 级的 `research` 悬垂引用,保持测试"warning → strict 升级为失败"的语义。

## 登记未修(Follow-up)

1. `discardFinishedSession` 在观察线程仍读 `last_log.txt` 时删除草稿目录,退出摘要偶发丢失(需跨线程协调,单独任务)。
2. `ResearchEditor` 每次编辑全量重建检查器并抢焦点(对比字段编辑的 `selfEdit` 守卫)。
3. `CosmosPane.recordFor`:星球与板块同名时点击选中错误记录(唯一性按类型,标签不按类型)。
4. `NodeCanvasPane.nodeAt` z 序:重叠卡片命中的是底层卡。
5. `ItemsPane.refresh()` 每次项目变更重置分页到第 1 页。
6. `PlanetPreview` 云层 ticker:外观页签选中时即使切到其它工作区仍每 120ms 重绘(TabPane 不分离非选中页)。
7. `detectJava()/detectGameJar()` 在 FX 线程上做 PATH/Program Files 扫描,首次 F6 可能卡顿。
8. `TechTreeIndex.isVanilla` 匹配任意原版内容名而非实际科技树节点,派生视图与游戏提示有分歧(仅显示)。
9. `HjsonReader` `'''` 块内 CRLF 规范化为 LF(与 arc `readMlString` 是否一致待对照)。
10. `TechTreeRules.requirementText` 数量经 `getAsString()`,JSON 浮点 `10.0` 往返成 `"copper/10.0"`(游戏可解析,纯值显示)。
11. `JsonTextPatch` 重复键定位第一个出现、解析保留最后一个:命中时整文件回退全量重序列化(保存仍正确,仅丢该文件注释)。
12. 策展 `extends` 捷径覆盖扫描父链(ItemTurret 只合并 Block 而非 Turret→ReloadTurret→BaseTurret 链),检查器字段覆盖收窄;无校验错误。

## 验证(2026-10-25)

- **全量回归**:`gradlew test --rerun` —— **347 用例 / 0 失败 / 0 跳过**(本轮新增 12 条:A1 一条、A2/A3 两条、A4 一条、A5 两条、B1–B5 六条;既有 2 条测试因固化了旧行为而修正期望,见上节)。
- **红验证(抽样)**:临时把 A1 的修复还原为 `inArray = false` 后,`HjsonReaderTest.aNestedArrayDoesNotStopCommaSeparationInTheOuterOne` 转红(同文件其余用例保持绿),恢复修复后通过——用例非空转。
- 关键守护用例全绿:`RoundTripTest.realModRebuildsByteForByte`(真实模组逐字节往返)、`SecurityHardeningTest`、`ProjectIoSavePlanTest`、`ModExporterAtomicityTest`、`ValidatorLocalisationTest`(新 i18n 键两语言齐性)。
- 环境注:`JAVA_HOME` 未入 PATH,运行 gradle 需 `JAVA_HOME="C:/Program Files/Java/jdk-21.0.12.1"`。
- 提交:遵守项目约定,本轮改动未提交,待用户确认后按批次 A/B/C/D 分批提交。
