; PhoneCam OBS plugin installer for Windows x64 (Inno Setup 6.3+). Test build until a real Windows PC, phone and
; OBS have proven it (docs/WINDOWS.md). Built by: python tools/build_windows.py --package
; which passes AppVersion, NumericVersion, StageDir and OutputDir.
;
; Installs for every user in OBS 30+'s plugin folder, the same layout the Windows test build documents:
;   C:\ProgramData\obs-studio\plugins\phonecam-obs\bin\64bit\phonecam-obs.dll
;   C:\ProgramData\obs-studio\plugins\phonecam-obs\data\  (licences and notices)
; OBS loads <folder>\bin\64bit\<folder>.dll, so the folder is named after the DLL. adb is not bundled.

#ifndef AppVersion
  #error Build with tools/build_windows.py --package (AppVersion not defined)
#endif

[Setup]
AppId={{860C9FC6-0B67-44C3-84BE-C25412136D09}
AppName=PhoneCam OBS plugin
AppVersion={#AppVersion}
AppVerName=PhoneCam OBS plugin {#AppVersion}
AppPublisher=PhoneCam OBS
AppPublisherURL=https://github.com/VaraKare/phonecam-obs
AppSupportURL=https://github.com/VaraKare/phonecam-obs
VersionInfoVersion={#NumericVersion}
DefaultDirName={commonappdata}\obs-studio\plugins\phonecam-obs
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=no
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir={#OutputDir}
OutputBaseFilename=PhoneCam-OBS-plugin-windows-x64-{#AppVersion}-setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
; Asks to close OBS (Restart Manager) when it has the plugin loaded during an update.
CloseApplications=yes
RestartApplications=no
UninstallDisplayName=PhoneCam OBS plugin
UninstallFilesDir={app}\uninstall
SetupLogging=yes

[Files]
Source: "{#StageDir}\bin\64bit\phonecam-obs.dll"; DestDir: "{app}\bin\64bit"; Flags: ignoreversion
Source: "{#StageDir}\data\*"; DestDir: "{app}\data"; Flags: ignoreversion recursesubdirs

[InstallDelete]
; Files an earlier version (or a copy made by hand) left behind.
Type: filesandordirs; Name: "{app}\bin"
Type: filesandordirs; Name: "{app}\data"

[UninstallDelete]
Type: dirifempty; Name: "{app}\bin\64bit"
Type: dirifempty; Name: "{app}\bin"
Type: dirifempty; Name: "{app}\data"
Type: dirifempty; Name: "{app}"

[Messages]
WelcomeLabel2=This installs the PhoneCam plugin for OBS Studio 30 or later (64-bit) for all users of this PC.%n%nQuit OBS before you continue.
FinishedHeadingLabel=PhoneCam is installed in OBS
FinishedLabelNoIcons=PhoneCam also needs Android platform-tools (adb), a free tool from Google. It is not included.%n%nDownload "SDK Platform-Tools for Windows" from developer.android.com/tools/releases/platform-tools and unzip it so that adb.exe is at C:\platform-tools\adb.exe (or put its folder on PATH, or type its full path in the source's adb.exe path setting).%n%nThen open OBS and choose Sources > + > PhoneCam (phone camera).%n%nTo remove PhoneCam: Settings > Apps > Installed apps > PhoneCam OBS plugin > Uninstall.
FinishedLabel=PhoneCam also needs Android platform-tools (adb), a free tool from Google. It is not included.%n%nDownload "SDK Platform-Tools for Windows" from developer.android.com/tools/releases/platform-tools and unzip it so that adb.exe is at C:\platform-tools\adb.exe (or put its folder on PATH, or type its full path in the source's adb.exe path setting).%n%nThen open OBS and choose Sources > + > PhoneCam (phone camera).
