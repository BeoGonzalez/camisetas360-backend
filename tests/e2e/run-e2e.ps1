$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path

# Windows PowerShell 5 can treat native stderr warnings as terminating errors
# when the caller redirects output. Maven's exit code determines success.
function Invoke-MavenVerify {
    param([string]$Wrapper, [string[]]$MavenArguments)
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Wrapper @MavenArguments
        $mavenExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($mavenExitCode -ne 0) { throw "Maven failed ($mavenExitCode): $Wrapper $MavenArguments" }
}

# Build current executable JARs and run each participating service's own suite first.
foreach ($module in @('carrito', 'orders', 'notifications')) {
    Push-Location (Join-Path $repoRoot $module)
    try {
        Invoke-MavenVerify -Wrapper '.\mvnw.cmd' -MavenArguments @('-B', '-ntp', 'verify')
    } finally {
        Pop-Location
    }
}
Push-Location $repoRoot
try {
    Invoke-MavenVerify -Wrapper '.\orders\mvnw.cmd' -MavenArguments @('-B', '-ntp', '-f', 'tests/e2e/pom.xml', '-Pe2e', 'verify')
} finally {
    Pop-Location
}
