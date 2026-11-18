param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\..\work\artifacts')
)

$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath $PSScriptRoot
try {
    & bun run build
    if ($LASTEXITCODE -ne 0) { throw 'Extension build failed.' }
} finally {
    Pop-Location
}
$sourceDirectory = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\assaad-reader'))
$artifactDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$manifest = Get-Content -LiteralPath (Join-Path $sourceDirectory 'manifest.json') -Raw | ConvertFrom-Json
[System.IO.Directory]::CreateDirectory($artifactDirectory) | Out-Null
$archivePath = Join-Path $artifactDirectory ("assaad-reader-" + $manifest.version + '.zip')

# Use a temporary file so an interrupted build cannot damage a prior package.
$temporaryPath = Join-Path $artifactDirectory ([System.IO.Path]::GetRandomFileName() + '.zip')
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($sourceDirectory, $temporaryPath)
Move-Item -LiteralPath $temporaryPath -Destination $archivePath -Force

$archive = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
try {
    if (-not ($archive.Entries.FullName -contains 'manifest.json')) {
        throw 'Package must contain manifest.json at its root.'
    }
    if ($archive.Entries.FullName -match 'node_modules|\.test\.|package-lock') {
        throw 'Development files must not be shipped.'
    }
    $archive.Entries | Select-Object FullName, Length
} finally {
    $archive.Dispose()
}
Get-FileHash -LiteralPath $archivePath -Algorithm SHA256
