# Mindustry ModMaker

Mindustry 模组可视化制作工具：导入已有模组包 → 画布编辑 → 导出可被游戏直接加载的模组。未改动的文件字节级原样保留。

## 环境要求

- JDK 25（工程目录内自带 `JDK25/`，`gradle.properties` 用相对路径 `org.gradle.java.home=JDK25` 钉住，项目整体挪盘或换机器都不必改；`JDK25/` 不进 git，克隆后需自行放入）
- 首次启动 `gradlew.bat` 本身仍需 `JAVA_HOME` 或 PATH 上任意一个 JVM 来拉起进程；之后构建与编译一律用工程内 JDK25
- 其余依赖由 Gradle Wrapper 自动下载（`gradle/wrapper/`）

## 构建与测试

```bat
:: Windows
gradlew.bat --offline test

:: macOS / Linux
./gradlew test
```

测试需要 ≥2g 堆（`build.gradle.kts` 已配置 `maxHeapSize = "2g"`）。当前全量：491 用例 / 57 类，0 失败、28 跳过（真实模组夹具缺省时跳过）。

## 运行图形界面

- 双击 `run.bat`：有 JVM 时 `gradlew run` 跑当前源码；无 JVM 时回退 `dist/ModMaker` 便携包。
- 或直接执行 `gradlew.bat run`。

## 命令行（无头）

```bat
:: 校验一个项目
gradlew.bat run --args="validate <project> [--json <report>] [--strict] [--profile <ver>]"

:: 构建一个项目到文件夹或 zip
gradlew.bat run --args="build <project> --out <dir|zip> [--manifest <file>] [--strict] [--profile <ver>]"
```

退出码：`0` 成功，`2` 校验失败，`3` 构建失败，`5` 参数错误或项目不可读。

## 便携包

改完源码后，在有 JDK 的机器上运行 `make-portable.bat` 重建 `dist/ModMaker`（jar + jlink runtime）。

## 目录结构

- `src/main/java` — 源码：`core/` 零 JavaFX 核心逻辑、`cli/` 无头命令行、`ui/` JavaFX 界面
- `schemas/` — 从 Mindustry 源码生成的字段 schema（`gradlew schemaBootstrap` 重生成）
- `markdown/` — 笔记与设计文档（`markdown/首页.md` 是入口）
