# Mindustry ModMaker

A visual mod authoring tool for Mindustry: import an existing mod package → edit on a canvas → export a mod the game can load directly. Files you do not touch are preserved byte-for-byte.

## Requirements

- JDK 25 (the repo ships its own `JDK25/` directory; `gradle.properties` pins it with the relative path `org.gradle.java.home=JDK25`, so moving the project to another disk or machine needs no config change. `JDK25/` is not in git — drop it in after cloning.)
- Launching `gradlew.bat` for the first time still needs a JVM on `JAVA_HOME` or PATH to bootstrap the process; after that every build and compile uses the bundled JDK25.
- All other dependencies are downloaded automatically by the Gradle Wrapper (`gradle/wrapper/`).

## Building and testing

```bat
:: Windows
gradlew.bat --offline test

:: macOS / Linux
./gradlew test
```

Tests need a ≥2g heap (`build.gradle.kts` already sets `maxHeapSize = "2g"`). Current full run: 491 cases / 57 classes, 0 failures, 28 skipped (skipped when the real-mod fixture is absent).

## Running the GUI

- From the source tree: `gradlew.bat run` (the project root no longer ships a `run.bat`).
- Frozen package: `Mindustry-modmaker-build\run.bat` launches a pre-built install layout under `Mindustry-modmaker-build\build\install\` without rebuilding; refresh it from `gradlew.bat installDist` when the source changes.
- Portable distribution: run `make-portable.bat` to rebuild `dist\ModMaker\` (jar + jlink runtime), then start it with `runtime\bin\java.exe -cp app\* dev.modmaker.Launcher` from inside `dist\ModMaker\`.

## Command line (headless)

```bat
:: Validate a project
gradlew.bat run --args="validate <project> [--json <report>] [--strict] [--profile <ver>]"

:: Build a project to a folder or zip
gradlew.bat run --args="build <project> --out <dir|zip> [--manifest <file>] [--strict] [--profile <ver>]"
```

Exit codes: `0` success, `2` validation failed, `3` build failed, `5` bad arguments or unreadable project.

## Portable package

After changing the source, on a machine with a JDK run `make-portable.bat` to rebuild `dist/ModMaker` (jar + jlink runtime).

## Project structure

- `src/main/java` — source: `core/` JavaFX-free core logic, `cli/` headless command line, `ui/` JavaFX UI
- `schemas/` — field schemas generated from the Mindustry source (regenerate with `gradlew schemaBootstrap`)
- `markdown/` — notes and design docs (`markdown/首页.md` is the entry point)

Note
