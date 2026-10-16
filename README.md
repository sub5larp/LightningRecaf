# LightningRecaf

<img src="recaf-exe/assets/lightning.png" alt="LightningRecaf logo" width="96">

A Recaf-based Java reverse-engineering workbench with automatic deobfuscation,
normalization, a customizable grayscale interface, and portable Windows packaging.

LightningRecaf is an independent project based on [Recaf](https://github.com/Col-E/Recaf)
by Matt Coley and contributors. This repository contains the Windows entry point, enhancement
plugin, branding assets, and source for the patched engine components. The custom
engine is pinned to Recaf commit `17216bd3b76bc6ef36c4198262b5318e2936fbb7`.

## Download

Download **LightningRecaf.exe** from [Releases](https://github.com/sub5larp/LightningRecaf/releases/latest)
and double-click it. Java and the custom engine are included. No installer, Java
setup or manual extraction is needed. Requires Windows 10/11 x64 and .NET Framework 4.8.

The two release downloads are:

- **LightningRecaf.exe** — the standalone application.
- **LightningRecaf-source.zip** — source, build scripts, assets, pinned engine
  dependencies and matching OpenJDK source for this release.

The EXE opens LightningRecaf directly. It checks this repository's stable GitHub
releases in the background, downloads a newer EXE, verifies its SHA-256 digest and
version, and uses it automatically on the next launch. An open workspace is never
restarted for an update. Offline or failed checks keep the installed version usable.

Checks run at most once every 15 minutes when the app is launched. Updates stay in
the data folder, preserving settings and portable installations; the downloaded
EXE remains a stable entry point. Pass `--no-update` to run that EXE's bundled version
without checking, or set `auto-update=false` in
`%LOCALAPPDATA%\LightningRecaf\data\launcher\recaf-exe.ini` to disable automatic
updates. Extracted copies use `data\launcher\recaf-exe.ini` beside their EXE.

On first launch the EXE prepares its bundled files under
`%LOCALAPPDATA%\LightningRecaf\app`. Settings, plugins, logs and caches live under
`%LOCALAPPDATA%\LightningRecaf\data` and survive application updates. Preparing
the first launch takes longer; subsequent launches reuse the verified files.
To share the app, send the downloaded EXE. It contains no user settings or saved paths.

For an extracted portable copy, run `LightningRecaf.exe --extract C:\Tools\LightningRecaf`.
The destination must be a new folder. Run the EXE inside that folder; its data stays
beside it. Spaces are supported. If Windows uses a profile path that the bundled
Java launcher cannot represent in the system code page, use this extraction option
with a simple path such as `C:\Tools\LightningRecaf`.

## Features

- Automatic, bounded recovery for supported ZKM, Skidfuscator and other obfuscator
  patterns: strings, numeric encodings, static tables, opaque branches and metadata.
- 36 automatic transformation passes; optional extended recovery enables 40.
- Original, deobfuscated and normalized views, with per-class recovery evidence
  and exportable Tiny v2 name mappings.
- Decompiled-result caching, shared concurrent work, cancellation and editor
  deadlines that clear the loading overlay.
- Network-reference highlighting and a searchable network report.
- Up to ten additional subtree explorer tabs within the same workspace.
- A dark grayscale version of Recaf's R logo, configurable themes, gradients,
  animated backgrounds, translucent panels, rounding and reduced-motion options.
- Optional Windows Open With, context-menu and Start Menu integration.

Recovery depends on the actual bytecode and available state; an obfuscator name
does not imply support for every version, configuration or layered combination.
Unknown runtime-derived values remain unresolved. Diagnostic candidates are
distinguished from automatic replacements. See the in-app recovery report and
[coverage reference](recaf-enhanced/resources/recafx/coverage.txt) for details.

Automatic JVM recovery applies to Java classfiles. Android archive associations
do not imply equivalent recovery for arbitrary DEX or native code. The original
input archive is preserved; exporting modified output is an explicit operation.

## Windows integration

Windows registration is optional. From the folder containing the downloaded EXE:

```powershell
.\LightningRecaf.exe --install
```

This registers the prepared application for your Windows account. Use `--uninstall`
to remove it. For an extracted portable copy, registration uses that copy's location;
unregister it before moving its folder. Registration replaces any existing
LightningRecaf registration for that account; it does not force Windows file defaults.

Open a file through the application, drag it onto the executable, or pass its path:

```powershell
.\LightningRecaf.exe "C:\Samples\example.jar"
```

## Build from source

Download and extract **LightningRecaf-source.zip**. It contains the source and
pinned Recaf/JavaFX dependencies. Use Windows PowerShell 5.1 or newer and .NET
Framework 4.8. Run this command from the extracted source folder:

```powershell
powershell -ExecutionPolicy Bypass -File .\build-release.ps1
```

The builder downloads the checksum-pinned official JDK if it is not cached, uses
that JDK to compile the engine patches and plugin, builds the Windows application and emits
the EXE and source ZIP under `dist\LightningRecaf-2.4.0`. An existing output folder
is never overwritten. `-OutputDirectory`, `-WorkDirectory` and `-DownloadsDirectory`
select alternate locations.

For a Git checkout, obtain the pinned `engine` files from the source ZIP first.
The individual build scripts also support `JAVA_HOME` pointing to JDK 22 or newer.

## Licensing and credits

The combined LightningRecaf enhancement code is provided under
[GNU GPL v3.0](LICENSE). Existing component licenses and copyright notices remain
applicable: Recaf is MIT-licensed, and adapted Diobfuscator recovery code is GPL-licensed.
The grayscale logo is derived from Recaf's original artwork. Skidfuscator decoder
templates informed specific recovery models.

See [third-party notices](THIRD-PARTY-NOTICES.txt) and [licenses](licenses) for
attribution and license texts. The EXE retains the unmodified Temurin/OpenJDK
runtime's notices; its matching source is in the companion source ZIP. LightningRecaf is not an
official Recaf release and is not affiliated with its maintainers.
