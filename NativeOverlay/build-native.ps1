$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$imgui = Join-Path $PSScriptRoot 'third_party\imgui'
$output = Join-Path $PSScriptRoot 'out'
$vcvars = 'C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat'
$javaHome = 'C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot'

if (-not (Test-Path $vcvars)) {
    throw "Visual C++ build environment was not found: $vcvars"
}
if (-not (Test-Path (Join-Path $javaHome 'include\jni.h'))) {
    throw "JDK JNI headers were not found: $javaHome"
}
if (-not (Test-Path (Join-Path $imgui 'imgui.cpp'))) {
    throw "The pinned nyaoouo/imgui source is missing: $imgui"
}

New-Item -ItemType Directory -Force $output | Out-Null
$sources = @(
    (Join-Path $PSScriptRoot 'Minecraft1211ezcheatOverlay.cpp'),
    (Join-Path $imgui 'imgui.cpp'),
    (Join-Path $imgui 'imgui_draw.cpp'),
    (Join-Path $imgui 'imgui_tables.cpp'),
    (Join-Path $imgui 'imgui_widgets.cpp'),
    (Join-Path $imgui 'backends\imgui_impl_win32.cpp'),
    (Join-Path $imgui 'backends\imgui_impl_opengl3.cpp')
)
$quotedSources = ($sources | ForEach-Object { '"' + $_ + '"' }) -join ' '
$dll = Join-Path $output 'Minecraft1211ezcheat-overlay-2.0.0.dll'
$command = @"
call "$vcvars" >nul && cd /d "$output" && cl /nologo /std:c++20 /O2 /MT /EHsc /utf-8 /LD /DUNICODE /D_UNICODE /DNOMINMAX /DWIN32_LEAN_AND_MEAN /I"$javaHome\include" /I"$javaHome\include\win32" /I"$imgui" /I"$imgui\backends" $quotedSources /link /OUT:"$dll" /IMPLIB:"$output\Minecraft1211ezcheat-overlay-2.0.0.lib" opengl32.lib user32.lib gdi32.lib imm32.lib
"@
cmd.exe /d /s /c $command
if ($LASTEXITCODE -ne 0) {
    throw "Native overlay compilation failed with exit code $LASTEXITCODE"
}
Write-Output $dll
