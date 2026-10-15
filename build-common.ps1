$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$engineRoot = Join-Path $projectRoot 'engine'
function Get-LightningJdk {
    $candidates = @($env:JAVA_HOME) + @(Get-ChildItem -LiteralPath 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object FullName)
    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $compiler = Join-Path $candidate 'bin\javac.exe'
        if (-not (Test-Path -LiteralPath $compiler)) { continue }
        $version = & $compiler -version 2>&1
        if ("$version" -match 'javac (\d+)' -and [int]$Matches[1] -ge 22) { return $candidate }
    }
    throw 'LightningRecaf needs JDK 22 or newer. Set JAVA_HOME to a compatible JDK.'
}
function Initialize-LightningEngine {
    New-Item -ItemType Directory -Force -Path $engineRoot | Out-Null
    $engineJar = Join-Path $engineRoot 'recaf.jar'
    if (-not (Test-Path -LiteralPath $engineJar)) {
        throw 'Pinned engine files are missing. Copy engine from the matching LightningRecaf source ZIP into this checkout.'
    }
    $deps = Join-Path $engineRoot 'dependencies'
    New-Item -ItemType Directory -Force -Path $deps | Out-Null
    if (-not (Get-ChildItem -LiteralPath $deps -Filter 'javafx-controls-*-win.jar')) {
        throw 'Pinned JavaFX files are missing. Copy engine from the matching LightningRecaf source ZIP into this checkout.'
    }
    $pinPath = Join-Path $engineRoot 'pin.json'
    if (Test-Path -LiteralPath $pinPath) {
        $pin = Get-Content -Raw -LiteralPath $pinPath | ConvertFrom-Json
        foreach ($file in $pin.files) {
            $path = [IO.Path]::GetFullPath((Join-Path $engineRoot $file.path))
            if (-not $path.StartsWith([IO.Path]::GetFullPath($engineRoot) + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid engine pin path' }
            if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $file.sha256) { throw "Pinned dependency changed: $($file.path)" }
        }
    }
    return $engineJar
}
function Clear-LightningBuildDirectory([string]$path, [string]$allowedRoot) {
    $absolute = [IO.Path]::GetFullPath($path)
    $boundary = [IO.Path]::GetFullPath($allowedRoot).TrimEnd('\') + '\'
    if (-not $absolute.StartsWith($boundary, [StringComparison]::OrdinalIgnoreCase)) { throw "Build directory is outside workspace: $absolute" }
    if (Test-Path -LiteralPath $absolute) { Remove-Item -LiteralPath $absolute -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $absolute | Out-Null
}
function Get-LightningSourceFiles {
    @('.gitattributes','.gitignore','LICENSE','README.md','THIRD-PARTY-NOTICES.txt',
      'build-common.ps1','build-branding.ps1','build-release.ps1','engine/pin.json',
      'engine-patches/build.ps1','recaf-enhanced/build.ps1','recaf-exe/build.ps1',
      'recaf-exe/app.manifest','recaf-exe/LightningRecaf.exe.config',
      'recaf-exe/assets/lightning.ico','recaf-exe/assets/lightning.png','recaf-exe/assets/recaf-dark-master.png',
      'standalone/Bootstrap.cs','standalone/runtime-pin.json')
    foreach ($folder in 'engine-patches/src','recaf-enhanced/src','recaf-enhanced/resources','licenses') {
        Get-ChildItem -LiteralPath (Join-Path $projectRoot $folder) -File -Recurse | ForEach-Object {
            $_.FullName.Substring($projectRoot.Length + 1).Replace('\','/')
        }
    }
    Get-ChildItem -LiteralPath (Join-Path $projectRoot 'recaf-exe') -File -Filter '*.cs' | ForEach-Object { 'recaf-exe/' + $_.Name }
}
function New-LightningZip([string]$directory, [string]$destination) {
    Add-Type -AssemblyName System.IO.Compression
    $boundary = [IO.Path]::GetFullPath($directory).TrimEnd('\') + '\'
    $stream = [IO.File]::Open($destination, [IO.FileMode]::CreateNew)
    try {
        $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create, $true)
        try {
            Get-ChildItem -LiteralPath $directory -File -Recurse | Sort-Object FullName | ForEach-Object {
                $name = $_.FullName.Substring($boundary.Length).Replace('\','/')
                $entry = $zip.CreateEntry($name, [IO.Compression.CompressionLevel]::Optimal)
                $inputStream = [IO.File]::OpenRead($_.FullName)
                try {
                    $outputStream = $entry.Open()
                    try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose() }
                } finally { $inputStream.Dispose() }
            }
        } finally { $zip.Dispose() }
    } finally { $stream.Dispose() }
}
