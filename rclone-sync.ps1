[CmdletBinding()]
param(
    [ValidateSet("init", "once", "watch")]
    [string] $Mode = "once",

    [ValidateRange(5, 3600)]
    [int] $IntervalSeconds = 15
)

$ErrorActionPreference = "Stop"

# Project-specific synchronization settings.
$ProjectRoot = $PSScriptRoot
$LocalMods = Join-Path $ProjectRoot "libs"
$LocalScripts = Join-Path $ProjectRoot "src\main\kotlin"
$StateDirectory = Join-Path $ProjectRoot ".rclone-state"
$LocalVersions = Join-Path $ProjectRoot ".rclone-versions"
$SyncPolicyVersion = "3"
$SyncPolicyMarker = Join-Path $StateDirectory "sync-policy-version.txt"

$RemoteMods = "testserver:mods"
$RemoteScripts = "testserver:jet/scripts"
$RemoteVersions = "testserver:jet/.rclone-versions/scripts"

function Invoke-Rclone {
    param(
        [Parameter(Mandatory)]
        [string[]] $Arguments,
        [switch] $AllowFailure
    )

    Write-Host ("rclone " + ($Arguments -join " ")) -ForegroundColor DarkGray
    & rclone @Arguments
    $exitCode = $LASTEXITCODE

    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "rclone exited with code $exitCode."
    }

    return $exitCode
}

function New-BisyncArguments {
    param(
        [switch] $Initialize,
        [switch] $DryRun
    )

    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $arguments = @(
        "bisync"
        $LocalScripts
        $RemoteScripts
        "--workdir", $StateDirectory
        # Keep the newer working copy. The replaced loser is handled as a
        # deletion, so backup-dir preserves it outside the working tree.
        "--conflict-resolve", "newer"
        "--conflict-loser", "delete"
        "--backup-dir1", $LocalVersions
        "--backup-dir2", $RemoteVersions
        "--suffix", ".$timestamp"
        "--suffix-keep-extension"
        "--max-delete", "25"
        "--resilient"
        "--recover"
        # Some SFTP servers incorrectly reset a directory's POSIX mode while
        # handling a timestamp-only SETSTAT request (for example, 0755 -> 0644).
        "--no-update-dir-modtime"
        # Do not replicate artifacts produced by the old conflict policy or by
        # the equal-timestamp fallback where rclone cannot choose a winner.
        # Filter rules are evaluated in this exact order. Include directories
        # so rclone continues traversing them before rejecting all other files.
        "--filter", "- *.conflict*.kts"
        "--filter", "- *.conflict*.kt"
        "--filter", "+ **/"
        "--filter", "+ *.jet.kts"
        "--filter", "+ *.jetlib.kt"
        "--filter", "- **"
        "--modify-window", "2s"
#         "--exclude", "/RCLONE_TEST"
#         "--verbose"
    )

    if ($Initialize) {
        $arguments += @("--resync-mode", "newer")
    }
    else {
        # Resync initializes with copy operations, where rename tracking is not
        # applicable. Enable it only for normal bisync runs.
        $arguments += @(
            "--track-renames"
            # SFTP-only accounts generally cannot run remote checksum commands.
            "--track-renames-strategy", "modtime,leaf"
        )
    }
    if ($DryRun) {
        $arguments += "--dry-run"
    }

    return $arguments
}

function Test-BisyncInitialized {
    if (-not (Test-Path -LiteralPath $SyncPolicyMarker -PathType Leaf)) {
        return $false
    }
    if ((Get-Content -LiteralPath $SyncPolicyMarker -Raw).Trim() -ne $SyncPolicyVersion) {
        return $false
    }

    # Successful bisync runs leave a matched pair of baseline listings. Dry-run
    # listings end in .lst-dry and intentionally do not count as initialization.
    foreach ($path1 in Get-ChildItem -LiteralPath $StateDirectory -Filter "*.path1.lst" -File -ErrorAction SilentlyContinue) {
        $path2Name = $path1.Name -replace '\.path1\.lst$', '.path2.lst'
        if (Test-Path -LiteralPath (Join-Path $StateDirectory $path2Name) -PathType Leaf) {
            return $true
        }
    }

    return $false
}

function Initialize-Bisync {
    param([switch] $PreviewAndConfirm)

    if ($PreviewAndConfirm) {
        Write-Host "Previewing initial two-way merge. No files will be changed." -ForegroundColor Cyan
        Invoke-Rclone -Arguments (New-BisyncArguments -Initialize -DryRun) | Out-Null

        Write-Host ""
        $confirmation = Read-Host "Type INIT to perform the initial merge"
        if ($confirmation -cne "INIT") {
            throw "Initialization cancelled; the preview made no changes."
        }
    }
    else {
        Write-Host "No completed bisync baseline was found; initializing automatically." -ForegroundColor Cyan
    }

    Invoke-Rclone -Arguments (New-BisyncArguments -Initialize) | Out-Null
    Set-Content -LiteralPath $SyncPolicyMarker -Value $SyncPolicyVersion -NoNewline
    Write-Host "Initial synchronization completed." -ForegroundColor Green
}

function Ensure-BisyncInitialized {
    if (-not (Test-BisyncInitialized)) {
        Initialize-Bisync
    }
}

function Sync-Once {
    # Mods are intentionally one-way: the SFTP server is authoritative.
    Invoke-Rclone -Arguments @(
        "sync", $RemoteMods, $LocalMods
        "--include", "*.jar"
        "--update"
        "--no-update-dir-modtime"
#         "--verbose"
    ) | Out-Null

    # Scripts are stateful and two-way. Bisync distinguishes a new file from a
    # deletion by comparing both sides with its saved listings from the last run.
    Ensure-BisyncInitialized
    Invoke-Rclone -Arguments (New-BisyncArguments) | Out-Null
}

if (-not (Get-Command rclone -ErrorAction SilentlyContinue)) {
    throw "rclone was not found on PATH. Install it or add rclone.exe to PATH."
}

New-Item -ItemType Directory -Force -Path $StateDirectory, $LocalVersions | Out-Null

# Prevent two copies of this script on the same computer from corrupting the
# same bisync state. This does not lock out collaborators on other computers.
$mutex = [Threading.Mutex]::new($false, "Local\JetScriptsRcloneSync")
$hasMutex = $false

try {
    $hasMutex = $mutex.WaitOne(0)
    if (-not $hasMutex) {
        throw "Another Jet Scripts synchronization is already running on this computer."
    }

    if ($Mode -eq "init") {
        Initialize-Bisync -PreviewAndConfirm
        exit 0
    }

    if ($Mode -eq "once") {
        Sync-Once
        Write-Host "Synchronization completed." -ForegroundColor Green
        exit 0
    }

    Write-Host "Watching for changes every $IntervalSeconds seconds. Press Ctrl+C to stop." -ForegroundColor Cyan
    while ($true) {
        try {
            Sync-Once
        }
        catch {
            # Offline/network failures leave the local working copy intact. A
            # later pass retries, while --resilient/--recover protect bisync state.
            Write-Warning $_.Exception.Message
        }
        Start-Sleep -Seconds $IntervalSeconds
    }
}
finally {
    if ($hasMutex) {
        $mutex.ReleaseMutex()
    }
    $mutex.Dispose()
}
