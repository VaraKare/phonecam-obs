# PhoneCam OBS - portable Windows test kit (docs/WINDOWS_TEST.md). Installs nothing and changes no settings:
# OBS runs in portable mode from one Desktop folder, which is deleted again when OBS closes, together with
# anything adb created. Built by tools/windows_kit.py; __URL__ and __WIFI__ are filled in there.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$Url = '__URL__'
$Wifi = __WIFI__
$Started = Get-Date
$Dir = Join-Path ([Environment]::GetFolderPath('Desktop')) 'PhoneCam-Test'
$Zip = Join-Path $env:TEMP 'PhoneCam-Test.zip'
$Tmp = Join-Path $Dir 'tmp'
$AndroidKeys = Join-Path $env:USERPROFILE '.android'
$HadAndroidKeys = Test-Path $AndroidKeys
$Pairings = Join-Path $env:APPDATA 'phonecam-obs'
$HadPairings = Test-Path $Pairings
$OriginalPath = $env:PATH
$script:AdbPath = Join-Path $Dir 'platform-tools\adb.exe'
$script:Calls = 0

# Settings from an earlier run in this same window must not carry over; all of these live only in this
# window and the OBS it starts.
foreach ($v in 'ADB_TRACE', 'ADB_USB_LEGACY', 'ADB_LIBUSB', 'ADB_MDNS', 'ANDROID_ADB_LOG_PATH', 'ANDROID_ADB_SERVER_PORT', 'PHONECAM_WIFI_STORE') {
    Remove-Item "Env:$v" -ErrorAction SilentlyContinue
}
$env:ANDROID_ADB_SERVER_PORT = '5038'                        # our own adb server, apart from any other adb here
$env:ADB_MDNS = '0'                                          # USB only: adb opens no network ports
$env:PHONECAM_WIFI_STORE = Join-Path $Dir 'wifi-pairings.json' # Wi-Fi pairings stay inside the kit folder

function Send([string]$Name, [string]$Text) {
    try { Invoke-RestMethod -UseBasicParsing -Method Post -Uri "$Url/report/$Name" -Body ([Text.Encoding]::UTF8.GetBytes($Text)) -ContentType 'text/plain; charset=utf-8' -TimeoutSec 15 | Out-Null } catch { }
}
function Say([string]$Text, [string]$Color = 'White') { Write-Host $Text -ForegroundColor $Color }
# Every adb call has a time limit and writes to files, never a pipe, so nothing can hang the kit.
function Adb {
    New-Item -ItemType Directory -Force $Tmp | Out-Null
    $script:Calls++
    $out = Join-Path $Tmp "adb$($script:Calls).txt"; $err = Join-Path $Tmp "adb$($script:Calls)-err.txt"
    $line = ($args | ForEach-Object { "$_" }) -join ' '
    $p = Start-Process -FilePath $script:AdbPath -ArgumentList $line -NoNewWindow -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
    if (-not $p.WaitForExit(10000)) { try { $p.Kill() } catch { }; "(adb $line timed out)" }
    Get-Content $out, $err -ErrorAction SilentlyContinue
}
function Listening { [bool](Get-NetTCPConnection -State Listen -LocalPort 5038 -ErrorAction SilentlyContinue) }
function StopKitProcesses {
    Get-Process obs64, adb -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$Dir*" } | Stop-Process -Force -ErrorAction SilentlyContinue
}

