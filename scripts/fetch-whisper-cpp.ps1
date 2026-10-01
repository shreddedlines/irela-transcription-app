# Fetches the whisper.cpp sources that android/lib compiles into the JNI library.
#
#   .\scripts\fetch-whisper-cpp.ps1           # run from the repository root
#
# Pinned to the upstream release the app was developed and tested against.
# The sources go to third_party\whisper.cpp, which is git-ignored.
$ErrorActionPreference = 'Stop'

$Repo = 'https://github.com/ggml-org/whisper.cpp.git'
$Tag  = if ($env:WHISPER_CPP_TAG) { $env:WHISPER_CPP_TAG } else { 'v1.9.3' }

$Root = Split-Path -Parent $PSScriptRoot
$Dest = Join-Path $Root 'third_party\whisper.cpp'

if (Test-Path (Join-Path $Dest 'src\whisper.cpp')) {
    Write-Host "whisper.cpp already present at $Dest (delete it to re-fetch)."
    exit 0
}

New-Item -ItemType Directory -Force (Join-Path $Root 'third_party') | Out-Null
git clone --depth 1 --branch $Tag $Repo $Dest
if ($LASTEXITCODE -ne 0) { throw "git clone failed ($LASTEXITCODE)" }
Write-Host "Fetched whisper.cpp $Tag into $Dest"
