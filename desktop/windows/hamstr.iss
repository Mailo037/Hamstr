; Hamstr's Windows setup (Inno Setup 6), built by `gradlew :desktop:packageWindowsSetup` from the
; app folder Compose makes (createDistributable). It installs for the signed-in user into
; %LOCALAPPDATA%\Programs\Hamstr: no admin rights, no Windows Installer, no restart. Hamstr's
; updater runs it with /VERYSILENT /OUTOFJOB and opens Hamstr again afterwards.
;
; Gradle passes Version, NumericVersion, Source (the app folder) and Output.

#define AppGuid "{5B0D3C6E-7F2A-4E1B-9A8D-3C4F5E6A7B80}"
#define UninstallKey "Software\Microsoft\Windows\CurrentVersion\Uninstall\" + AppGuid + "_is1"

[Setup]
AppId={#StringChange(AppGuid, "{", "{{")}
AppName=Hamstr
AppVersion={#Version}
AppVerName=Hamstr {#Version}
AppPublisher=Hamstr
AppPublisherURL=https://github.com/Mailo037/Hamstr
AppSupportURL=https://github.com/Mailo037/Hamstr/issues
AppUpdatesURL=https://github.com/Mailo037/Hamstr/releases
VersionInfoVersion={#NumericVersion}
VersionInfoDescription=Hamstr Setup
VersionInfoProductName=Hamstr
; For this user only, so nothing asks for an administrator.
PrivilegesRequired=lowest
DefaultDirName={autopf}\Hamstr
DisableDirPage=auto
DisableProgramGroupPage=yes
DisableReadyPage=yes
WizardStyle=modern
WizardImageFile=setup_side_1x.bmp,setup_side_2x.bmp
WizardSmallImageFile=setup_small_1x.bmp,setup_small_2x.bmp
SetupIconFile=..\icons\hamstr.ico
UninstallDisplayIcon={app}\Hamstr.exe
UninstallDisplayName=Hamstr
OutputDir={#Output}
OutputBaseFilename=Hamstr-{#Version}
Compression=lzma2/normal
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; Hamstr is closed before anything is replaced (PrepareToInstall), so nothing waits for a restart.
CloseApplications=force
RestartApplications=no
SetupMutex=HamstrSetup

[Files]
Source: "{#Source}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[InstallDelete]
; The old version's libraries, so none of them stays behind beside the new ones.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Icons]
Name: "{autoprograms}\Hamstr"; Filename: "{app}\Hamstr.exe"
; On the desktop once; an update doesn't bring it back after it was deleted.
Name: "{autodesktop}\Hamstr"; Filename: "{app}\Hamstr.exe"; Check: FirstInstall

[Run]
Filename: "{app}\Hamstr.exe"; Description: "{cm:LaunchProgram,Hamstr}"; Flags: nowait postinstall skipifsilent

[Code]
var
  First: Boolean;

function IsProcessInJob(Process: THandle; Job: THandle; var InJob: LongBool): LongBool;
  external 'IsProcessInJob@kernel32.dll stdcall';
function GetCurrentProcess(): THandle;
  external 'GetCurrentProcess@kernel32.dll stdcall';

function FirstInstall(): Boolean;
begin
  Result := First;
end;

{ The setup again, as it was started, plus /OUTOFJOB. }
function OwnCommandLine(): String;
var
  I: Integer;
  P: String;
begin
  Result := '"' + ExpandConstant('{srcexe}') + '"';
  for I := 1 to ParamCount do
  begin
    P := ParamStr(I);
    if (Pos(' ', P) > 0) and (Pos('"', P) = 0) then P := '"' + P + '"';
    Result := Result + ' ' + P;
  end;
  Result := Result + ' /OUTOFJOB';
end;

{ Started by Hamstr 0.7.x's updater, the setup sits in the job of Hamstr's launcher, which ends
  every process in it once Hamstr quits, this setup included. WMI starts it again outside. }
function LeftJob(): Boolean;
var
  InJob: LongBool;
  Locator, Service: Variant;
  I: Integer;
begin
  Result := False;
  { Started outside already: by this function, or by the updater's helper. }
  for I := 1 to ParamCount do
    if CompareText(ParamStr(I), '/OUTOFJOB') = 0 then Exit;
  if not IsProcessInJob(GetCurrentProcess(), 0, InJob) or not InJob then Exit;
  try
    Locator := CreateOleObject('WbemScripting.SWbemLocator');
    Service := Locator.ConnectServer('.', 'root\cimv2');
    Result := Service.Get('Win32_Process').Create(OwnCommandLine()) = 0;
  except
    Result := False;
  end;
end;

function InitializeSetup(): Boolean;
begin
  First := not RegKeyExists(HKCU, '{#UninstallKey}');
  Result := not LeftJob();
end;

procedure CloseHamstr();
var
  Code: Integer;
begin
  { In the tray Hamstr has no window to close, so it is ended. }
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM Hamstr.exe', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Sleep(500);
end;

{ The product code of a Hamstr 0.7.0-0.7.2, which came as an MSI for all users. }
function OldMsi(): String;
var
  Keys: TArrayOfString;
  I: Integer;
  Name: String;
  Msi: Cardinal;
  Base: String;
begin
  Result := '';
  Base := 'Software\Microsoft\Windows\CurrentVersion\Uninstall\';
  if not RegGetSubkeyNames(HKLM64, Base, Keys) then Exit;
  for I := 0 to GetArrayLength(Keys) - 1 do
    if RegQueryStringValue(HKLM64, Base + Keys[I], 'DisplayName', Name) and (Name = 'Hamstr')
      and RegQueryDWordValue(HKLM64, Base + Keys[I], 'WindowsInstaller', Msi) and (Msi = 1) then
    begin
      Result := Keys[I];
      Exit;
    end;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Code: String;
  Done: Integer;
begin
  Result := '';
  CloseHamstr();
  { Two Hamstrs would show up in Start; the old one goes, with Windows' permission once.
    MSIFASTINSTALL skips the restore point that made removing it slow. }
  Code := OldMsi();
  if Code <> '' then
  begin
    SuppressibleMsgBox('Hamstr from an earlier version is still installed for all users. Windows asks once for permission to remove it; this Hamstr replaces it.',
      mbInformation, MB_OK, IDOK);
    ShellExec('runas', 'msiexec.exe', '/x ' + Code + ' /qn /norestart MSIFASTINSTALL=7', '', SW_HIDE, ewWaitUntilTerminated, Done);
  end;
end;

function InitializeUninstall(): Boolean;
begin
  CloseHamstr();
  Result := True;
end;

procedure CurUninstallStepChanged(Step: TUninstallStep);
begin
  { "Start with this computer" leaves an entry that would point at nothing. }
  if Step = usPostUninstall then
    RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'Hamstr');
end;
