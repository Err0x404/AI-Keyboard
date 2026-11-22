param(
    [string]$RimeSource = "",
    [string]$Variant = "Release"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($RimeSource)) {
    throw "Pass -RimeSource with the librime source directory."
}

if (-not (Test-Path -LiteralPath (Join-Path $RimeSource "CMakeLists.txt"))) {
    throw "librime source is missing at the supplied path: $RimeSource"
}

Push-Location $repo
try {
    & ".\gradlew.bat" "-PrimeSourceDir=$RimeSource" ":app:externalNativeBuild$Variant" "--no-configuration-cache"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & ".\gradlew.bat" "-PrimeSourceDir=$RimeSource" ":app:testDebugUnitTest" "--tests" "com.example.ime.RimeOptionMappingTest" "--no-configuration-cache"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}
