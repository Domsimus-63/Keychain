param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $taskRoot 'releases' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path
Push-Location $taskRoot
try {
    & "$PSScriptRoot/sync-legacy.ps1"
    foreach ($taskVersion in @('26.1', '26.2', '26.3')) {
        & .\gradlew.bat --console=plain build "-Pminecraft_version=$taskVersion"
        if ($LASTEXITCODE -ne 0) { throw "Build failed for Minecraft $taskVersion" }
        Copy-Item -LiteralPath "build/libs/keychain-mc$taskVersion-3.0.jar" -Destination $OutputDirectory
    }
    & .\legacy-1.21.11\gradlew.bat -p legacy-1.21.11 --console=plain build
    if ($LASTEXITCODE -ne 0) { throw 'Build failed for Minecraft 1.21.11' }
    Copy-Item -LiteralPath 'legacy-1.21.11/build/libs/keychain-mc1.21.11-3.0.jar' -Destination $OutputDirectory
    & "$PSScriptRoot/package-universal.ps1" -JarDirectory $OutputDirectory -OutputPath (Join-Path $OutputDirectory 'keychain-3.0-universal.jar')
} finally { Pop-Location }
