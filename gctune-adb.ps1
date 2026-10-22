# gctune-adb — 通过 ADB 桥向 GC Tune 发送命令，并打印执行结果。
#
#   gctune-adb "id"
#   gctune-adb "getprop ro.product.model"
#   gctune-adb "dumpsys deviceidle whitelist" -Wait 40
#
# 前置条件：应用内已打开「ADB 命令」（位于「其他」页面），
# GC Tune 处于打开状态，设备已通过 adb 连接。
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Command,

    # 等待通道响应的秒数（通道本身很快，但电竞模式可能要手动
    # 拉开游戏魔盒的下拉面板才会响应）
    [int]$Wait = 30,

    [string]$Uri = 'content://com.poc.gctune.bridge'
)

$ErrorActionPreference = 'Stop'

# 1) 发送：exec 返回 ack（或即时错误：disabled/no_game/busy）。
#    命令需要对设备侧 sh 做转义，否则命令里的空格会破坏
#    --arg 的解析（adb shell 会把参数拼成一行且不加引号）。
$escCmd = $Command -replace "'", "'\''"
$escUri = $Uri -replace "'", "'\''"
$sent = adb shell "content call --uri '$escUri' --method exec --arg '$escCmd' --extra wait:i:0" 2>&1 | Out-String

$status = if ($sent -match 'status=(\w+)') { $Matches[1] } else { '?' }
$ack = if ($sent -match 'ack=(-?\d+)') { [long]$Matches[1] } else { -1 }
$msg = if ($sent -match 'msg=([^,}\]]+)') { $Matches[1].Trim() } else { '' }

if ($status -notin @('pending', 'ok')) {
    Write-Output "[$status] $msg"
    if ($sent -match 'out=([\s\S]*?)\s*\}\]\s*$') { Write-Output $Matches[1] }
    exit 1
}
if ($ack -le 0 -and $status -ne 'ok') {
    Write-Output "[$status] $msg"
    exit 1
}

# 2) 等待：轮询 Settings.Secure.gc_tune_adb_out 这面镜像
#    行格式：ack|STATUS|rc|base64(utf8)
$deadline = (Get-Date).AddSeconds($Wait)
$raw = $null
do {
    $line = (adb shell settings get secure gc_tune_adb_out) 2>$null
    if ($line) {
        $line = ($line | Out-String).Trim() -replace "`r|`n", ''
        if ($line -and $line -ne 'null') {
            $f = $line.Split('|', 4)
            if ($f.Count -eq 4) {
                $lineAck = 0
                [void][long]::TryParse($f[0], [ref]$lineAck)
                if (($ack -le 0 -or $lineAck -eq $ack) -and $f[1] -ne 'PENDING') {
                    $raw = $f
                    break
                }
            }
        }
    }
    Start-Sleep -Milliseconds 800
} while ((Get-Date) -lt $deadline)

if (-not $raw) {
    Write-Output "[超时] 等待 $Wait 秒仍未收到响应。"
    Write-Output "通道正在等待电竞模式：请把 GC Tune 加入游戏列表，并拉开游戏魔盒下拉面板。"
    exit 2
}

# 3) 解码
$state = $raw[1]
$rc = $raw[2]
$text = ''
if ($raw[3]) {
    try {
        $bytes = [Convert]::FromBase64String($raw[3])
        $text = [Text.Encoding]::UTF8.GetString($bytes)
    } catch {
        $text = $raw[3]
    }
}

Write-Output "[$state rc=$rc]"
if ($text) { Write-Output $text }
if ($state -ne 'OK') { exit 1 }
