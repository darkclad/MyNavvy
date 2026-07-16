<#
  Build the NOAA chart MBTiles in Docker, then optionally install the app and
  push the charts to a connected tablet.

  Usage:
    .\run_pipeline.ps1                # build charts -> .\out\charts.mbtiles
    .\run_pipeline.ps1 -Push          # + adb push charts to the tablet
    .\run_pipeline.ps1 -Install -Push # + install the debug APK first
#>
param(
  [switch]$Push,
  [switch]$Install
)
$ErrorActionPreference = 'Stop'
$dir = 'D:\Work\Programming\Android\MyNavvy\charts-pipeline'
$out = Join-Path $dir 'out'
$adb = 'D:\Work\Android\platform-tools\adb.exe'
$apk = 'D:\Work\Programming\Android\MyNavvy\app\build\outputs\apk\debug\app-debug.apk'
$pkg = 'com.dvladi.mynavvy'
New-Item -ItemType Directory -Force $out | Out-Null

Write-Host '== Building pipeline image ==' -ForegroundColor Cyan
docker build -t mynavvy-charts $dir

Write-Host '== Generating charts.mbtiles ==' -ForegroundColor Cyan
docker run --rm -v "${out}:/data" mynavvy-charts

if ($Install) {
  Write-Host '== Installing APK ==' -ForegroundColor Cyan
  & $adb install -r $apk
  # First launch creates the external files dir; start once so the push target exists.
  & $adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
  Start-Sleep -Seconds 3
}

if ($Push) {
  $dest = "/sdcard/Android/data/$pkg/files/charts.mbtiles"
  Write-Host "== Pushing charts to $dest ==" -ForegroundColor Cyan
  & $adb push (Join-Path $out 'charts.mbtiles') $dest
  & $adb shell am force-stop $pkg
  & $adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
  Write-Host 'Done. Charts should now render offline.' -ForegroundColor Green
}
