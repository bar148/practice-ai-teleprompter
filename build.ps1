# בונה את קובץ ההתקנה (Setup.exe) של גרסת הדסקטופ.
# הבנייה מתבצעת מחוץ ל-OneDrive (ב-LOCALAPPDATA) כדי לא לסנכרן מאות מגה של node_modules.
# שימוש:  pwsh -File build.ps1
$ErrorActionPreference = 'Stop'
$src = $PSScriptRoot
$work = Join-Path $env:LOCALAPPDATA 'VoiceTeleprompter\build'

New-Item -ItemType Directory -Force $work | Out-Null
foreach ($f in 'main.js', 'preload.js', 'bridge.html', 'package.json', 'icon.png', 'afterPack.js') {
    Copy-Item (Join-Path $src "desktop\$f") $work -Force
}
Copy-Item (Join-Path $src 'index.html') $work -Force
Copy-Item (Join-Path $src 'whisper_server.py') $work -Force

Push-Location $work
try {
    if (-not (Test-Path 'node_modules\electron-builder')) {
        npm install --save-dev --no-audit --no-fund electron@44.4.5 electron-builder@26.15.3
        if ($LASTEXITCODE) { throw 'npm install failed' }
    }
    npx electron-builder --win --publish never
    if ($LASTEXITCODE) { throw 'electron-builder failed' }
} finally { Pop-Location }

$out = Join-Path $src 'dist'
New-Item -ItemType Directory -Force $out | Out-Null
Get-ChildItem (Join-Path $work 'dist') -Filter '*.exe' | Copy-Item -Destination $out -Force
Get-ChildItem $out -Filter '*.exe' | Select-Object Name, @{ n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } }
