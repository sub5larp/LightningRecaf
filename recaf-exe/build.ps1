# Builds LightningRecaf.exe with the C# compiler bundled with Windows (.NET Framework 4.x) - no SDK needed.
#   powershell -ExecutionPolicy Bypass -File build.ps1 [-Install <path>] [-NoInstall]
param(
    [string]$Install = "",
    [switch]$NoInstall
)
if ($NoInstall) { $Install = "" }
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$root   = $PSScriptRoot
$assets = Join-Path $root "assets"
$out    = Join-Path $root "bin"
New-Item -ItemType Directory -Force $assets, $out | Out-Null

# Generate icon sizes from the retained dark grayscale edit of Recaf's original R artwork.
$icoPath = Join-Path $assets 'lightning.ico'
$logoPath = Join-Path $assets 'lightning.png'
if (-not (Test-Path -LiteralPath $icoPath) -or -not (Test-Path -LiteralPath $logoPath)) {
    & (Join-Path (Split-Path $root -Parent) 'build-branding.ps1')
}
if (-not (Test-Path -LiteralPath $icoPath) -or -not (Test-Path -LiteralPath $logoPath)) {
    throw 'LightningRecaf branding assets were not generated.'
}
# --- Compile ------------------------------------------------------------------------------------------
$fw  = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319"
$csc = Join-Path $fw "csc.exe"
$exe = Join-Path $out "LightningRecaf.exe"
$cscArgs = @(
    "/nologo", "/target:winexe", "/platform:anycpu", "/optimize+", "/utf8output",
    "/out:$exe",
    "/win32icon:$icoPath",
    "/win32manifest:$(Join-Path $root 'app.manifest')",
    "/resource:$logoPath,logo.png",
    "/r:System.dll", "/r:System.Core.dll", "/r:System.Drawing.dll", "/r:System.Windows.Forms.dll",
    "/r:System.Xml.dll", "/r:$fw\System.Web.Extensions.dll", "/r:$fw\System.IO.Compression.dll"
)
# Embed the matching LightningRecaf plugin (..\recaf-enhanced\build.ps1).
$pluginJar = Join-Path (Split-Path $root -Parent) "recaf-enhanced\build\recaf-enhanced.jar"
if (Test-Path $pluginJar) {
    $cscArgs += "/resource:$pluginJar,recaf-enhanced.jar"
    Write-Host "Embedding plugin $pluginJar"
} else {
    throw "Build the LightningRecaf plugin first: $pluginJar is missing"
}
$cscArgs += (Get-ChildItem $root -Filter *.cs | ForEach-Object { $_.FullName })
& $csc @cscArgs
if ($LASTEXITCODE -ne 0) { throw "csc failed with exit code $LASTEXITCODE" }
Write-Host "Built $exe ($((Get-Item $exe).Length) bytes)"
Copy-Item -LiteralPath (Join-Path $root 'LightningRecaf.exe.config') -Destination ($exe + '.config') -Force

if ($Install) {
    Copy-Item $exe $Install -Force
    Copy-Item -LiteralPath ($exe + '.config') -Destination ($Install + '.config') -Force
    Write-Host "Installed to $Install"
}
