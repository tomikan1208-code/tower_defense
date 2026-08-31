$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

# MazewardServer は 0.0.0.0:25565 で待ち受ける。ここは表示とファイアウォール用の定数。
$port = 25565
$ruleName = "MAZEWARD Minecraft ($port)"

Write-Host ""
Write-Host "=== MAZEWARD 公開サーバー起動 ===" -ForegroundColor Cyan
Write-Host ""

# --- 1. Windows ファイアウォールの受信許可 ---------------------------------
# 既に同名のルールがあれば何もしない。無ければ管理者権限の PowerShell を
# 一瞬だけ起動してルールを追加する（サーバー本体は管理者権限では動かさない）。
$rule = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
if (-not $rule) {
    Write-Host "[1/3] ファイアウォールに TCP $port の受信許可を追加します（UAC の確認が出ます）..."
    $addRule = "New-NetFirewallRule -DisplayName '$ruleName' -Direction Inbound -Action Allow -Protocol TCP -LocalPort $port -Profile Any | Out-Null"
    try {
        Start-Process -FilePath "powershell.exe" -Verb RunAs -Wait -ArgumentList @(
            "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $addRule
        )
    } catch {
        Write-Host "  UAC がキャンセルされました。ファイアウォールは変更していません。" -ForegroundColor Yellow
    }
    $rule = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
}
if ($rule) {
    Write-Host "[1/3] ファイアウォール: 許可済み ($ruleName)" -ForegroundColor Green
} else {
    Write-Host "[1/3] ファイアウォール: 未設定。外部から繋がらない場合は管理者 PowerShell で次を実行してください:" -ForegroundColor Yellow
    Write-Host "      New-NetFirewallRule -DisplayName '$ruleName' -Direction Inbound -Action Allow -Protocol TCP -LocalPort $port -Profile Any"
}

# --- 2. アドレスの確認 -----------------------------------------------------
$lanIp = $null
try {
    $lanIp = (Get-NetIPConfiguration |
        Where-Object { $_.IPv4DefaultGateway -ne $null -and $_.NetAdapter.Status -eq "Up" } |
        Select-Object -First 1).IPv4Address.IPAddress
} catch {}

# グローバル IP は外部サービスに問い合わせて取得する（送信するのは問い合わせのみ）。
$globalIp = $null
try {
    $globalIp = (Invoke-RestMethod -Uri "https://api.ipify.org" -TimeoutSec 5).Trim()
} catch {}

Write-Host ""
Write-Host "[2/3] 接続先アドレス" -ForegroundColor Green
Write-Host "      このPCから      : localhost"
if ($lanIp)    { Write-Host "      同じLAN（家の中）: ${lanIp}:$port" }
if ($globalIp) { Write-Host "      外部（インターネット）: ${globalIp}:$port" }
else           { Write-Host "      外部（インターネット）: グローバルIPを取得できませんでした" -ForegroundColor Yellow }

Write-Host ""
Write-Host "      外部の人が入れるようにするには、ルーターのポート開放が必要です:" -ForegroundColor Yellow
Write-Host "        ・ルーター管理画面 → ポート変換 / ポートフォワーディング"
if ($lanIp) {
    Write-Host "        ・プロトコル TCP / 外部ポート $port / 転送先 ${lanIp}:$port"
} else {
    Write-Host "        ・プロトコル TCP / 外部ポート $port / 転送先 このPCのLAN IP:$port"
}
Write-Host "        ・ポート開放できない回線（モバイル回線等）なら playit.gg などのトンネルを使う"
Write-Host ""
Write-Host "      注意: このサーバーは認証なし（オフラインモード）です。" -ForegroundColor Yellow
Write-Host "            アドレスを知っている人は誰でも任意の名前で入れます。信頼できる相手にだけ共有してください。" -ForegroundColor Yellow

# --- 3. サーバー起動 -------------------------------------------------------
Write-Host ""
Write-Host "[3/3] サーバーを起動します（停止は Ctrl+C）..." -ForegroundColor Green
Write-Host ""
& (Join-Path $projectRoot "start-server.ps1")
