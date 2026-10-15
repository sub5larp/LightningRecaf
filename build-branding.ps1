$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
. (Join-Path $project 'build-common.ps1')
Add-Type -AssemblyName System.Drawing
$assets = Join-Path $project 'recaf-exe\assets'
New-Item -ItemType Directory -Force -Path $assets | Out-Null
# Dark grayscale edit of Recaf's original circular R. The source image is retained for reproducible packaging.
$master = [Drawing.Image]::FromFile((Join-Path $assets 'recaf-dark-master.png'))
function Render-Lightning([int]$size) {
    $bmp = [Drawing.Bitmap]::new($size, $size)
    $g = [Drawing.Graphics]::FromImage($bmp)
    try {
        $g.Clear([Drawing.Color]::Transparent)
        $g.CompositingMode = [Drawing.Drawing2D.CompositingMode]::SourceCopy
        $g.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $attributes = [Drawing.Imaging.ImageAttributes]::new()
        try {
            $attributes.SetWrapMode([Drawing.Drawing2D.WrapMode]::TileFlipXY)
            $g.DrawImage($master, [Drawing.Rectangle]::new(0,0,$size,$size), 0,0,$master.Width,$master.Height,[Drawing.GraphicsUnit]::Pixel,$attributes)
        } finally { $attributes.Dispose() }
        $memory = [IO.MemoryStream]::new()
        try { $bmp.Save($memory,[Drawing.Imaging.ImageFormat]::Png); return ,$memory.ToArray() } finally { $memory.Dispose() }
    } finally { $g.Dispose(); $bmp.Dispose() }
}
$logo=Render-Lightning 256
[IO.File]::WriteAllBytes((Join-Path $assets 'lightning.png'),$logo)
$sizes=16,20,24,32,40,48,64,96,128,256
$frames=@($sizes | ForEach-Object { ,(Render-Lightning $_) })
$master.Dispose()
$writer=[IO.BinaryWriter]::new([IO.File]::Create((Join-Path $assets 'lightning.ico')))
try {
    $writer.Write([UInt16]0); $writer.Write([UInt16]1); $writer.Write([UInt16]$sizes.Count)
    $offset=6+16*$sizes.Count
    for($i=0;$i -lt $sizes.Count;$i++) {
        $dim=if($sizes[$i] -eq 256){0}else{$sizes[$i]}
        $writer.Write([byte]$dim);$writer.Write([byte]$dim);$writer.Write([byte]0);$writer.Write([byte]0)
        $writer.Write([UInt16]1);$writer.Write([UInt16]32);$writer.Write([UInt32]$frames[$i].Length);$writer.Write([UInt32]$offset)
        $offset+=$frames[$i].Length
    }
    foreach($frame in $frames){$writer.Write([byte[]]$frame)}
} finally { $writer.Dispose() }
$overlay=Join-Path $project 'build\branding'
Clear-LightningBuildDirectory $overlay $project
New-Item -ItemType Directory -Force -Path (Join-Path $overlay 'icons') | Out-Null
foreach($name in 'logo.png','logo-full.png','logo-mini.png') { [IO.File]::WriteAllBytes((Join-Path $overlay "icons\$name"),$logo) }
Copy-Item -LiteralPath (Join-Path $assets 'lightning.png') -Destination (Join-Path $project 'recaf-enhanced\resources\recafx\logo.png')
$jdk=Get-LightningJdk
Initialize-LightningEngine | Out-Null
& (Join-Path $jdk 'bin\jar.exe') --create --file (Join-Path $engineRoot 'LightningRecaf-branding.jar') -C $overlay .
if ($LASTEXITCODE) { throw 'Branding overlay creation failed' }
