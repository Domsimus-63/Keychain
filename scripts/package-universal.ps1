param(
    [string]$OutputPath,
    [string]$JarDirectory
)

$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path $PSScriptRoot -Parent
$outputs = Split-Path $sourceRoot -Parent

$names = @(
    'keychain-mc1.21.11-3.0.jar',
    'keychain-mc26.1-3.0.jar',
    'keychain-mc26.2-3.0.jar',
    'keychain-mc26.3-3.0.jar'
)
if (-not $JarDirectory) {
    $JarDirectory = @((Join-Path $outputs 'MC'), $outputs) |
        Where-Object {
            $candidate = $_
            @($names | Where-Object { -not (Test-Path -LiteralPath (Join-Path $candidate $_) -PathType Leaf) }).Count -eq 0
        } | Select-Object -First 1
}
if (-not $JarDirectory) {
    throw 'Could not find all four Keychain builds in outputs or outputs/MC.'
}
if (-not $OutputPath) {
    $OutputPath = Join-Path $JarDirectory 'keychain-3.0-universal.jar'
}
foreach ($name in $names) {
    if (-not (Test-Path -LiteralPath (Join-Path $JarDirectory $name) -PathType Leaf)) {
        throw "Missing build: $name"
    }
}

$metadata = [ordered]@{
    schemaVersion = 1
    id = 'keychain_multiversion'
    version = '3.0'
    name = 'Keychain 3.0 Bundle'
    description = 'Selects the matching Keychain build for Minecraft 1.21.11 or 26.1 through 26.3.'
    authors = @('Domsimus')
    contact = @{ homepage = 'https://modrinth.com/mod/keychain' }
    license = 'MIT'
    environment = 'client'
    jars = @($names | ForEach-Object { @{ file = "META-INF/jars/$_" } })
    depends = [ordered]@{
        fabricloader = '>=0.19.2'
        minecraft = @('1.21.11', '>=26.1 <26.4')
        java = '>=21'
        accountswitcher = '3.0'
    }
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (Test-Path -LiteralPath $OutputPath) {
    Remove-Item -LiteralPath $OutputPath -Force
}
$archive = [System.IO.Compression.ZipFile]::Open($OutputPath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    $entry = $archive.CreateEntry('fabric.mod.json')
    $writer = [System.IO.StreamWriter]::new($entry.Open(), [System.Text.UTF8Encoding]::new($false))
    try {
        $writer.Write(($metadata | ConvertTo-Json -Depth 10))
    } finally {
        $writer.Dispose()
    }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $archive, (Join-Path $sourceRoot 'LICENSE'), 'LICENSE',
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    foreach ($name in $names) {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $archive, (Join-Path $JarDirectory $name), "META-INF/jars/$name",
            [System.IO.Compression.CompressionLevel]::NoCompression) | Out-Null
    }
} finally {
    $archive.Dispose()
}
Write-Output $OutputPath
