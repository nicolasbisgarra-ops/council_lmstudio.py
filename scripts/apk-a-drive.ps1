# Copia el APK de debug a la carpeta de Google Drive para escritorio.
# Drive lo sube solo, sin límite de tamaño. No usa red ni tokens.
#
#   .\scripts\apk-a-drive.ps1           copia el APK que ya está compilado
#   .\scripts\apk-a-drive.ps1 -Build    compila primero (:app:assembleDebug)
#   .\scripts\apk-a-drive.ps1 -Destino "G:\Mi unidad\Otra carpeta"
#
# Deja dos archivos en "<Drive>\Trazo APKs":
#   Trazo-<fecha>-<commit>-debug.apk   uno por versión
#   Trazo-ultimo-debug.apk             siempre el más nuevo (mismo enlace en Drive)

param(
    [switch]$Build,
    [string]$Destino
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

if ($Build) {
    & .\gradlew.bat :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Falló el build (código $LASTEXITCODE)." }
}

$apk = Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw "No hay APK en $apk. Corré el script con -Build." }

if (-not $Destino) {
    $raiz = $null
    foreach ($d in Get-PSDrive -PSProvider FileSystem) {
        foreach ($nombre in 'Mi unidad', 'My Drive') {
            $p = Join-Path $d.Root $nombre
            if (Test-Path $p) { $raiz = $p; break }
        }
        if ($raiz) { break }
    }
    if (-not $raiz) {
        throw "No encontré la carpeta de Google Drive (\Mi unidad o \My Drive). ¿Está abierto Google Drive para escritorio? Si no, pasá -Destino."
    }
    $Destino = Join-Path $raiz 'Trazo APKs'
}
New-Item -ItemType Directory -Force -Path $Destino | Out-Null

$commit = (git rev-parse --short HEAD 2>$null)
if (-not $commit) { $commit = 'sin-git' }
$fecha = Get-Date -Format 'yyyy-MM-dd_HHmm'
$version = Join-Path $Destino "Trazo-$fecha-$commit-debug.apk"
$ultimo = Join-Path $Destino 'Trazo-ultimo-debug.apk'

Copy-Item -LiteralPath $apk -Destination $version -Force
Copy-Item -LiteralPath $apk -Destination $ultimo -Force

$mb = [math]::Round((Get-Item $apk).Length / 1MB, 1)
$sha = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash.ToLower()
Write-Host "Listo: $version ($mb MB)"
Write-Host "También: $ultimo"
Write-Host "sha256 $sha"
Write-Host "Google Drive para escritorio lo sube en segundo plano."
