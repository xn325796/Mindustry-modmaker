# 节点画布:结构化多引用字段可连线(2026-10-25)

> 来源:用户请求「分析方块与单位的 json,当前的节点画布仍然不能有效编辑,一些组和字段可以用节点外连,例如建筑需求和输入产出类多引用的字段」

## 分析结论

画布的端口机制此前只为 `content | stack | stack[]` 类型的字段开端口,覆盖 `Block.requirements`、`outputItem(s)`、`outputLiquid(s)`、`researchCost` 等;而方块/单位 JSON 里真正难写的**嵌套多引用组**全部被策展 schema 标为 `raw`,既无端口、不可连线,重命名/删除时也只有部分被重映射:

| 字段 | 游戏 JSON 形态 | 此前状态 |
|---|---|---|
| `Block.consumes` | `[{items:[copper/10], liquids:[water/20], power:0.1}]` | raw,无端口;重映射有 |
| `ItemTurret.ammoTypes` | `{copper: {type:…}}`(**map 键**即物品引用) | raw,无端口;重映射有 |
| `LiquidTurret.ammo` | `{water: {…}}`(同上,液体) | raw;重映射无 |
| `UnitType.requirements` | `[{block: 工厂, requirements:[copper/10], time}]` | raw,无端口;重映射有 |
| `UnitFactory.plans` | `[{unit: 单位, requirements, time}]` | raw;**重映射无(漏网 bug)** |
| `Reconstructor.upgrades` | `[[fromUnit, toUnit], …]` | raw;**重映射无(漏网 bug)** |

顺带发现并修正:策展 schema 把 ItemTurret 的弹药字段误写成 `ammo`(游戏字段名为 `ammoTypes`,ItemTurret.java:24)。

## 实现:FieldType.struct(结构化多引用组)

- **`FieldType.struct`**:新增控件类型,schema 拼写 `struct:shape`(shape 进 stackKind,与 `stack[]:item` 同语法)。
- **策展 schema 重定型**:`consumes→struct:consume(any)`、`ItemTurret.ammoTypes→struct:ammoMap(item)`、`LiquidTurret.ammo→struct:ammoMap(liquid)`、`UnitType.requirements→struct:unitReq(block)`、`UnitFactory.plans→struct:plan(unit)`。检查器里这些字段保持裸 JSON 编辑(画布管连线,检查器管整组)。
- **连线语义(全部经 ReferenceOps,可撤销)**:
  - `consume`:物品→第一个组的 `items`,液体→`liquids`(无槽位时并入第一个组/新建组);双击连线可改数量(嵌套栈可被找到,"copper/10" 字符串形式也会被升级为对象形式);断开连线剥离对应栈,空槽位/空组/空结构逐级清理。
  - `unitReq`:先连**工厂方块**→创建 `{block, requirements:[]}`(游戏默认 time=600,ContentParser.UnitReq);再连物品→填充首个条目的需求。物品先于工厂连线被拒绝并提示。
  - `plan`:先连**单位**→创建 `{unit, requirements:[]}`;物品填充同上。
  - `ammoMap`:连物品/液体→加 map 键,值为**空对象**——游戏对缺 `type` 的弹药对象默认解析为 BasicBulletType(ContentParser.java:185),子弹对象仍走检查器的内联编辑。
- **LinkGraph/Validator 按形态提取引用**:ammoMap 提取 map 键;其余沿通用命名键(block/unit/item/liquid)+ 新增的**容器递归**(UnitReq 的嵌套 requirements 数组、consume 组),字符串栈剥掉 `/数量`;struct 字段解析引用时先按 contentCtype 匹配、失败放宽到任意类型(unitReq 同时装着 block 引用与 item 栈)。`"type": "BasicBulletType"` 这类未知键下的标量永不被当作引用。
- **ReferenceRewrite 补漏**:EXTRA 列表加入 `plans`(按 unit 提示重写)与 `upgrades`(`[[from,to]]` 对的逐元素重写/删除);ammoMap 重写泛化到任意字段名 + 键类型(LiquidTurret 的液体键此前根本不会被重映射)。

## 测试(新增 10 条,全绿)

- `ReferenceOpsTest` +4:consume 组增长/嵌套数量编辑/"copper/10" 升级/逐级清理;unitReq 工厂→物品次序与拒绝语义;ammoMap 键写入/撤销/清空;plan 同构。
- `LinkGraphTest` +2:unitReq/consume 按形态派生边(工厂边+物品栈边);ammoTypes map 键派生边。
- `ReferenceRewriteTest` +2:单位重命名跟进 plans(unit 键 + 需求栈);删除单位丢弃对应 plan 与 upgrades 对(未知名对保留)。
- `ProjectControllerInteractionTest` +3:真连线写 consumes(物品/液体各进各槽)、断开只剥自己的栈;unitReq 工厂→物品次序与拒绝;ammoTypes 键写入与撤销。

## 真实模组验证与连带修复

金标准夹具(Exoprosopa)暴露并修复了三处提取精度问题,最终**校验错误 0、往返逐字节一致**:

1. consume 组的具名键值也是栈拼写(`"liquid": "bioliquid/0.25"`——液体量可为小数),具名键分支同样剥 `/数量`(LinkGraph 与 Validator 各自的收集器)。
2. 嵌套容器递归(UnitReq 的 requirements、consume 组的 items/liquids):只递归数组/对象,未知键下的标量(子弹的 `"type": "BasicBulletType"`)永不当引用。
3. struct 字段的引用解析先按槽位类型、失败放宽到任意类型(UnitReq 同时装 block 引用与 item 栈);ReferenceRewrite 的 `accepts` 同理放行 struct(shape 名不是类型名)。
4. `RealModImportTest.collectStrings` 同步收集 map 键(ammoTypes 的引用在键上)。

## 后续候选(未做)

- `weapons`(武器→子弹)与 `Reconstructor.upgrades` 的画布连线语义(upgrades 的 from/to 配对语义尚需产品决策)。
- 检查器里 struct 字段的结构化列表编辑器(mesh 编辑器是先例)。
- 端口上限(可见 4 个)在字段增多时的分组显示。
