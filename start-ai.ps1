<#
    MAZEWARD 対戦 AI ブリッジ（ai/mc_brain.py）を手で立ち上げる。

    普段は Minecraft 側が「AI と対戦 / AI 観戦」を選んだ時点で自動起動するので、
    これを使うのは次の 3 つのとき。

      1. 起動が失敗する理由を、Minecraft のログに混ぜずに見たいとき
      2. 学習を回しながら別の世代を貸し出したいとき（--model / --port）
      3. 別のマシンから繋ぎたいとき（--host 0.0.0.0）

    例: start-ai.bat --model ppo_gen35.pt --greedy
#>

param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$BrainArgs
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location (Join-Path $projectRoot "ai")

# コンソールと Python の両方を UTF-8 に揃える。
# これが無いと、Python が日本語のログを 1 行出した瞬間に
# UnicodeEncodeError で落ちる（cp932 に無い記号があるため）。
chcp 65001 > $null
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new() } catch {}
$env:PYTHONIOENCODING = "utf-8"
$env:PYTHONUTF8 = "1"

# Python を探す。MAZEWARD_PYTHON があればそれを最優先。
# Windows には「ストアを開くだけ」の python.exe が PATH に居ることがあるので、
# 実際に import torch まで通るかを見てから採用する。
function Find-Python {
    $candidates = @()
    if ($env:MAZEWARD_PYTHON) { $candidates += $env:MAZEWARD_PYTHON }
    $candidates += "python"
    $candidates += "py"
    foreach ($candidate in $candidates) {
        try {
            $version = & $candidate -c "import sys; print(sys.version.split()[0])" 2>$null
            if ($LASTEXITCODE -eq 0 -and $version) { return $candidate }
        } catch {
            continue
        }
    }
    return $null
}

$python = Find-Python
if (-not $python) {
    Write-Host "python が見つかりません。" -ForegroundColor Red
    Write-Host "  - python が PATH にあるか確認してください（where python）"
    Write-Host "  - 別の環境を使うなら MAZEWARD_PYTHON に実行ファイルのパスを入れてください"
    exit 1
}

& $python -c "import torch" 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "$python に torch が入っていません。" -ForegroundColor Red
    Write-Host "  pip install -r requirements.txt"
    exit 1
}

Write-Host "MAZEWARD AI ブリッジを起動します（$python mc_brain.py $BrainArgs）" -ForegroundColor Cyan
Write-Host "Minecraft のロビーで「AI と対戦」「AI 観戦」を選ぶと繋がります。終了は Ctrl+C"
Write-Host ""

& $python mc_brain.py @BrainArgs

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "ブリッジが異常終了しました (exit $LASTEXITCODE)" -ForegroundColor Red
    Write-Host "  上のエラーを確認してください。モデルが見つからない場合は ai/models/ を確認"
}
