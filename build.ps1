# =============================================================================
#  ZHM world backup helper 构建脚本
#  使用 JDK 自带的 javac / jar 直接编译打包（无需安装 Maven 或 Gradle）
#  用法：  pwsh -File build.ps1
#          pwsh -File build.ps1 -Version 1.0.1
#  依赖：  JDK 25+（Paper 26.2 需要 Java 25）
# =============================================================================
param(
    [string]$Version = '1.0.1',
    [string]$ApiVersion = '26.2.build.130-stable'
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$libs = Join-Path $root 'libs'
$buildDir = Join-Path $root 'build'
$classesDir = Join-Path $buildDir 'classes'
$distDir = Join-Path $root 'dist'

function Write-Step($text) { Write-Host "==> $text" -ForegroundColor Cyan }

# ---------------------------------------------------------------- 依赖准备
New-Item -ItemType Directory -Force -Path $libs, $classesDir, $distDir | Out-Null

$paperJar = Join-Path $libs "paper-api-$ApiVersion.jar"
if (-not (Test-Path $paperJar)) {
    Write-Step "下载 paper-api $ApiVersion"
    $url = "https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/$ApiVersion/paper-api-$ApiVersion.jar"
    Invoke-WebRequest -UseBasicParsing $url -OutFile $paperJar -TimeoutSec 600
}

$mavenCentral = 'https://repo.papermc.io/repository/maven-public'
$dependencies = @(
    @{ path = 'net/kyori/adventure-api/5.2.0/adventure-api-5.2.0.jar'; name = 'adventure-api-5.2.0.jar'; compile = $true },
    @{ path = 'net/kyori/adventure-key/5.2.0/adventure-key-5.2.0.jar'; name = 'adventure-key-5.2.0.jar'; compile = $true },
    @{ path = 'net/kyori/adventure-text-minimessage/5.2.0/adventure-text-minimessage-5.2.0.jar'; name = 'adventure-text-minimessage-5.2.0.jar'; compile = $true },
    @{ path = 'org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar'; name = 'jspecify-1.0.0.jar'; compile = $true },
    @{ path = 'net/md-5/bungeecord-chat/1.21-R0.2-deprecated%2Bbuild.21/bungeecord-chat-1.21-R0.2-deprecated%2Bbuild.21.jar'; name = 'bungeecord-chat-1.21-R0.2-deprecated+build.21.jar'; compile = $true },
    @{ path = 'com/google/code/gson/gson/2.14.0/gson-2.14.0.jar'; name = 'gson-2.14.0.jar'; compile = $true },
    @{ path = 'org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar'; name = 'snakeyaml-2.2.jar'; compile = $true },
    @{ path = 'org/apache/commons/commons-compress/1.28.0/commons-compress-1.28.0.jar'; name = 'commons-compress-1.28.0.jar'; compile = $true; shade = $true },
    @{ path = 'org/tukaani/xz/1.10/xz-1.10.jar'; name = 'xz-1.10.jar'; compile = $true; shade = $true }
)

foreach ($dep in $dependencies) {
    $target = Join-Path $libs $dep.name
    if (-not (Test-Path $target)) {
        Write-Step "下载 $($dep.name)"
        Invoke-WebRequest -UseBasicParsing "$mavenCentral/$($dep.path)" -OutFile $target -TimeoutSec 600
    }
}

# ---------------------------------------------------------------- 编译
Write-Step '清理旧的编译输出'
if (Test-Path $classesDir) { Remove-Item -Recurse -Force $classesDir }
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null

$classpath = @($paperJar) + ($dependencies | Where-Object { $_.compile } | ForEach-Object { Join-Path $libs $_.name })
$classpathString = $classpath -join [IO.Path]::PathSeparator

$sources = Get-ChildItem -Path (Join-Path $root 'src\main\java') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if ($sources.Count -eq 0) { throw '未找到任何 Java 源文件' }

$argFile = Join-Path $buildDir 'sources.txt'
[System.IO.File]::WriteAllLines($argFile, [string[]]$sources, (New-Object System.Text.UTF8Encoding($false)))

Write-Step "编译 $($sources.Count) 个源文件 (release 25)"
& javac -encoding UTF-8 --release 25 -Xlint:all,-classfile -cp $classpathString -d $classesDir "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "编译失败（javac 退出码 $LASTEXITCODE）" }

# ---------------------------------------------------------------- 资源 + 内置依赖
Write-Step '复制资源文件'
Copy-Item -Path (Join-Path $root 'src\main\resources\*') -Destination $classesDir -Recurse -Force

Write-Step '展开内置依赖（commons-compress / xz）'
$tempDir = Join-Path $buildDir 'shade'
if (Test-Path $tempDir) { Remove-Item -Recurse -Force $tempDir }
foreach ($dep in ($dependencies | Where-Object { $_.shade })) {
    $depJar = Join-Path $libs $dep.name
    $depTemp = Join-Path $tempDir $dep.name
    New-Item -ItemType Directory -Force -Path $depTemp | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($depJar, $depTemp)
    Get-ChildItem -Path $depTemp -Recurse -File | Where-Object {
        $_.Name -ne 'module-info.class' -and $_.FullName -notmatch '\\META-INF\\(MANIFEST\.MF|.*\.(SF|DSA|RSA)|LICENSE.*|NOTICE.*|maven\\.*)$'
    } | ForEach-Object {
        $relative = $_.FullName.Substring($depTemp.Length).TrimStart('\')
        $destination = Join-Path $classesDir $relative
        New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
        Copy-Item $_.FullName $destination -Force
    }
}

# ---------------------------------------------------------------- 打包
$jarPath = Join-Path $distDir "ZHM-world-backup-helper-$Version.jar"
if (Test-Path $jarPath) { Remove-Item -Force $jarPath }

Write-Step "打包 $jarPath"
& jar --create --file $jarPath -C $classesDir .
if ($LASTEXITCODE -ne 0) { throw "打包失败（jar 退出码 $LASTEXITCODE）" }

$size = [math]::Round((Get-Item $jarPath).Length / 1KB, 1)
Write-Host ""
Write-Host "构建完成：$jarPath ($size KB)" -ForegroundColor Green
Write-Host "把该 jar 放进服务器的 plugins/ 目录即可。" -ForegroundColor Green