try {
    Say "`nPhoneCam test kit. Nothing is installed; everything is removed when you close OBS.`n" Cyan
    StopKitProcesses
    if (Test-Path $Dir) { Start-Sleep 1; Remove-Item -Recurse -Force $Dir }
    Say "1/4  Downloading the kit (about 240 MB)..."
    Invoke-WebRequest -UseBasicParsing "$Url/kit.zip" -OutFile $Zip
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory($Zip, $Dir)
    Remove-Item -Force $Zip

    $os = Get-CimInstance Win32_OperatingSystem
    $gpu = (Get-CimInstance Win32_VideoController | ForEach-Object { "$($_.Name) (driver $($_.DriverVersion))" }) -join '; '
    $cpu = (Get-CimInstance Win32_Processor | Select-Object -First 1).Name
    $av = (Get-CimInstance -Namespace root/SecurityCenter2 -ClassName AntivirusProduct -ErrorAction SilentlyContinue | ForEach-Object displayName) -join ', '
    Send 'system' "Windows: $($os.Caption) $($os.Version) build $($os.BuildNumber)`nCPU: $cpu`nGPU: $gpu`nRAM: $([math]::Round($os.TotalVisibleMemorySize/1MB,1)) GB`nAntivirus: $av`nWi-Fi test: $Wifi`nAndroid keys folder existed before: $HadAndroidKeys"

    Say "2/4  Plug the phone in with the USB cable. On the phone, tick 'Always allow from this computer' and tap Allow." Yellow
    # adb's server in the foreground mode (antivirus such as Avast can block its background start); if it
    # does not open its port, the other Windows USB methods.
    New-Item -ItemType Directory -Force $Tmp | Out-Null
    $up = $false
    foreach ($mode in @(@{ Name = 'default USB' }, @{ Name = 'legacy USB'; Legacy = '1'; Libusb = '0' }, @{ Name = 'libusb'; Libusb = '1' })) {
        $env:ADB_USB_LEGACY = $mode.Legacy; $env:ADB_LIBUSB = $mode.Libusb
        $tag = $mode.Name -replace ' ', '-'
        Start-Process -FilePath $script:AdbPath -ArgumentList '-L', 'tcp:5038', 'server', 'nodaemon' -NoNewWindow `
            -RedirectStandardOutput (Join-Path $Tmp "server-$tag.txt") -RedirectStandardError (Join-Path $Tmp "server-$tag-err.txt") | Out-Null
        for ($t = 0; $t -lt 15 -and -not $up; $t++) { Start-Sleep 1; $up = Listening }
        if ($up) { Send 'adb-server' "adb server running ($($mode.Name))"; break }
        StopKitProcesses; Start-Sleep 1
    }
    if (-not $up) {
        Say "     adb could not start on this computer; the details went to the Mac." Red
        Send 'adb-server' ("adb server did not start.`n" + ((Get-ChildItem $Tmp -Filter 'server-*' | ForEach-Object { "$($_.Name):`n" + ((Get-Content $_.FullName -Tail 20) -join "`n") }) -join "`n"))
    }
    $state = 'none'; $until = (Get-Date).AddMinutes(3); $i = 0
    while ($up -and (Get-Date) -lt $until -and $state -ne 'device') {
        $line = Adb devices | Where-Object { $_ -match "`t" } | Select-Object -First 1
        $state = if ($line) { ($line -split "`t")[1].Trim() } else { 'none' }
        if ($i++ % 3 -eq 0) { Say "     Phone: $state" }
        if ($state -ne 'device') { Start-Sleep 3 }
    }
    $usb = (Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | Where-Object { $_.FriendlyName -match 'ADB|Android' } | ForEach-Object { "$($_.FriendlyName) [$($_.Status)]" }) -join '; '
    $app = (Adb shell dumpsys package dev.phonecam.obs | Select-String 'versionName') -join ''
    Send 'adb' ("Phone: $state`nUSB devices: $usb`nPhoneCam app: $app`n" + ((Adb devices -l) -join "`n"))
    if ($state -eq 'device') { Say "     Phone connected." Green }
    else { Say "     The phone isn't connected yet ($state). OBS keeps looking; check the cable and the Allow prompt." Yellow }

    Say "3/4  Starting OBS (portable, with the PhoneCam source ready)..."
    $env:PATH = (Split-Path $script:AdbPath) + ';' + $OriginalPath   # the plugin finds this adb.exe
    $bin = Join-Path $Dir 'OBS\bin\64bit'
    $obs = Start-Process -FilePath (Join-Path $bin 'obs64.exe') -ArgumentList '--portable', '--disable-updater', '--disable-missing-files-check' -WorkingDirectory $bin -PassThru
    Say "`n     On the phone: Rear > High > Start live. Follow the checklist you were given." Yellow
    if ($Wifi) { Say "     Wi-Fi test: if Windows Firewall asks about OBS, allow it on private networks (Windows keeps that entry)." Yellow }
    Say "     Close OBS when you're done; this window then removes everything.`n" Yellow

    $logs = Join-Path $Dir 'OBS\config\obs-studio\logs'
    $sent = ''
    while (-not $obs.HasExited) {
        Start-Sleep 5
        $log = Get-ChildItem $logs -Filter *.txt -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1
        if (-not $log) { continue }
        $lines = (Get-Content $log.FullName -ErrorAction SilentlyContinue | Select-String -Pattern 'phonecam' | ForEach-Object { $_.Line }) -join "`n"
        if ($lines -ne $sent) { Send 'live' $lines; $sent = $lines; Send 'phone-live' ((Adb logcat -d -t 80 -s PhoneCam:V) -join "`n") }
    }
    Say "4/4  OBS closed. Sending the final logs..."
    $log = Get-ChildItem $logs -Filter *.txt -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1
    if ($log) { Send 'obs-log' (Get-Content $log.FullName -Raw) }
    Send 'phone-log' ((Adb logcat -d -s PhoneCam:V) -join "`n")
}
catch {
    Say "Problem: $($_.Exception.Message)" Red
    Send 'error' ($_ | Out-String)
}
finally {
    Say "`nRemoving everything the kit created..."
    StopKitProcesses
    Start-Sleep 2
    $removed = @()
    foreach ($path in @($Dir, $Zip)) { if (Test-Path $path) { Remove-Item -Recurse -Force $path -ErrorAction SilentlyContinue } }
    # adb's key folder and the plugin's pairings folder: removed if the kit created them, otherwise only
    # the files created during this run.
    foreach ($pair in @(@($AndroidKeys, $HadAndroidKeys), @($Pairings, $HadPairings))) {
        $folder = $pair[0]
        if (-not (Test-Path $folder)) { continue }
        if (-not $pair[1]) { Remove-Item -Recurse -Force $folder -ErrorAction SilentlyContinue; $removed += $folder; continue }
        Get-ChildItem -Force $folder -ErrorAction SilentlyContinue | Where-Object { $_.CreationTime -ge $Started } |
            ForEach-Object { Remove-Item -Recurse -Force $_.FullName -ErrorAction SilentlyContinue; $removed += $_.FullName }
    }
    Get-ChildItem $env:TEMP -Filter 'adb*.log' -ErrorAction SilentlyContinue | Where-Object { $_.CreationTime -ge $Started } |
        ForEach-Object { Remove-Item -Force $_.FullName -ErrorAction SilentlyContinue; $removed += $_.FullName }
    foreach ($v in 'ADB_TRACE', 'ADB_USB_LEGACY', 'ADB_LIBUSB', 'ADB_MDNS', 'ANDROID_ADB_LOG_PATH', 'ANDROID_ADB_SERVER_PORT', 'PHONECAM_WIFI_STORE') {
        Remove-Item "Env:$v" -ErrorAction SilentlyContinue
    }
    $env:PATH = $OriginalPath

    $left = @(@($Dir, $Zip) | Where-Object { Test-Path $_ })
    if (-not $HadAndroidKeys -and (Test-Path $AndroidKeys)) { $left += $AndroidKeys }
    if (-not $HadPairings -and (Test-Path $Pairings)) { $left += $Pairings }
    # Windows Firewall entries for the kit's programs (made if someone answered a firewall prompt) can only be
    # removed by an administrator, so they are reported.
    $rules = @(Get-NetFirewallApplicationFilter -ErrorAction SilentlyContinue | Where-Object { $_.Program -like "*\PhoneCam-Test\*" } |
        Get-NetFirewallRule -ErrorAction SilentlyContinue | ForEach-Object { "$($_.DisplayName) ($($_.Action), $($_.Profile))" })
    if ($left.Count) { Say "Could not remove: $($left -join ', '). Close OBS and delete them by hand." Red }
    else { Say "Done. Everything the kit created has been removed; nothing was installed." Green }
    if ($rules.Count) {
        Say "Windows Firewall kept $($rules.Count) entr$(if ($rules.Count -eq 1) { 'y' } else { 'ies' }) for the test's OBS or adb." Yellow
        Say "To remove: Start > Windows Defender Firewall with Advanced Security > Inbound Rules > delete the rules for obs64.exe or adb.exe in PhoneCam-Test (needs an administrator)." Yellow
    }
    Send 'cleanup' ("Left behind: " + $(if ($left.Count) { $left -join ', ' } else { 'nothing' }) +
        "`nRemoved files created during the test in folders that already existed: " + $(if ($removed.Count) { $removed -join ', ' } else { 'none' }) +
        "`nFirewall entries for the kit: " + $(if ($rules.Count) { $rules -join '; ' } else { 'none' }))
}
