<#
Pruebas de carga/estres con JMeter contra el stack local (docker compose).

Uso (desde la raiz del repo, con los JARs ya construidos y Docker corriendo):
  .\tests\stress\run-stress.ps1 -Perfil carga     # 40 navegando + 15 comprando, 3 min
  .\tests\stress\run-stress.ps1 -Perfil estres    # 150 navegando + 50 comprando, 5 min
  .\tests\stress\run-stress.ps1 -Restaurar        # vuelve a la validacion normal con Entra ID

Que hace:
  1. Genera una clave RSA local, su JWKS y 200 tokens CUSTOMER (tests/stress/data, ignorado por git).
  2. Levanta el stack con docker-compose.stress.yml: los resource servers validan contra ese JWKS
     en lugar de Entra ID (solo local; nunca usar en produccion).
  3. Descarga JMeter 5.6.3 (verificando SHA-512) en tests/stress/tools si no existe.
  4. Ejecuta camisetas360.jmx y genera el dashboard HTML en tests/stress/results/<perfil>/dashboard.
#>
param(
    [ValidateSet('carga', 'estres')] [string]$Perfil = 'carga',
    [switch]$Restaurar
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path "$PSScriptRoot\..\.."
$here = $PSScriptRoot
$compose = @('-f', "$root\docker-compose.yml", '-f', "$here\docker-compose.stress.yml")

if ($Restaurar) {
    docker compose -f "$root\docker-compose.yml" up -d auth catalog carrito orders notifications analytics recommendation
    docker rm -f jwks-stress 2>$null | Out-Null
    Write-Host 'Stack restaurado con validacion Entra ID.'
    return
}

$perfiles = @{
    carga  = @{ nav = 40;  buy = 15; rampup = 60;  duration = 180 }
    estres = @{ nav = 150; buy = 50; rampup = 120; duration = 300 }
}
$p = $perfiles[$Perfil]

# 1. Tokens y JWKS
$nimbus = Get-ChildItem "$env:USERPROFILE\.m2\repository\com\nimbusds\nimbus-jose-jwt" -Recurse -Filter 'nimbus-jose-jwt-*.jar' |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } | Sort-Object Name | Select-Object -Last 1
if (-not $nimbus) { throw 'No se encontro nimbus-jose-jwt en ~/.m2: ejecuta antes .\mvnw.cmd clean verify en algun servicio.' }
java -cp $nimbus.FullName "$here\GenTokens.java" "$here\data" 200

# 2. Stack con emisor JWT de prueba
docker compose @compose up -d jwks-stress auth catalog carrito orders notifications analytics recommendation pagos
foreach ($c in 'app-auth', 'app-catalog', 'app-carrito', 'app-orders', 'app-notifications', 'app-analytics', 'app-recommendation', 'app-pagos') {
    for ($i = 0; $i -lt 60 -and (docker inspect -f '{{.State.Health.Status}}' $c) -ne 'healthy'; $i++) { Start-Sleep 3 }
}

# 3. JMeter
$jmeterHome = "$here\tools\apache-jmeter-5.6.3"
if (-not (Test-Path "$jmeterHome\bin\jmeter.bat")) {
    New-Item -ItemType Directory -Force "$here\tools" | Out-Null
    $url = 'https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-5.6.3.zip'
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest $url -OutFile "$here\tools\jmeter.zip" -UseBasicParsing
    $expected = ((Invoke-WebRequest "$url.sha512" -UseBasicParsing).Content -split '\s+')[0]
    if ((Get-FileHash "$here\tools\jmeter.zip" -Algorithm SHA512).Hash -ine $expected) { throw 'SHA-512 de JMeter no coincide' }
    Expand-Archive "$here\tools\jmeter.zip" -DestinationPath "$here\tools" -Force
}

# 4. Ejecucion
$out = "$here\results\$Perfil"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $out | Out-Null
$env:HEAP = '-Xms512m -Xmx1g'
& "$jmeterHome\bin\jmeter.bat" -n -t "$here\camisetas360.jmx" -l "$out\results.jtl" -j "$out\jmeter.log" -e -o "$out\dashboard" `
    "-Jtokens=$here\data\tokens.csv" "-Jnav=$($p.nav)" "-Jbuy=$($p.buy)" "-Jrampup=$($p.rampup)" "-Jduration=$($p.duration)"
Write-Host "Dashboard: $out\dashboard\index.html"
Write-Host 'Para volver a Entra ID: .\tests\stress\run-stress.ps1 -Restaurar'
