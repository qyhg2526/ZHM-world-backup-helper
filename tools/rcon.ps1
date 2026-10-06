# =============================================================================
#  极简 RCON 客户端：用于在自动化测试中向 Paper 服务器发送控制台命令
#  用法： pwsh -File tools\rcon.ps1 -Command "backup now" -Password dshbackup
# =============================================================================
param(
    [Parameter(Mandatory = $true)][string]$Command,
    [string]$Server = '127.0.0.1',
    [int]$Port = 25575,
    [string]$Password = 'dshbackup',
    [int]$TimeoutMs = 30000
)

function Send-Packet {
    param($Stream, [int]$Id, [int]$Type, [string]$Body)
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
    $length = 4 + 4 + $bodyBytes.Length + 2
    $buffer = New-Object System.IO.MemoryStream
    $writer = New-Object System.IO.BinaryWriter($buffer)
    $writer.Write([int]$length)
    $writer.Write([int]$Id)
    $writer.Write([int]$Type)
    $writer.Write($bodyBytes)
    $writer.Write([byte]0)
    $writer.Write([byte]0)
    $writer.Flush()
    $bytes = $buffer.ToArray()
    $Stream.Write($bytes, 0, $bytes.Length)
    $Stream.Flush()
}

function Read-Packet {
    param($Stream)
    $header = New-Object byte[] 4
    $read = 0
    while ($read -lt 4) {
        $n = $Stream.Read($header, $read, 4 - $read)
        if ($n -le 0) { return $null }
        $read += $n
    }
    $length = [System.BitConverter]::ToInt32($header, 0)
    $payload = New-Object byte[] $length
    $read = 0
    while ($read -lt $length) {
        $n = $Stream.Read($payload, $read, $length - $read)
        if ($n -le 0) { return $null }
        $read += $n
    }
    $id = [System.BitConverter]::ToInt32($payload, 0)
    $type = [System.BitConverter]::ToInt32($payload, 4)
    $body = [System.Text.Encoding]::UTF8.GetString($payload, 8, $length - 10)
    return [pscustomobject]@{ Id = $id; Type = $type; Body = $body }
}

$client = New-Object System.Net.Sockets.TcpClient
$client.Connect($Server, $Port)
$client.ReceiveTimeout = 1500
$stream = $client.GetStream()

Send-Packet -Stream $stream -Id 1 -Type 3 -Body $Password
$auth = Read-Packet -Stream $stream
if ($null -eq $auth -or $auth.Id -eq -1) {
    Write-Output 'AUTH_FAILED'
    $client.Close()
    exit 1
}

Send-Packet -Stream $stream -Id 2 -Type 2 -Body $Command
$output = ''
while ($true) {
    try {
        $packet = Read-Packet -Stream $stream
    } catch {
        break   # 读取超时表示响应结束
    }
    if ($null -eq $packet) { break }
    if ($packet.Body.Length -gt 0) { $output += $packet.Body }
    if ($packet.Body.Length -eq 0) { break }
}
$client.Close()
Write-Output $output
