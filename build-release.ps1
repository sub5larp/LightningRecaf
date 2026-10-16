param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'dist\LightningRecaf-2.4.0'),
    [string]$WorkDirectory = (Join-Path $PSScriptRoot 'build\release'),
    [string]$DownloadsDirectory = (Join-Path $PSScriptRoot 'build\downloads')
)
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
. (Join-Path $project 'build-common.ps1')
Add-Type -AssemblyName System.IO.Compression.FileSystem
$destination = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $destination) { throw 'Output already exists. Choose a new -OutputDirectory.' }
$work = Join-Path ([IO.Path]::GetFullPath($WorkDirectory)) ([Guid]::NewGuid().ToString('N'))
$downloads = [IO.Path]::GetFullPath($DownloadsDirectory)
New-Item -ItemType Directory -Force -Path $work, $downloads | Out-Null
$pin = Get-Content -Raw -LiteralPath (Join-Path $project 'standalone\runtime-pin.json') | ConvertFrom-Json
foreach ($asset in @($pin.binary, $pin.source)) {
    $path = Join-Path $downloads $asset.name
    $supplied = Join-Path $project ('third-party\' + $asset.name)
    if (-not (Test-Path -LiteralPath $path) -and (Test-Path -LiteralPath $supplied)) {
        Copy-Item -LiteralPath $supplied -Destination $path
    }
    if (-not (Test-Path -LiteralPath $path)) {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -UseBasicParsing -Uri $asset.url -OutFile ($path + '.partial')
        if ((Get-FileHash -LiteralPath ($path + '.partial') -Algorithm SHA256).Hash -ne $asset.sha256) { throw "Download checksum failed: $($asset.name)" }
        Move-Item -LiteralPath ($path + '.partial') -Destination $path
    }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $asset.sha256) { throw "Archive checksum failed: $($asset.name)" }
}
$runtimeStage = Join-Path $work 'jdk'
[IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $downloads $pin.binary.name), $runtimeStage)
$jdk = @(Get-ChildItem -LiteralPath $runtimeStage -Directory)
if ($jdk.Count -ne 1) { throw 'Unexpected JDK archive layout' }
foreach ($required in 'bin\java.exe','bin\javac.exe','lib\modules','release','NOTICE','legal') {
    if (-not (Test-Path -LiteralPath (Join-Path $jdk[0].FullName $required))) { throw "Missing runtime component: $required" }
}
$previousJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $jdk[0].FullName
    Initialize-LightningEngine | Out-Null
    & (Join-Path $project 'build-branding.ps1')
    & (Join-Path $project 'engine-patches\build.ps1')
    & (Join-Path $project 'recaf-enhanced\build.ps1')
    & (Join-Path $project 'recaf-exe\build.ps1') -NoInstall
} finally { $env:JAVA_HOME = $previousJava }

$package = Join-Path $work 'application'
New-Item -ItemType Directory -Path $package | Out-Null
Copy-Item -LiteralPath $jdk[0].FullName -Destination (Join-Path $package 'runtime') -Recurse
foreach ($name in 'LightningRecaf.exe','LightningRecaf.exe.config') {
    Copy-Item -LiteralPath (Join-Path $project ('recaf-exe\bin\' + $name)) -Destination $package
}
Set-Content -LiteralPath (Join-Path $package 'LightningRecaf.portable') -Value 'portable' -Encoding ascii
foreach ($name in 'LICENSE','THIRD-PARTY-NOTICES.txt') { Copy-Item -LiteralPath (Join-Path $project $name) -Destination $package }
Copy-Item -LiteralPath (Join-Path $project 'licenses') -Destination $package -Recurse
Copy-Item -LiteralPath (Join-Path $project 'standalone\runtime-pin.json') -Destination $package
$enginePin = Get-Content -Raw -LiteralPath (Join-Path $project 'engine\pin.json') | ConvertFrom-Json
foreach ($name in @($enginePin.files.path) + @('pin.json','LightningRecaf-patches.jar','LightningRecaf-branding.jar')) {
    $target = Join-Path $package ('engine\' + $name)
    New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
    Copy-Item -LiteralPath (Join-Path $project ('engine\' + $name)) -Destination $target
}

# Only the maintained source inputs are included, regardless of local development files.
$source = Join-Path $work 'source'
New-Item -ItemType Directory -Path $source | Out-Null
foreach ($name in Get-LightningSourceFiles) {
    $target = Join-Path $source $name
    New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
    Copy-Item -LiteralPath (Join-Path $project $name) -Destination $target
}
foreach ($name in $enginePin.files.path) {
    $target = Join-Path $source ('engine\' + $name)
    New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
    Copy-Item -LiteralPath (Join-Path $project ('engine\' + $name)) -Destination $target
}
New-Item -ItemType Directory -Path (Join-Path $source 'third-party') | Out-Null
Copy-Item -LiteralPath (Join-Path $downloads $pin.source.name) -Destination (Join-Path $source 'third-party')

$manifest = Join-Path $work 'package.manifest'
Get-ChildItem -LiteralPath $package -File -Recurse | Sort-Object FullName | ForEach-Object {
    (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $_.FullName.Substring($package.Length + 1).Replace('\','/')
} | Set-Content -LiteralPath $manifest -Encoding ascii
$payload = Join-Path $work 'package.zip'
New-LightningZip $package $payload
$digest = Join-Path $work 'package.sha256'
(Get-FileHash -LiteralPath $payload -Algorithm SHA256).Hash.ToLowerInvariant() | Set-Content -LiteralPath $digest -Encoding ascii
New-Item -ItemType Directory -Path $destination | Out-Null
$exe = Join-Path $destination 'LightningRecaf.exe'
$framework = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319'
& (Join-Path $framework 'csc.exe') /nologo /target:winexe /platform:x64 /optimize+ "/out:$exe" "/win32icon:$(Join-Path $project 'recaf-exe\assets\lightning.ico')" "/win32manifest:$(Join-Path $project 'recaf-exe\app.manifest')" /r:System.dll /r:System.Core.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll "/r:$framework\System.Web.Extensions.dll" "/r:$framework\System.IO.Compression.dll" "/resource:$payload,package.zip" "/resource:$manifest,package.manifest" "/resource:$digest,package.sha256" (Join-Path $project 'standalone\Bootstrap.cs') (Join-Path $project 'recaf-exe\Updates.cs')
if ($LASTEXITCODE) { throw 'Standalone EXE compilation failed' }
New-LightningZip $source (Join-Path $destination 'LightningRecaf-source.zip')
Get-ChildItem -LiteralPath $destination -File | ForEach-Object {
    Write-Host ((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $_.Name)
}
Write-Host "Built standalone EXE and source ZIP in $destination"
