@echo off
setlocal
rem d
cd /d "%~dp0"

set "DIST=dist\ModMaker"
set "APP=%DIST%\app"
set "JAR=mindustry-modmaker-0.1.0.jar"
set "JAVA=%DIST%\runtime\bin\java.exe"

if not exist "gradlew.bat" (
    echo [make-portable] gradlew.bat not found - run this from the project root.
    goto :fail
)

echo [make-portable] Building the application jar from the current source...
call gradlew.bat jar --console=plain
if errorlevel 1 (
    echo [make-portable] The build failed, %DIST% was left as it was.
    goto :fail
)

if not exist "build\libs\%JAR%" (
    echo [make-portable] build\libs\%JAR% is missing after the build.
    goto :fail
)

if not exist "%APP%" mkdir "%APP%"
copy /y "build\libs\%JAR%" "%APP%\%JAR%" >nul
if errorlevel 1 (
    echo [make-portable] Could not copy the jar into %APP%.
    goto :fail
)
echo [make-portable] Replaced %APP%\%JAR%

if not exist "%JAVA%" (
    echo [make-portable] The jlink runtime is missing, regenerating it...
    if "%JAVA_HOME%"=="" (
        echo [make-portable] JAVA_HOME is not set, so jlink cannot run. Point it at a JDK 25 and retry.
        goto :fail
    )
    if not exist "%JAVA_HOME%\bin\jlink.exe" (
        echo [make-portable] %JAVA_HOME%\bin\jlink.exe not found. Point JAVA_HOME at a JDK 25 and retry.
        goto :fail
    )
    "%JAVA_HOME%\bin\jlink.exe" --add-modules java.se,jdk.unsupported --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output "%DIST%\runtime"
    if errorlevel 1 (
        echo [make-portable] jlink failed, %DIST%\runtime was not written.
        goto :fail
    )
    echo [make-portable] Runtime regenerated at %DIST%\runtime
    echo [make-portable] Note: %APP% also needs gson and the javafx jars; copy them from the Gradle cache.
)

echo [make-portable] Verifying that the package still renders...
"%JAVA%" -cp "%APP%\*" dev.modmaker.Launcher --snapshot=snapshots\portable-check.png
if errorlevel 1 (
    echo [make-portable] The jar was replaced, but the package did not start; see the messages above.
    goto :fail
)
if exist "snapshots\portable-check.png" del "snapshots\portable-check.png"

echo [make-portable] Done. Launch with run.bat or %DIST%.
pause
exit /b 0

:fail
pause
exit /b 1
