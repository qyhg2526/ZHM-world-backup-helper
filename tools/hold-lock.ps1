# =============================================================================
#  测试辅助：以独占方式占用一个文件，用于验证 7-Zip 遇到被占用文件时的行为
#  用法： pwsh -File tools\hold-lock.ps1 -Path <file> -Seconds 90
# =============================================================================
param(
    [Parameter(Mandatory = $true)][string]$Path,
    [int]$Seconds = 90
)
$dir = Split-Path $Path -Parent
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
$stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Create, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
$bytes = New-Object byte[] 65536
(New-Object System.Random).NextBytes($bytes)
$stream.Write($bytes, 0, $bytes.Length)
$stream.Flush()
Write-Output "已独占锁定 $Path ，持续 $Seconds 秒"
Start-Sleep -Seconds $Seconds
$stream.Close()
Write-Output "已释放 $Path"
