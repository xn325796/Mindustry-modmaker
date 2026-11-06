# 表单覆盖优化:按游戏源码规则减少手写 JSON(2026-10-25)

> 来源:用户请求「仍然有很多字段需要手动编写,根据游戏源码的规则,继续优化 UI 的表单式编入」
> 前置:第三轮缺陷修复(见 defect-remediation-round3.md),347 用例全绿

## 问题

检查器的表单行由 schema 字段类型驱动(11 种控件),但两类根因让大量字段退化成裸 JSON 文本框:

1. **策展 `extends` 捷径切断真实继承链**:`schema.json` 手写 ItemTurret 直接 extends Block,而游戏实际按 Java 类链 Turret → ReloadTurret → BaseTurret 解析(81 个字段:shootCone、maxAmmo、inaccuracy、targetAir、heatRequirement…)全部不可表单编辑。
2. **扫描层把可枚举/可命名类型一律标成 `raw`**(298 个):`Block.group`(BlockGroup 枚举)、`attribute`(Attribute 静态实例类)、各处 `Sound` 字段(游戏按资产名字符串解析,ContentParser.java:310-319)都只能手写。

## 改动

### 1. 字段合并改走真实继承链 — `SchemaRegistry.collect`

`fieldsOf` 从沿 `schema.parent`(策展层可覆写)改为沿 `trueParent()`(源码扫描的 `hierarchyParent` 优先)。策展字段仍按字段名获胜,只影响**祖先链**。效果:ItemTurret/LiquidTurret/PowerTurret 等炮塔类补全 81+ 个 Turret 链字段;策展层没有的类(纯手写)行为不变。

扫描链在 Block 处自然终止(fields.json 只含内容类,UnlockableContent/Content 不在),无根类污染。Turret 链尾部的运行时字段(curRecoil、heat、target 等)保留显示——同一名字在不同类里含义不同(如 `x/y` 在 UnitType 与 Turret),静态隐藏名单会误伤,不做隐藏。

### 2. 扫描器识别封闭取值集 — `SchemaBootstrap`

- 新增 `ENUM_DECLARATION` / `ADD_VALUE` 两个模式 + `scanValueSets`:扫描 `public enum X {…}` 常量与 Attribute 式 `X.name = add("name")` 静态实例注册;带匿名类体的枚举(BuildVisibility)无法可靠解析,**整类跳过**(残缺取值列表比没有更糟,策展层已覆盖 buildVisibility)。
- `draftType`:命中取值集 → `enum`;`Sound` → `text`。
- fields.json 字段格式新增 `"v":[…]`(取值列表)。
- `SchemaRegistry.loadGenerated` 读取 `"v"` 进 `enumValues`。

### 3. 重新生成 fields.json

`gradlew schemaBootstrap`(扫描 ../Mindustry-160.5)。变化:298 raw → 240 raw(**9 个 enum + 49 个 text**),其余类型数量不变,无字段丢失。新可表单编辑的典型字段:

- `Block.group` → 下拉(12 个 BlockGroup 常量)
- `ThermalGenerator.attribute` 等属性字段 → 下拉(heat/spores/water/oil/light/sand/steam)
- 49 个 Sound 字段(Turret.shootSound、UnitType.deathSound…)→ 普通文本框

控件层(RecordInspector)零改动:enum/content/text 控件本就存在,只是此前没有数据。

## 回归测试(SchemaRegistryTest 新增 3 条)

- `curatedExtendsShortcutsDoNotCutTheScannedChain`:ItemTurret 表单必须含 Turret 链的 shootCone/maxAmmo/inaccuracy/reload/range/health
- `scannedEnumFieldsCarryTheirValues`:Block.group 枚举含 walls/transportation;ThermalGenerator.attribute 含 sand
- `soundFieldsAreTextBecauseTheGameReadsAssetNames`:shootSound/deathSound 为 text

## 后续候选(未做)

- **weapons 结构化编辑器**(UnitType.weapons = Weapon[] 对象带嵌套 bullet):单兵作者最痛的 raw 字段,但需要嵌套记录编辑框架,工作量独立成项。
- 运行时字段与配置字段的区分(需语义级标注,正则扫描不可行)。
- BuildVisibility 类带方法体枚举的可靠解析(现状:留 raw,策展层补)。
