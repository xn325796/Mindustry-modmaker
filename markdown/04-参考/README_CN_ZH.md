# Mindustry ModMaker

节点画布式的 Mindustry 模组构建器（Java + JavaFX 桌面应用）。

像白板一样把内容卡片摆出来、用连线表达引用与科技树；**能读入已有模组包继续编辑**，
**能输出可被游戏加载的完整模组**（未改动过的文件字节级原样保留）。

架构说明见 [[architectureCN_ZH]]（最新状态）
**接手开发/交付请先读 [[receiving_CN_ZH]]**——自包含的蒸馏文档：环境、架构、格式契约、验证协议、陷阱备忘录。英文版已归档至 `99-归档/英文版/`。

## 运行

**最简单的方式：双击 `run.bat`**（自动定位 JDK 并跑当前源码；若没有 JVM，则回退到 `dist\ModMaker` 便携包）。

首次启动使用仓库内已有的 `schemas/`（三个生成物已入库）；只有 schema 缺失或要从游戏源码重新扫描时，才需要手动跑 `gradlew schemaBootstrap`。

也可以带参数启动，直接看导入真实模组的效果：

```bat
run.bat --import=..\mod --projectDir=build\my-import
```

> 注：`--import` 指向任意真实模组文件夹或 `.zip`；上面的 `..\mod` 就是本工作区的 Exoprosopa，换成你手头模组包的实际路径即可。

命令行方式（等价）：

```bash
./gradlew run      # 启动应用（首次会联网下载 JavaFX）
./gradlew test     # 298 个测试：格式规则、容错解析、真机模组往返、稳定性校验（依赖真实模组夹具的金标用例在夹具缺失时自动跳过；交付前加 --rerun-tasks）
```

首次使用前生成 schema（扫 Mindustry 源码；启动脚本会自动做）：

```bash
./gradlew schemaBootstrap
```

- **新建项目** → 选一个目录，得到 `<目录>/<模组名>/`：里面就是完整的模组文件夹骨架。
- **导入模组包** → 选 `.zip` 或已有的模组文件夹，应用会把它整个解包成可编辑项目（内容、精灵、脚本、
  地图、bundle 全部保留），并在画布上按类型分列摆好、按引用关系连线。
- **编辑** → 点卡片，右侧是 schema 驱动的字段编辑器（数字/颜色/枚举/内容引用/堆叠/原始 JSON）；主区有六个工作区：资源管理/方块构筑/单位构筑/星球战役/科技研究/扩展脚本（脚本页暂为禁用占位）。字符串表/精灵/校验/构建日志面板仍在，但底部栏默认隐藏（`AppShell.BOTTOM_STRIP_VISIBLE`）。
- **构建** → 输出到文件夹或打成 `.zip`（单层包装目录，解压即 `mods/<名>/`）。
- **实机测试** → 把模组装进一个临时数据目录并启动 Mindustry（`MINDUSTRY_DATA_DIR`，
  不碰真实存档），退出后读游戏日志报告内容错误。

**免 Java 便携版**：`dist\ModMaker\`（`app` + `runtime` 两层，自带 jlink 精简 JRE：`java.se` + `jdk.unsupported`）。没有独立的启动 bat，`run.bat` 在找不到 JVM 时会自动用它。重新生成见根目录 `make-portable.bat`。

## 工程结构

```
src/main/java/dev/modmaker/
  core/json/      容错 HJSON 读取、规范 JSON 写出、JSON 路径
  core/fmt/       ContentType（9 类可文件化）、命名规则、精灵区域、bundle 编解码
  core/model/     ModProject / ContentRecord / Board / CanvasNode / Edge / StringTable / AssetRef
  core/schema/    SchemaRegistry（精编 + 源码扫描 + 原版内容名录）
  core/io/        读取模组包 → 建项目 → 存盘 → 构建/打包，自动布局，JSON Canvas
  core/validate/  校验器（重名/非法名/缺 type/悬空引用/缺精灵/孤儿节点）
  tools/          SchemaBootstrap（扫 Mindustry 源码生成 schema 与主体名录）
  ui/             AppShell、NodeCanvasPane、ContentCard、RecordInspector、StringsPane、IssuesPane、GameLauncher
src/test/java/    格式规则、容错解析、导入、往返、创作、稳定性、游戏启动、UI 冒烟
schemas/          schema.json（精编）+ fields.json / vanilla-content.json（生成物）
```
