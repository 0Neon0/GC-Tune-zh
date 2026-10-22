<#
.SYNOPSIS
    adbplus —— 通过 GC-Tune 守护进程通道在设备上执行 shell 命令。

.DESCRIPTION
    完整往返链路，无需 root：
      1. 构造与 GC-Tune 应用完全一致的内容体（base64 输出 + __RC__ 标记，
         以 "N|base64" 形式暂存在 Settings.System gc_tune_ack，并附带 ACK 广播）；
      2. 通过 adb 把内容写入 Settings.Secure gc_tune_cmd（shell 用户默认拥有
         WRITE_SECURE_SETTINGS 权限）；
      3. 用一行日志唤醒 uid1000 守护进程：log -t GCTune WAKE <ack>；
      4. 轮询 Settings.System gc_tune_ack，直到 ack 编号与我们的一致；
      5. 解码 base64 内容，剥掉 __RC__ 尾部标记，打印 stdout+stderr，
         并以远端命令的退出码退出。

    守护进程必须已在运行（启动一次 GC-Tune 应用，或它已由 DAEMON_BOOT 拉起）。
    仅需 PATH 中有 adb，且设备已连接。

.EXAMPLE
    ./adbplus.ps1 id
    ./adbplus.ps1 getprop ro.product.model
    ./adbplus.ps1 -Timeout 30 "ls -la /data/system"
    ./adbplus.ps1 -Raw "echo 'a b' \"c\""

.NOTES
    退出码：远端命令的 rc（通常 0..255）；124 = 超时（无守护进程/无 ack）；
    1 = 用法或传输错误。
#>
[CmdletBinding()]
param(
    # 远端命令行。选项之后的所有内容，可作为单个参数传入，也可分多个词传入。
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$Command,

    # 轮询超时秒数（-Timeout 30）。
    [Alias('t')]
    [ValidateRange(1, 600)]
    [int]$TimeoutSec = 20,

    # 打印解码后的原始内容，不解析 __RC__。
    [switch]$Raw,

    # 把总耗时打印到 stderr（Verbose）。
    [switch]$Measure,

    [switch]$h
)

$ErrorActionPreference = 'Stop'

if ($Measure) { $sw = [Diagnostics.Stopwatch]::StartNew() }

if ($h -or -not $Command -or $Command.Count -eq 0) {
    Get-Help $MyInvocation.MyCommand.Path -Detailed
    exit 1
}

function Invoke-Adb {
    param([string[]]$AdbArgs)
    # adb 的进度信息走 stderr；在 PS 5.1 且 $ErrorActionPreference='Stop' 时
    # 会被当成终止性的 NativeCommandError。用 cmd /c 包一层并把 stderr 折回来，
    # 就能保持为普通的非终止消息。
    cmd /c "`"adb`" $($AdbArgs -join ' ') 2>&1"
}

# 通过 adb shell stdin 把 $Content 推送到设备 $Remote：完全不用引号/转义，
# 设备侧 $(cat file) 只会展开一次。
function Push-DeviceFile {
    param([string]$Content, [string]$Remote)
    $tmp = Join-Path $env:TEMP ('gplus_' + $PID + '_' + (Get-Random))
    [IO.File]::WriteAllText($tmp, $Content, (New-Object Text.UTF8Encoding($false)))
    try {
        $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($tmp))
        Invoke-Adb @('shell', "`"echo $b64 | base64 -d > $Remote`"") | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "推送到 $Remote 失败" }
    }
    finally { Remove-Item $tmp -ErrorAction SilentlyContinue }
}

$state = (Invoke-Adb @('get-state') | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $state -notmatch 'device') {
    Write-Error "未连接设备（adb get-state: $state）"
    exit 1
}

$remoteCmd = ($Command -join ' ')

# 唯一的 ack 编号，避开应用自身 1_000_001+ 的计数器区间。
$ack = 1500000000 + (Get-Random -Maximum 99999999)

# 与 Main.apply() 产出的内容体形状完全一致：输出 + __RC__ 标记，base64，
# 以 "<ack>|<b64>" 存入 gc_tune_ack，并作为旁路通道广播。
$body = 'b=$({ ' + $remoteCmd + '; echo __RC__$?; } 2>&1 | base64 -w 0 | head -c 8000);'
$body += 'b=${b:-Cg==};'
$body += 'se=$( { settings put system gc_tune_ack ' + $ack + '\|$b 2>&1; echo RC$?; } | base64 -w 0);'
$body += 'se=${se:-Cg==};'
$body += 'am broadcast -a com.poc.gctune.ACK --ei n ' + $ack + ' --es o $b --es e $se >/dev/null 2>&1'

# 通过推送文件来发布 —— 载荷零 shell 引号风险。
Push-DeviceFile -Content $body -Remote '/data/local/tmp/.gplus_body'

# 设备侧的 "$(cat file)" 只展开一次 —— 内容不会被再次解析。
Invoke-Adb @('shell', '"settings put secure gc_tune_cmd \"$(cat /data/local/tmp/.gplus_body)\""') | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Error 'settings put 失败（是否缺少 WRITE_SECURE_SETTINGS 权限？）'; exit 1 }

$check = (Invoke-Adb @('shell', '"settings get secure gc_tune_cmd"') | Out-String).Trim()
if ($check -ne $body) {
    $short = $check.Substring(0, [Math]::Min(80, $check.Length))
    Write-Error "gc_tune_cmd 未按原文存储（实际值：$short...）"
    exit 1
}

# 唤醒守护进程。守护进程内的 logcat -s GCTune:I 会匹配任意 *WAKE* 行。
Invoke-Adb @('shell', "`"log -t GCTune WAKE $ack`"") | Out-Null

# 轮询暂存结果。
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$val = $null
while ((Get-Date) -lt $deadline) {
    $v = (Invoke-Adb @('shell', '"settings get system gc_tune_ack"') | Out-String).Trim()
    if ($v -match "^$ack\|(\S+)$") { $val = $Matches[1]; break }
    Start-Sleep -Milliseconds 200
}
if ($null -eq $val) {
    Write-Error "超时 ${TimeoutSec}s：未收到 ack $ack —— gctw 守护进程是否在运行？（请先打开一次 GC-Tune 应用）"
    exit 124
}

$blob = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($val))

if ($Raw) {
    Write-Output $blob
    exit 0
}

$rc = 0
if ($blob -match '__RC__(-?\d+)\s*$') {
    $rc = [int]$Matches[1]
    $blob = $blob -replace '__RC__-?\d+\s*$', ''
}
if ($blob.Length -gt 0) { Write-Output $blob.TrimEnd("`r", "`n") }

Invoke-Adb @('shell', '"rm -f /data/local/tmp/.gplus_body"') | Out-Null
if ($Measure) { $el = [int]$sw.ElapsedMilliseconds; Write-Verbose "adbplus: 总耗时 ${el}ms（含唤醒到 ack 的轮询）" }
exit $rc
