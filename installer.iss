; Inno Setup script for AudioCast
; Download Inno Setup from https://jrsoftware.org/isdl.php

#define MyAppName "AudioCast"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "AudioCast"
#define MyAppExeName "AudioCast.exe"

[Setup]
AppId={{A3D1C8F0-7E2B-4A5D-9F1E-6B8C3D4E5F60}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputDir=installer_output
OutputBaseFilename=AudioCast-Setup-{#MyAppVersion}
SetupIconFile=audiocast.ico
UninstallDisplayIcon={app}\AudioCast.exe
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=lowest

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
Name: "startupicon"; Description: "Start AudioCast on Windows startup"; GroupDescription: "Other:"; Flags: unchecked

[Files]
; GUI executable (built by PyInstaller)
Source: "dist\AudioCast.exe"; DestDir: "{app}"; Flags: ignoreversion

; Server executable (built by Cargo)
Source: "server\target\release\audiocast-server.exe"; DestDir: "{app}"; Flags: ignoreversion

; Icon
Source: "audiocast.ico"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\audiocast.ico"
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\audiocast.ico"; Tasks: desktopicon

[Registry]
; Optional startup entry
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "AudioCast"; ValueData: """{app}\{#MyAppExeName}"""; Flags: uninsdeletevalue; Tasks: startupicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "taskkill"; Parameters: "/F /IM AudioCast.exe"; Flags: runhidden; RunOnceId: "KillGUI"
Filename: "taskkill"; Parameters: "/F /IM audiocast-server.exe"; Flags: runhidden; RunOnceId: "KillServer"

[Code]
// Add Windows Firewall rule during install for LAN streaming
procedure CurStepChanged(CurStep: TSetupStep);
var
  ResultCode: Integer;
begin
  if CurStep = ssPostInstall then
  begin
    Exec('netsh', 'advfirewall firewall add rule name="AudioCast Server" dir=in action=allow program="' + ExpandConstant('{app}\audiocast-server.exe') + '" enable=yes profile=private', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    Exec('netsh', 'advfirewall firewall add rule name="AudioCast Server UDP" dir=in action=allow program="' + ExpandConstant('{app}\audiocast-server.exe') + '" enable=yes profile=private protocol=udp', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  end;
end;

// Remove firewall rule on uninstall
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  ResultCode: Integer;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    Exec('netsh', 'advfirewall firewall delete rule name="AudioCast Server"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    Exec('netsh', 'advfirewall firewall delete rule name="AudioCast Server UDP"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  end;
end;
