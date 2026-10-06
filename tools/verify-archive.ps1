# =============================================================================
#  归档完整性验证：用参考实现 7zr.exe 解压备份，并与原始目录逐文件比对
#  （同时比对目录结构，可发现空目录丢失等问题）
#  用法： pwsh -File tools\verify-archive.ps1 -Archive <7z> -WorldDir <dir> -SevenZip <7zr.exe>
# =============================================================================
param(
    [Parameter(Mandatory = $true)][string]$Archive,
    [Parameter(Mandatory = $true)][string]$WorldDir,
    [Parameter(Mandatory = $true)][string]$SevenZip,
    [string]$ExtractDir = '',
    [string[]]$Exclude = @('session.lock')
)

$ErrorActionPreference = 'Stop'
if (-not $ExtractDir) {
    $base = Join-Path (Split-Path $PSScriptRoot -Parent) 'build\verify'
    New-Item -ItemType Directory -Force -Path $base | Out-Null
    $ExtractDir = Join-Path $base ([guid]::NewGuid().ToString('N'))
}
New-Item -ItemType Directory -Force -Path $ExtractDir | Out-Null

Write-Host "==> 解压 $Archive" -ForegroundColor Cyan
& $SevenZip x $Archive "-o$ExtractDir" -y | Out-Null
if ($LASTEXITCODE -ne 0) { throw "解压失败（退出码 $LASTEXITCODE）" }

$worldName = Split-Path $WorldDir -Leaf
$root = Join-Path $ExtractDir $worldName
if (-not (Test-Path $root)) { throw "归档中找不到目录 $worldName" }

$srcFiles = Get-ChildItem $WorldDir -Recurse -File | Where-Object { $Exclude -notcontains $_.Name }
$dstFiles = Get-ChildItem $root -Recurse -File | Where-Object { $Exclude -notcontains $_.Name }
$srcDirs = Get-ChildItem $WorldDir -Recurse -Directory
$dstDirs = Get-ChildItem $root -Recurse -Directory

$srcMap = @{}
foreach ($f in $srcFiles) { $srcMap[$f.FullName.Substring($WorldDir.Length).TrimStart('\')] = $f }
$dstMap = @{}
foreach ($f in $dstFiles) { $dstMap[$f.FullName.Substring($root.Length).TrimStart('\')] = $f }
$dstDirSet = @{}
foreach ($d in $dstDirs) { $dstDirSet[$d.FullName.Substring($root.Length).TrimStart('\')] = $true }

$missing = @(); $sizeMismatch = @(); $hashMismatch = @()
foreach ($key in $srcMap.Keys) {
    if (-not $dstMap.ContainsKey($key)) { $missing += $key; continue }
    if ($srcMap[$key].Length -ne $dstMap[$key].Length) { $sizeMismatch += $key; continue }
    if ((Get-FileHash $srcMap[$key].FullName -Algorithm SHA256).Hash -ne (Get-FileHash $dstMap[$key].FullName -Algorithm SHA256).Hash) {
        $hashMismatch += $key
    }
}
$extra = @($dstMap.Keys | Where-Object { -not $srcMap.ContainsKey($_) })
$missingDirs = @()
foreach ($d in $srcDirs) {
    $rel = $d.FullName.Substring($WorldDir.Length).TrimStart('\')
    if (-not $dstDirSet.ContainsKey($rel)) { $missingDirs += $rel }
}

$srcTotal = ($srcFiles | Measure-Object -Property Length -Sum).Sum
$dstTotal = ($dstFiles | Measure-Object -Property Length -Sum).Sum

Write-Host ("原始: {0} 文件 / {1} 目录, {2:N2} MB" -f $srcFiles.Count, $srcDirs.Count, ($srcTotal / 1MB))
Write-Host ("归档: {0} 文件 / {1} 目录, {2:N2} MB" -f $dstFiles.Count, $dstDirs.Count, ($dstTotal / 1MB))
Write-Host ("缺失文件: {0}  大小不符: {1}  内容不符: {2}  多余文件: {3}  缺失目录: {4}" -f `
    $missing.Count, $sizeMismatch.Count, $hashMismatch.Count, $extra.Count, $missingDirs.Count)
if ($missing.Count -gt 0) { Write-Host ("缺失示例: " + (($missing | Select-Object -First 5) -join ', ')) -ForegroundColor Red }
if ($hashMismatch.Count -gt 0) { Write-Host ("内容不符示例: " + (($hashMismatch | Select-Object -First 5) -join ', ')) -ForegroundColor Red }
if ($missingDirs.Count -gt 0) { Write-Host ("缺失目录示例: " + (($missingDirs | Select-Object -First 5) -join ', ')) -ForegroundColor Red }

$ok = ($missing.Count -eq 0 -and $sizeMismatch.Count -eq 0 -and $hashMismatch.Count -eq 0 `
       -and $extra.Count -eq 0 -and $missingDirs.Count -eq 0)
if ($ok) {
    Write-Host "结果: 归档与原始目录完全一致 ✔" -ForegroundColor Green
} else {
    Write-Host "结果: 归档存在差异 ✘" -ForegroundColor Red
}
Remove-Item $ExtractDir -Recurse -Force -ErrorAction SilentlyContinue
if (-not $ok) { exit 1 }
