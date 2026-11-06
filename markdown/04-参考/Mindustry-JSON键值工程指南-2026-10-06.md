---
tags:
  - 工程指南
  - mindustry-json
  - 参考手册
source: https://learn-mindustry-mod.github.io/json/json_1_gemini/x01-basic.html
status: current
created: 2026-10-06
---

# Mindustry JSON 键值工程指南（教程蒸馏）

> 来源：[L.M.M. 教程 Gemini 版 0x01–0x09](https://learn-mindustry-mod.github.io/json/json_1_gemini/index.html)，2026-10-06 蒸馏。
> 用途：作为 Mindustry-modmaker 项目「用户友好化」改造的数据依据——字段中文释义、单位换算、校验规则、命名自动化均以此为准。字段未列全时按 0x09 方法论查源码补齐（见第八节）。
> 关联：[[项目分析-2026-10-05]]

---

## 一、模组路径指南（目录结构）

纯 JSON 模组**必须**遵守官方约定结构（Java 模组可自定义路径，如《饱和火力》的 `SFcontent/` + `classes.dex`）：

| 路径 | 必需 | 内容 |
|---|---|---|
| `mod.json` / `mod.hjson` | ✅ | 模组身份证（配置入口） |
| `content/items/` | 按需 | 物品（Item） |
| `content/liquids/` | 按需 | 流体（Liquid） |
| `content/blocks/` | 按需 | 方块（工厂/炮塔/电力/环境/单位工厂等），**允许子目录**：`production/`、`turrets/`、`units/`、`environment/` |
| `content/units/` | 按需 | 单位（UnitType） |
| `content/status/` | 按需 | 状态效果（StatusEffect）；游戏同时接受历史复数目录（本项目 `ContentType.acceptedFolders` 已兼容 status/statuses） |
| `content/planets/` | 按需 | 星球（Planet） |
| `content/sectors/` | 按需 | 区块（SectorPreset） |
| `sprites/` | 按需 | 模组专属贴图（png） |
| `sprites/blocks/environment/` | ⚠️ 强制 | **环境方块贴图必须放这里**，否则地形显示为粉黑错误方块 |
| `sprites-override/` | 按需 | 覆盖原版贴图 |
| `bundles/` | 按需 | 多语言文件 `bundle_zh_CN.properties`（UTF-8 无 BOM） |
| `sounds/` | 按需 | 自定义音效（**必须真 .ogg 转码**，不能改后缀） |
| `maps/` | 按需 | 区块地图 `.msav` |
| `scripts/` | 按需 | JavaScript 脚本（特殊逻辑） |

**打包与导入**：直接全选 `mod.json` 与各文件夹压缩为 `.zip`；打开压缩包第一眼必须看到 `mod.json`（多套一层文件夹 → 游戏报 `No mod.json found`）。导入：设置 → 模组 → 导入模组 → 从文件导入。

**编辑器警告**：禁用 Windows 记事本（自动加 BOM 导致解析失败）；所有标点必须英文半角（中文冒号 `：` 与漏 `}` 占解析报错 50% 以上）。

---

## 二、命名与引用规则（内部名体系）

| 规则 | 说明 |
|---|---|
| 模组内部名 `name` | 只用小写字母+连字符；游戏自动把空格转连字符、大写转小写 |
| 内容完整内部名 | `<模组name>-<文件名>`，如 `my-first-mod-silicon-steel`；JSON 文件名本身**不加**模组前缀 |
| 跨文件引用 | 自定义内容必须带模组前缀；原版内容直接用原名（如 `copper`、`plastanium`、`groundZero`） |
| 贴图命名 | 与 JSON 文件名完全一致（物品 32×32 透明 png）；缺失时物品显示为白色问号块 |
| Drawer 后缀贴图 | `<方块名><suffix>.png`，如 `silicon-steel-smelter-bottom.png`、`-glow`、`-weave` |
| 炮塔部件贴图 | `<炮塔名><suffix>.png`，如 `laser-blaster-barrel.png` |
| bundle 键格式 | `[类型].[模组名]-[文件名].[属性]`，如 `item.my-first-mod-silicon-steel.name=硅钢`（属性含 `.name` / `.description`） |
| 音效引用 | 写文件名不带 `.ogg` 后缀，如 `shootSound: heavy-shoot` |

---

## 三、Hjson 语法要点（Mindustry 方言）

- 可省略大多数引号与行尾逗号；支持 `//` 单行与 `/* */` 多行注释。
- **堆栈缩写糖**：`copper/10` ≡ `{item: copper, amount: 10}`；流体同理 `water/0.2`、产物 `my-first-mod-silicon-steel/2`。
- 文件编码 UTF-8 **无 BOM**。
- 缺省回退：未填字段取默认值（如 Item 属性默认 0/false），最小可用定义只需 `type` + 必要键。

---

## 四、mod.json 键表（0x01）

| 键 | 作用 | 游戏内表现 |
|---|---|---|
| `name` | 内部唯一标识符 | 引用贴图/物品时的前缀来源 |
| `displayName` | 显示名称（可中文） | 模组列表中看到的名字 |
| `author` | 作者 | 模组列表展示 |
| `description` | 介绍 | 模组详情展示 |
| `version` | 版本号 | 模组列表展示 |
| `minGameVersion` | 最低游戏版本 | 低于该版本的游戏无法加载；v7 推荐 `"136"` 或 `"146"` |
| `java` / `main` | Java 模组入口（纯 JSON 模组不写） | — |

---

## 五、内容类型键值总表（键 → 作用 → 游戏内表现）

### 5.1 Item 物品（0x02）`content/items/`

| 键 | 示例 | 作用 | 游戏内表现 |
|---|---|---|---|
| `type` | `Item` | 声明类型 | — |
| `color` | `7595D2`（6 位 hex 无 #） | 基础颜色 | 小地图、传送带发光、粒子特效颜色 |
| `explosiveness` | `0`~`1.2` | 爆炸性 | 作弹药/被摧毁时的爆炸范围与伤害 |
| `flammability` | `0`~`1.0` | 可燃性 | 作燃料的燃烧时间/引火几率 |
| `radioactivity` | `0`~`1.0` | 放射性 | RTG 发电机效能；可被 `itemRadioactive` 匹配 |
| `charge` | `0.45` | 带电性 | 作建材时的电力/蓄电池容量加成 |
| `hardness` | `1`/`3`/`4` | 硬度 | **钻头 `tier` 必须 ≥ 它才能开采**（铜铅1、钛3、钍4） |
| `cost` | `1.75` | 建材时间倍率 | 用它造的方块建造更慢 |
| `research` | 见 5.10 | 科技树 | 战役研究面板节点 |

### 5.2 Liquid 流体（0x02）`content/liquids/`

| 键 | 示例 | 作用 | 游戏内表现 |
|---|---|---|---|
| `color` | `7CF389FF`（8 位，末两位=透明度） | 基础色 | 流体渲染与发光 |
| `lightColor` | `7CF38970` | 光照色 | 流体发光效果 |
| `temperature` | `0.5`=室温 | 温度 | <0.5 冷冻液（可冷却炮塔）；>0.5 高温（矿渣 1.5） |
| `heatCapacity` | `1.45` | 热容 | 冷却效率 |
| `viscosity` | `0.3` | 粘稠度 | 管道流速、进入方块速度（水0.5、矿渣0.7） |
| `effect` | `freezing`/`melting` | 接触状态 | 单位踩上去获得状态效果 |
| `research` | `{parent: plastanium}` | 科技树 | 研究面板 |

### 5.3 GenericCrafter 工厂（0x03）`content/blocks/`

**方块通用键**（炮塔/发电机等所有 Block 共享）：

| 键 | 示例 | 作用 | 游戏内表现 |
|---|---|---|---|
| `type` | `GenericCrafter` | 子类声明 | 决定行为模型 |
| `size` | `3` | 占地 N×N（不支持长方形） | 建筑尺寸 |
| `health` | `400` | 血量（3×3 工厂常取 300~500） | 血条 |
| `category` | `crafting` | 分类 | 建造菜单页签：crafting/power/turret/defense/distribution |
| `requirements` | `[copper/100 lead/50]` | 建造材料 | 建造消耗与造价 |
| `buildCostMultiplier` | `0.8` | 建造时间倍率 | 0.5=快一倍（别去改物品 cost） |
| `itemCapacity` | `60` | 内部物品容量（教程称默认 24） | 容量不足会导致「装不下原料」而罢工 |
| `liquidCapacity` | `100` | 内部液体容量 | 缓冲液体 |

**工厂专属键**：

| 键 | 示例 | 作用 | 游戏内表现 |
|---|---|---|---|
| `craftTime` | `90` | 单次合成 tick 数（60=1秒） | 生产节拍 |
| `outputItem` | `my-first-mod-silicon-steel/2` | 产物（自定义物品**必须带模组前缀**） | 产出物 |
| `outputItems` | `[silicon/2 graphite/1]` | 多产物（复数数组） | 一次合成吐多种物品；随机产出要改用 `Separator` |
| `outputLiquid` | `slag/0.2` | 流体副产物 | 排液 |
| `consumes.items` | `[silicon/4 titanium/2]` | 每次合成的物品消耗 | 进料 |
| `consumes.power` | `1.5` | **每 tick** 耗电（1.5×60=90/秒） | 电力需求 |
| `consumes.liquids` | `[water/0.2]` | 流体消耗 | 进液 |
| `craftEffect` | `smeltsmoke` | 完成瞬间特效 | 生产反馈 |
| `updateEffect` | `pulverizeSmall` | 运转中持续特效 | 冒火花等 |
| `ambientSound` / `ambientSoundVolume` | `loopSmelter` / `0.88` | 环境音 | 工作噪音 |
| `ignoreLiquidFullness` | `true` | 液体满不停工 | 多余液体凭空蒸发 |

**消耗语义**：`items` 为结算式（进度满才扣）；`liquid`/`power` 为流式（每帧扣），供应不足时压低 `efficiency` → 表现为**变慢而非停机**。可选输入 `optional: true` 不会提速；`booster: true` 表达加速语义。

**Drawer 绘制器系统**（`drawer` 键，DrawMulti 按数组从底到顶叠加）：

| Drawer | 效果 | 贴图后缀 |
|---|---|---|
| `DrawDefault` | 主体贴图 | 无 |
| `DrawRegion` | 指定静止图层（`suffix`） | 自定义，如 `-bottom`、`-top` |
| `DrawGlowRegion` | 发光层，亮度随 warmup（0→1 预热）变化 | `-glow` |
| `DrawLiquidRegion` | 颜色随内部液体变化的图层 | `-liquid` |
| `DrawLiquidTile` | 平铺液体（`drawLiquid`、`padding`） | 无 |
| `DrawFlame` | 周期性跳动的火光 | `-top` |
| `DrawCrucibleFlame` | 坩埚火焰（`flameColor`/`flameRadius`/`flameRadiusMag`） | 无 |
| `DrawWeave` | 旋转梭子（相织布机） | `-weave` |
| `DrawPistons` | 周期性收缩活塞 | `-piston` |

律动黑话：`*Mag` = 振幅（跳多剧烈），`*Scl` = 频率（跳多快），底层是正弦函数。

### 5.4 生产与电力方块（0x04）

| 类型 | 关键键 | 作用 | 游戏内表现 |
|---|---|---|---|
| `Drill` 钻头 | `tier`、`drillTime: 300`、`liquidBoostIntensity: 1.5` | 采掘等级/基础采掘时间/供水加速倍率 | **tier ≥ 矿 hardness 才能挖**；给水提速 |
| `Pump` 泵 | — | 抽取液体 | — |
| `ConsumeGenerator` 消耗发电 | `powerProduction: 550`（每 tick）、`itemDuration: 19.5`、`generateEffect: generatespark` | 发电量/燃料续航/发电特效 | 烧燃料发电冒火花 |
| `ThermalGenerator` 热能发电 | `powerProduction: 3.6`、`attribute: heat`、`floating: true` | 实际发电 = 基础值 × 覆盖地板属性均值 | 岩浆上满效、沙地完全停工；可悬浮建在液面 |
| 高级消耗匹配 | `itemRadioactive: {}`、`itemExplode: {}` | 匹配任意带放射性/爆炸性物品 | 吃任意钍类/爆炸物 |

### 5.5 炮塔（0x05）`content/blocks/turrets/`

| 键 | 示例 | 作用 | 游戏内表现 |
|---|---|---|---|
| `type` | `ItemTurret` / `PowerTurret` | 弹药型/电力型 | 供弹机制差异 |
| `reload` | `60` | 装填 tick | 射速（越小越快） |
| `range` | `240` | 射程（像素，8px=1格 → 240=30格） | 射程圈 |
| `recoil` | `3` | 后坐力像素 | 开火炮管后退动画 |
| `shootCone` | `15` | 射击锥角 | 目标进入正前方 15° 才开火 |
| `inaccuracy` | `2` | 散布角度 | 越大越偏靶 |
| `rotateSpeed` | `2.5` | 炮塔转速 | 转向快慢 |
| `shootSound` | `bang` | 射击音 | 开火音效 |
| `ammoTypes` | 物品id → BulletType | **ItemTurret** 弹药映射 | 不同弹药不同弹道 |
| `shootType` | BulletType 对象 | **PowerTurret** 直接定义子弹 | 无弹药消耗 |
| `consumes.power` | `2.0` | PowerTurret 每 tick 耗电 | 电力条 |

**BulletType 常用键**（`BasicBulletType`）：`damage`、`speed`、`lifetime`（⚠️ **speed × lifetime = range**，否则子弹飞不到或飞过头）、`width`、`height`、`pierce`、`pierceCap`、`frontColor`、`backColor`、`ammoMultiplier`（1 铜 = 2 发）、`shootEffect`、`smokeEffect`、`hitEffect`、`status`、`statusDuration`。`LaserBulletType`：`damage`、`length`、`colors: [7595D266, 7595D2, ffffff]`。

**DrawTurret 部件**：`basePrefix`（默认 `block-`，可改 `reinforced-`）、`parts[]` 的 `RegionPart`：`suffix: -barrel`、`mirror`（双管对称）、`under`、`progress`（`recoil`/`warmup`/`charge`/`heat`）、`moveY: -2.0`。

### 5.6 StatusEffect 状态效果（0x06）`content/status/`

| 键 | 示例 | 作用 | 游戏内表现 |
|---|---|---|---|
| `color` | `a2a2a2` | 状态颜色 | 单位身上染色 + 界面气泡 |
| `armorMultiplier` | `0.5` | 护甲乘数（1.0 正常） | 破甲 |
| `speedMultiplier` | `0.8` | 速度乘数 | 减速 |
| `damage` | `2.0` | **每秒**持续伤害 | 掉血 |
| `permanent` | `false` | 是否永久（通常 false） | 时长改由子弹 `statusDuration` 控制（tick，180=3秒） |
| `effect` / `effectChance` | `formSmoke` / `0.05` | 附带粒子及每 tick 触发概率 | 冒烟粒子 |

引用：子弹里写 `status: my-first-mod-armor-shatter`。

### 5.7 特效与音效（0x06）

- `MultiEffect.effects[]`：叠加多个特效。
- `ParticleEffect`：`particles`、`sizeFrom/sizeTo`、`length`、`lifetime`、`colorFrom/colorTo`、`interp`（如 `pow5Out` 先快后慢）、`sizeInterp`。
- `WaveEffect`：`sizeFrom/sizeTo`（扩散半径）、`strokeFrom/strokeTo`（圆环粗细）、`lifetime`、`colorFrom/colorTo`。
- 音效：`.ogg` 真转码 → 放 `sounds/` → 引用不带后缀。

### 5.8 UnitType 单位（0x07）`content/units/`

| 键 | 示例 | 作用 | 游戏内表现 |
|---|---|---|---|
| `type` | `flying`/`hovering`/`legs`/`treads` | 运动形态 | 飞行/悬浮/蜘蛛腿/履带 |
| `flying` | `true` | 是否飞行 | 空中单位 |
| `speed` / `accel` / `drag` | `3.5`/`0.1`/`0.05` | 速度/加速度/阻力 | 机动手感 |
| `rotateSpeed` | `5.0` | 转向速度 | 转身快慢 |
| `health` / `armor` | `450`/`2` | 血量/护甲 | 生存性 |
| `hitSize` | `12` | 碰撞箱 | 被命中难度 |
| `engineOffset` / `engineSize` | `6`/`2.5` | 引擎火焰位置/大小 | 尾焰视觉 |
| `deathExplosionEffect` | WaveEffect 等 | 死亡特效 | 爆炸表现 |
| `weapons[]` | 见下 | 武器挂载 | 开火 |

**Weapon 键**：`name`（武器贴图名）、`x`/`y`（挂载偏移，**x≠0 时自动镜像出对称武器**，`mirror: false` 关闭）、`reload`、`shootSound`、`rotate`/`rotateSpeed`（独立瞄准）、`bullet`（子弹定义与炮塔一致）。

**单位工厂**（`content/blocks/units/`）：`UnitFactory.plans[]` = `{unit: 内部名, time: 600, requirements: [silicon/30 lead/20]}` → 生产菜单选项与进度条；`Reconstructor.upgrades: [[dagger, mace], [flare, horizon]]` → T2+ 升级路线。

### 5.9 环境方块 / Planet / Sector（0x08）

**环境方块**（`content/blocks/environment/`，贴图**必须**在 `sprites/blocks/environment/`）：

| 类型 | 键 | 表现 |
|---|---|---|
| `Floor` | `variants: 3`（对应 metal-floor1~3.png 随机拼接）、`speedMultiplier: 0.9`、`isLiquid`（true 时陆地单位淹死）、`itemDrop: scrap` | 地形外观/行军速度/海军通行/矿物掉落 |
| `StaticWall` | `variants: 2` | 不可跨越、挡视野的自然墙 |

**Planet**（`content/planets/`）：`localizedName`、`parent`（`sun`/`serpulo`）、`orbitRadius: 25`、`generator`（⚠️ 纯 JSON 只能用原版已有生成器如 `AsteroidGenerator`，这是 JSON 模组战役设计最大短板）、`atmosphereColor`/`atmosphereRadIn`/`atmosphereRadOut`（大气层视觉）、`hiddenItems`（可用起始物品）、`mesh: {type: NoiseMesh, seed, divisions, color1, color2, radius, octaves, persistence}`（外太空看的地貌）。

**Sector**（`content/sectors/`）：`planet`、`sector: 15`（星球区块编号 0~200+）、`difficulty: 1~5`、`description`、`research: groundZero`（解锁前置）；对应地图放 `maps/`（`.msav`，引用时不写后缀）。

### 5.10 research 科技树（通用）

- 糖写法：`research: copper-wall`（仅指定前置）。
- 对象写法：`research: { parent: plastanium, objectives: [{type: SectorComplete, preset: groundZero}] }`。
- 表现：战役研究面板中的节点位置与解锁条件。

---

## 六、单位与换算速查（UI 提示用）

| 量 | 换算 |
|---|---|
| 时间 | 60 tick = 1 秒（游戏固定 60fps） |
| 电力 | `consumes.power`/`powerProduction` 均为**每 tick** 值，×60 = 每秒 |
| 射程 | 8 像素 = 1 格（`range: 240` = 30 格） |
| 子弹 | `speed × lifetime` 应等于 `range` |
| 颜色 | 6 位 hex（无 #）；8 位时末两位为 Alpha |
| 状态时长 | `statusDuration` 单位 tick |

---

## 七、高频坑清单（可转成校验规则）

1. zip 根目录必须直接是 `mod.json`，否则 `No mod.json found`。
2. 中文冒号/全角标点/漏括号 → 解析报错主因。
3. 记事本保存引入 BOM → 游戏读不到文件。
4. 自定义内容引用漏写模组前缀（`outputItem`、`status`、`plans.unit`、`ammoTypes` 键）。
5. `speed × lifetime ≠ range` → 子弹打不到射程或飞过头。
6. 钻头 `tier` < 矿 `hardness` → 无法开采。
7. 环境方块贴图没放 `sprites/blocks/environment/` → 粉黑错误方块。
8. 普通贴图缺失 → 白色问号块；`DrawRegion`/`parts` 的 suffix 贴图需另画。
9. `itemCapacity` 太小 → 工厂装不下原料长期罢工。
10. 液体副产物排不出 → 默认整厂停工（需 `ignoreLiquidFullness: true` 才继续）。
11. `optional` 消耗不会提速；多输入配方中某原料断续会导致缓冲堆积反向卡死。
12. 音效必须真 .ogg；引用不带后缀。
13. 多产物用 `outputItems`（复数）；随机产出必须 `Separator`。
14. JSON 模组 `generator` 只能用原版生成器类。
15. `ThermalGenerator` 放无对应 `attribute` 的地板上完全停工。

---

## 八、对本项目（modmaker）的工程应用建议

### 8.1 ContentType 与教程章节对照

| 项目 ContentType | 目录 | 教程章节 | 备注 |
|---|---|---|---|
| `item` | items | 0x02 | 默认类 `Item` |
| `liquid` | liquids | 0x02 | 默认类 `Liquid` |
| `block` | blocks | 0x03/0x04/0x05/0x07/0x08 | 子类型最多，建议按子类型分模板 |
| `status` | statuses（兼容 status） | 0x06 | 教程用 `content/status/`，双目录兼容已实现 |
| `unit` | units | 0x07 | 默认类 `UnitType` |
| `weather` | weather | 教程未覆盖 | **必须显式写 type**（无默认类） |
| `sector` | sectors | 0x08 | 默认类 `SectorPreset` |
| `planet` | planets | 0x08 | 默认类 `Planet` |
| `team` | teams | 教程未覆盖 | 默认类 `TeamEntry` |
| `bullet` | —（仅内联） | 0x05/0x06 | 不能独立成文件 |
| `unitCommand`/`unitStance` | —（仅内联） | 教程未覆盖 | 不能独立成文件 |

### 8.2 用户友好化落点（按收益排序）

1. **字段中文化 + 单位提示**：每个字段输入框旁显示中文释义与单位（tick→秒、电力→/秒、射程→格），数据源即本笔记第五、六节。
2. **自动校验**（对应第七节坑清单）：`speed×lifetime≈range`、`tier≥hardness`、模组前缀缺失、半角标点、BOM 检测、zip 根目录结构、环境贴图路径、贴图同名缺失提醒。
3. **命名自动化**：内部名规范化（空格→连字符、大写转小写）；bundle 键 `[类型].[mod]-[文件].name/.description` 一键生成；贴图名与 drawer suffix 清单（-glow/-bottom/-weave/-piston/-liquid/-barrel）提示。
4. **默认值透出**：未填字段显示游戏默认值（如 `craftTime: 80`、`itemCapacity`、`reload` 等），减少「不填会怎样」的困惑；默认值以源码为准（见 8.3）。
5. **内联类型引导**：`bullet`/`unitCommand`/`unitStance` 禁止「新建独立文件」，改为在宿主文件（炮塔/单位）内提供内联编辑入口。
6. **research 下拉**：`parent` 候选列出原版内部名 + 当前模组内容；`objectives` 提供 `SectorComplete` 模板。
7. **weather/team 强制选 type**：二者无默认类，新建时必须先选具体类型。
8. **子类型模板库**：为 block 提供 GenericCrafter / Drill / ConsumeGenerator / ThermalGenerator / ItemTurret / PowerTurret / UnitFactory / Reconstructor / Floor / StaticWall 模板（含最小可用键集），对应教程示例可直接作为种子数据。

### 8.3 字段扩展方法论（0x09 蒸馏）

- JSON 只是数据单：`type` → 找 Java 类 → 类的 **public 字段**都能写进 JSON（如 `ItemTurret.maxAmmo: 50`）。
- 继承链决定可用字段：`ItemTurret → Turret → Block`，`health`/`size` 在 `Block.java`。
- 源码索引：`core/src/mindustry/world/blocks/`（方块）、`type/`（Item/Liquid/UnitType/BulletType）、`content/Items.java`、`content/Blocks.java`（原版内部名）；中文名反查内部名用 `core/assets/bundles/bundle_zh_CN.properties`。
- 建议：本项目 SchemaRegistry 可定期从源码字段表同步，保持字段库与游戏版本对齐。

---

## 来源

- [0x01 工具配置、模组结构与基本语法](https://learn-mindustry-mod.github.io/json/json_1_gemini/x01-basic.html)
- [0x02 物品、流体、语言文件与科技树](https://learn-mindustry-mod.github.io/json/json_1_gemini/x02-item-and-liquid.html)
- [0x03 工厂、方块通性与绘制器](https://learn-mindustry-mod.github.io/json/json_1_gemini/x03-crafter-and-drawer.html)
- [0x04 生产机制与电力网络](https://learn-mindustry-mod.github.io/json/json_1_gemini/x04-production-and-power.html)
- [0x05 炮塔、弹药与 DrawPart](https://learn-mindustry-mod.github.io/json/json_1_gemini/x05-turret-and-drawpart.html)
- [0x06 状态效果、特效、音效](https://learn-mindustry-mod.github.io/json/json_1_gemini/x06-status-effect-and-fx.html)
- [0x07 单位、武器与单位工厂](https://learn-mindustry-mod.github.io/json/json_1_gemini/x07-unit-and-weapon.html)
- [0x08 行星、区块、生成器与环境方块](https://learn-mindustry-mod.github.io/json/json_1_gemini/x08-planet-and-sector.html)
- [0x09 如何找到自己需要的类和字段](https://learn-mindustry-mod.github.io/json/json_1_gemini/x09-how-to-find-class-and-field.html)
