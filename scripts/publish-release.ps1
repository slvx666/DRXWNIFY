# Publishes a GitHub release for an already pushed tag and attaches the APK as "Drxwnify.apk"
# (the name the site and the in-app updater download). The token is asked for here and never
# stored. Usage (from Meld-main):
#
#   powershell -ExecutionPolicy Bypass -File scripts\publish-release.ps1 -Version 1.4.1
#
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Repo = "slvx666/DRXWNIFY",
    [string]$Apk = "app\build\outputs\apk\foss\release\app-foss-release.apk"
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$apkPath = Join-Path $root $Apk
if (-not (Test-Path $apkPath)) { throw "APK not found: $apkPath (build it with gradlew :app:assembleFossRelease)" }

# Release notes: the same text the app and the site show.
$releases = Get-Content (Join-Path $root "website\releases.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$notes = ($releases.releases | Where-Object { $_.version -eq $Version }).notes
if (-not $notes) { $notes = "Drxwnify $Version" }

$secure = Read-Host "GitHub token (input hidden)" -AsSecureString
$token = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
$headers = @{ Authorization = "Bearer $token"; Accept = "application/vnd.github+json"; "User-Agent" = "drxwnify-release" }

$tag = "v$Version"
$existing = $null
try { $existing = Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/$Repo/releases/tags/$tag" } catch { }
if ($existing) {
    $release = $existing
    Write-Host "Release $tag already exists, updating its APK"
} else {
    $body = @{ tag_name = $tag; name = "Drxwnify $Version"; body = $notes; make_latest = "true" } | ConvertTo-Json
    $release = Invoke-RestMethod -Method Post -Headers $headers -Uri "https://api.github.com/repos/$Repo/releases" `
        -Body ([Text.Encoding]::UTF8.GetBytes($body)) -ContentType "application/json; charset=utf-8"
    Write-Host "Created release $tag"
}

foreach ($asset in $release.assets) {
    if ($asset.name -eq "Drxwnify.apk") {
        Invoke-RestMethod -Method Delete -Headers $headers -Uri "https://api.github.com/repos/$Repo/releases/assets/$($asset.id)" | Out-Null
    }
}
$upload = "https://uploads.github.com/repos/$Repo/releases/$($release.id)/assets?name=Drxwnify.apk"
Write-Host "Uploading APK ($([math]::Round((Get-Item $apkPath).Length / 1MB, 1)) MB)..."
$result = Invoke-RestMethod -Method Post -Headers $headers -Uri $upload -InFile $apkPath -ContentType "application/vnd.android.package-archive"
Write-Host "Done: $($result.browser_download_url)"
Write-Host "Now delete the token on GitHub (Settings -> Developer settings -> Personal access tokens)."
