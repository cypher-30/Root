[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9_.:-]+$')]
    [string] $Serial,
    [ValidatePattern('^[A-Za-z0-9_.$,#]*$')]
    [string] $Classes = '',
    [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Push-Location $repo
try {
    $sdk = $env:ANDROID_HOME
    if (!$sdk) {
        $line = Get-Content local.properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if (!$line) { throw 'Set ANDROID_HOME or sdk.dir in local.properties.' }
        $sdk = $line.Substring('sdk.dir='.Length).Replace('\:', ':').Replace('\\', '\')
    }
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    $aapt = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending |
        ForEach-Object { Join-Path $_.FullName 'aapt.exe' } |
        Where-Object { Test-Path $_ } | Select-Object -First 1
    if (!(Test-Path $adb) -or !$aapt) { throw 'Android platform-tools and build-tools are required.' }
    $state = & $adb -s $Serial get-state
    if ($LASTEXITCODE -ne 0 -or $state -ne 'device') { throw 'The selected device is not authorized and online.' }
    $user = & $adb -s $Serial shell am get-current-user
    if ($LASTEXITCODE -ne 0 -or $user.Trim() -ne '0') {
        throw 'Validation requires foreground user 0; this script will not switch device profiles.'
    }
    # OEM screensavers/Dream services (e.g. Samsung "Home Mode") can steal window focus mid-run
    # and produce intermittent "No compose hierarchies found in the app" failures. Disable them
    # for the duration of validation so results are deterministic; this only touches the
    # screensaver/Dream setting, never app data.
    foreach ($setting in @('screensaver_enabled', 'screensaver_activate_on_sleep', 'screensaver_activate_on_dock')) {
        & $adb -s $Serial shell settings put secure $setting 0 | Out-Null
    }
    if (!$SkipBuild) {
        & .\gradlew.bat -ProotTestBuildType=validation :app:assembleValidation :app:assembleValidationAndroidTest --console=plain --quiet
        if ($LASTEXITCODE -ne 0) { throw 'Validation APK build failed.' }
    }
    $app = 'app\build\outputs\apk\validation\app-validation.apk'
    $tests = 'app\build\outputs\apk\androidTest\validation\app-validation-androidTest.apk'
    foreach ($entry in @(@($app, 'com.root.app.validation'), @($tests, 'com.root.app.validation.test'))) {
        $metadata = & $aapt dump badging $entry[0]
        if ($LASTEXITCODE -ne 0 -or !($metadata -match ("^package: name='" + [regex]::Escape($entry[1]) + "'"))) {
            throw "Refusing to install an APK with an unexpected package: $($entry[0])"
        }
    }
    $manifest = & $aapt dump xmltree $tests AndroidManifest.xml
    if ($LASTEXITCODE -ne 0 -or !($manifest -match 'android:targetPackage.*="com\.root\.app\.validation"') -or
        !($manifest -match 'android:name.*="androidx\.test\.runner\.AndroidJUnitRunner"')) {
        throw 'Refusing to run an unexpected instrumentation target.'
    }
    foreach ($apk in @($app, $tests)) {
        & $adb -s $Serial install --user 0 -r $apk
        if ($LASTEXITCODE -ne 0) { throw "Installation failed: $apk" }
    }
    $arguments = @('-s', $Serial, 'shell', 'am', 'instrument', '--user', '0', '-w')
    if ($Classes) { $arguments += @('-e', 'class', $Classes) }
    $arguments += 'com.root.app.validation.test/androidx.test.runner.AndroidJUnitRunner'
    $reportDirectory = Join-Path 'app\build\reports\validation-device' $Serial.Replace(':', '_')
    New-Item -ItemType Directory -Force $reportDirectory | Out-Null
    $report = Join-Path $reportDirectory ("instrumentation-" + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.txt')
    $output = & $adb @arguments 2>&1
    $exitCode = $LASTEXITCODE
    $output | Set-Content $report
    $output | Write-Output
    # adb can return zero even when the runner reports failing tests or crashes.
    if ($exitCode -ne 0 -or !(($output -join "`n") -match '(?m)^OK \([1-9]\d* tests?\)\s*$')) {
        throw "Instrumentation did not pass. Report: $report"
    }
    Write-Output "Selected device: $Serial. Report: $report"
} finally {
    Pop-Location
}
