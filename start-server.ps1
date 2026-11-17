param(
    [string]$ServerJar = 'fabric-server-launch.jar',
    [string]$JavaPath = 'java',
    [string]$MinMemory = '1G',
    [string]$MaxMemory = '3G',
    [ValidateSet('Auto', 'Fabric', 'Forge', 'NeoForge')]
    [string]$Loader = 'Auto',
    [string]$ForgeArgsFile = ''
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$updateDirectory = Join-Path $PSScriptRoot 'config\guildquests\update'
$installPlan = Join-Path $updateDirectory 'pending-install.properties'
$installer = Join-Path $updateDirectory 'installer.jar'
$restartRequest = Join-Path $updateDirectory 'restart.requested'

# Forge uses minecraftforge; NeoForge 1.21+ uses neoforged/neoforge, and 1.20.1 NeoForge used neoforged/forge.
$loaderArgs = @()
foreach ($entry in @(
        @{ Loader = 'Forge'; Directory = 'libraries\net\minecraftforge\forge' },
        @{ Loader = 'NeoForge'; Directory = 'libraries\net\neoforged\neoforge' },
        @{ Loader = 'NeoForge'; Directory = 'libraries\net\neoforged\forge' })) {
    $directory = Join-Path $PSScriptRoot $entry.Directory
    if (Test-Path -LiteralPath $directory -PathType Container) {
        $loaderArgs += @(Get-ChildItem -LiteralPath $directory -Directory | ForEach-Object {
            $candidate = Join-Path $_.FullName 'win_args.txt'
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                [pscustomobject]@{ Path = $candidate; Loader = $entry.Loader }
            }
        })
    }
}
$forgeFiles = @($loaderArgs | Where-Object Loader -eq 'Forge')
$neoforgeFiles = @($loaderArgs | Where-Object Loader -eq 'NeoForge')
if ($ForgeArgsFile) {
    if ($Loader -eq 'Fabric') { throw '-ForgeArgsFile cannot be combined with -Loader Fabric.' }
    $Loader = if ($ForgeArgsFile -match '[\\/]neoforged[\\/]') { 'NeoForge' } else { 'Forge' }
}
if ($Loader -eq 'Auto') {
    $detected = @()
    if ($forgeFiles.Count -gt 0) { $detected += 'Forge' }
    if ($neoforgeFiles.Count -gt 0) { $detected += 'NeoForge' }
    if (Test-Path -LiteralPath $ServerJar -PathType Leaf) { $detected += 'Fabric' }
    if ($detected.Count -gt 1) {
        throw "Multiple loaders were found ($($detected -join ', ')). Select -Loader Fabric, -Loader Forge or -Loader NeoForge."
    }
    $Loader = if ($detected.Count -eq 1) { $detected[0] } else { 'Fabric' }
}
$launchArguments = @()
if ($Loader -ne 'Fabric') {
    $candidates = if ($Loader -eq 'NeoForge') { $neoforgeFiles } else { $forgeFiles }
    if (-not $ForgeArgsFile) {
        if ($candidates.Count -ne 1) { throw "Select one installed $Loader version with -ForgeArgsFile libraries/.../win_args.txt." }
        $ForgeArgsFile = $candidates[0].Path
    }
    if (-not (Test-Path -LiteralPath $ForgeArgsFile -PathType Leaf)) { throw "$Loader argument file not found: $ForgeArgsFile" }
    if (Test-Path -LiteralPath 'user_jvm_args.txt' -PathType Leaf) { $launchArguments += '@user_jvm_args.txt' }
    $launchArguments += "-Xms$MinMemory", "-Xmx$MaxMemory", "@$ForgeArgsFile", 'nogui'
} else {
    if (-not (Test-Path -LiteralPath $ServerJar -PathType Leaf)) { throw "Server JAR not found: $ServerJar" }
    $launchArguments += "-Xms$MinMemory", "-Xmx$MaxMemory", '-jar', $ServerJar, 'nogui'
}
while ($true) {
    # Always finish a pending installation before the server scans mods, including after a crash.
    if (Test-Path -LiteralPath $installPlan) {
        if (-not (Test-Path -LiteralPath $installer -PathType Leaf)) {
            throw "Update installer is missing: $installer"
        }
        & $JavaPath '-cp' $installer 'dev.guildquests.update.UpdateInstaller' $installPlan
        if ($LASTEXITCODE -ne 0) { throw 'Update failed. See config/guildquests/update/installer.log. Server was not restarted.' }
    }
    if (Test-Path -LiteralPath $restartRequest) { Remove-Item -LiteralPath $restartRequest }
    & $JavaPath @launchArguments
    $serverExitCode = $LASTEXITCODE
    # A normal stop or a crash does not request an automatic restart.
    if (-not (Test-Path -LiteralPath $restartRequest)) { exit $serverExitCode }
    Write-Host 'GuildQuests requested an update. Applying it before restarting...'
}
