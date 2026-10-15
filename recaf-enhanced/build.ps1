# Builds the Recaf Enhanced plugin jar against the installed recaf.jar + cached JavaFX.
#   powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = "Stop"
$root  = $PSScriptRoot
. (Join-Path (Split-Path $root -Parent) 'build-common.ps1')
$engineJar = Initialize-LightningEngine
$recaf = $engineRoot
$jdk = Get-LightningJdk
$javac = Join-Path $jdk "bin\javac.exe"
$jar   = Join-Path $jdk "bin\jar.exe"
$fx    = Get-ChildItem (Join-Path $recaf "dependencies") -Filter "javafx-*-win.jar" | Where-Object { $_.Name -notmatch '-ea\+' } | ForEach-Object { $_.FullName }
$overlay = Join-Path $engineRoot 'LightningRecaf-patches.jar'
if (-not (Test-Path -LiteralPath $overlay)) { throw 'Build engine-patches/build.ps1 before the plugin.' }
$cp    = (@($overlay, $engineJar) + $fx) -join ";"
$out   = Join-Path $root "build"
$classes = Join-Path $out "classes"
Clear-LightningBuildDirectory $classes $root
$sources = Get-ChildItem (Join-Path $root "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$argFile = Join-Path $out "sources.txt"
$sources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' } | Set-Content $argFile -Encoding ascii
& $javac --release 22 -encoding UTF-8 -Xlint:-options -proc:none -cp $cp -d $classes "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
Copy-Item -Recurse -Force (Join-Path $root "resources\*") $classes
$jarPath = Join-Path $out "recaf-enhanced.jar"
if (Test-Path $jarPath) { Remove-Item $jarPath }
& $jar --create --file $jarPath -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
Write-Host "Built $jarPath ($((Get-Item $jarPath).Length) bytes)"
