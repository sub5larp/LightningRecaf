$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
. (Join-Path (Split-Path $root -Parent) 'build-common.ps1')
$engineJar = Initialize-LightningEngine
$jdk = Get-LightningJdk
$classes = Join-Path $root 'build\classes'
Clear-LightningBuildDirectory $classes $root
$cp = (@($engineJar) + @(Get-ChildItem (Join-Path $engineRoot 'dependencies') -Filter '*.jar' | ForEach-Object FullName)) -join ';'
$sources = @(Get-ChildItem (Join-Path $root 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $jdk 'bin\javac.exe') --release 22 -encoding UTF-8 -proc:none -cp $cp -d $classes @sources
if ($LASTEXITCODE) { throw 'Engine patch compilation failed' }
& (Join-Path $jdk 'bin\jar.exe') --create --file (Join-Path $engineRoot 'LightningRecaf-patches.jar') -C $classes .
if ($LASTEXITCODE) { throw 'Engine patch packaging failed' }
