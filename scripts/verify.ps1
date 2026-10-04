<#
.SYNOPSIS
    Single verification entry point for LanChat.

.DESCRIPTION
    Runs the same checks locally, in the pre-commit hook, and in CI, so a green
    local run means a green CI run. Fails on:
      - any unit test failure
      - lint errors, or new lint findings not covered by app\lint.xml
      - stray CJK / replacement characters in source or docs
      - committed secrets (keystores, archives, local.properties)

    PowerShell is used rather than bash because the git hooks environment on
    Windows has no usable POSIX shell, and PowerShell is present on the CI
    runners as well, which keeps this one script for all three callers.

.PARAMETER Quick
    Skip lint and assemble. Used by the pre-commit hook for a fast loop.
#>
[CmdletBinding()]
param([switch]$Quick)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

function Write-Step($msg) {
    Write-Host ''
    Write-Host "=== $msg ==="
}

function Fail($msg) {
    Write-Host ''
    Write-Host "FAILED: $msg" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------------------
Write-Step 'Stray characters (CJK / replacement)'
# A CJK character inside an Arabic comment means text was pasted from a tool that
# mixed scripts. It has happened repeatedly in this codebase, so it is a hard gate
# rather than a style preference.
# PCRE2 has no \uXXXX escape, so the ranges are written as literal UTF-8 bytes:
#   \x{3040}-\x{30FF}  Hiragana + Katakana
#   \x{3400}-\x{9FFF}  CJK Unified Ideographs (also catches U+FFFD, the
#                      replacement character, which lives past this range)
$pattern = '[\x{3040}-\x{30FF}\x{3400}-\x{9FFF}\x{FFFD}]'
$stray = & git grep -n -P $pattern -- '*.kt' '*.md' '*.xml' '*.kts' 2>$null
if ($stray) {
    $stray | ForEach-Object { Write-Host $_ }
    Fail 'stray CJK or replacement characters found (see above)'
}
Write-Host 'clean'

# ---------------------------------------------------------------------------
Write-Step 'Secrets and machine-local files'
$forbidden = & git ls-files | Where-Object { $_ -match '\.(keystore|jks|apk|aab)$' }
if ($forbidden) {
    Write-Host 'committed binary/secret files:' -ForegroundColor Red
    $forbidden | ForEach-Object { Write-Host "  $_" }
    Fail 'remove these from the repository'
}
$tracked = & git ls-files
if ($tracked -contains 'local.properties') {
    Fail 'local.properties is committed; it must stay untracked'
}
Write-Host 'clean'

# ---------------------------------------------------------------------------
Write-Step 'Unit tests'
& .\gradlew.bat --no-daemon :app:testDebugUnitTest
if ($LASTEXITCODE -ne 0) { Fail 'unit tests' }

# ---------------------------------------------------------------------------
if (-not $Quick) {
    Write-Step 'Lint'
    & .\gradlew.bat --no-daemon :app:lintDebug
    if ($LASTEXITCODE -ne 0) { Fail 'lint' }

    Write-Step 'Assemble debug'
    & .\gradlew.bat --no-daemon :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { Fail 'assembleDebug' }
}

Write-Host ''
Write-Host '=== ALL CHECKS PASSED ===' -ForegroundColor Green