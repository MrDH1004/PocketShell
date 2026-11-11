; PocketShell one-click installer for Windows (Inno Setup 6). Built by scripts\release.ps1:
;   ISCC /DAppVersion=x.y.z /DSourceDir=<publish folder> /DOutDir=<dir> installer\PocketShell.iss
;
; - Per-user install, no admin for the app itself: %LOCALAPPDATA%\Programs\PocketShell (the folder `psh update`
;   replaces files in, so auto-updates keep working without elevation).
; - Installs the .NET 8 Desktop and ASP.NET Core runtimes when missing (downloads from Microsoft; their installers ask
;   for administrator rights).
; - Stops a running PocketShell (tray + server) before copying, then starts the tray with --pair so the PIN window
;   opens right away.
; - Uninstall removes the program, the Start menu entry and the auto-start entry; %APPDATA%\PocketShell (certificate,
;   paired phones, history, logs) is kept unless the user chooses to delete it.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef SourceDir
  #error SourceDir (publish folder) is required
#endif
#ifndef OutDir
  #define OutDir "."
#endif

[Setup]
AppId={{6B0E4C1F-6A63-4C8B-9B53-5C2E1E7A9D41}
AppName=PocketShell
AppVersion={#AppVersion}
AppVerName=PocketShell {#AppVersion}
AppPublisher=NestStudio
AppPublisherURL=https://github.com/MrDH1004/PocketShell
AppSupportURL=https://github.com/MrDH1004/PocketShell/issues
AppUpdatesURL=https://github.com/MrDH1004/PocketShell/releases
DefaultDirName={localappdata}\Programs\PocketShell
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
OutputDir={#OutDir}
OutputBaseFilename=PocketShell-Setup-v{#AppVersion}
SetupIconFile=..\assets\pocketshell.ico
UninstallDisplayIcon={app}\PocketShell.exe
UninstallDisplayName=PocketShell
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=no
RestartApplications=no

[Languages]
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"

[CustomMessages]
korean.RuntimeTitle=.NET 8 런타임 설치
korean.RuntimeDesc=PocketShell 실행에 필요한 Microsoft .NET 8 런타임을 받는 중입니다.
korean.RuntimeInstalling=.NET 8 런타임을 설치하는 중입니다… (관리자 권한 확인 창이 뜨면 예를 누르세요)
korean.RuntimeFailed=.NET 8 런타임(%1)을 설치하지 못했습니다 (코드 %2).%n%n인터넷 연결을 확인하고 설치를 다시 실행하거나, 아래 주소에서 직접 설치한 뒤 다시 실행하세요.%n%3
korean.RuntimeCancelled=.NET 8 런타임 설치를 취소했습니다. PocketShell은 이 런타임이 있어야 실행됩니다.
korean.StoppingApp=실행 중인 PocketShell을 종료하는 중입니다…
korean.ConfirmStop=PocketShell이 실행 중입니다. 설치하려면 종료해야 하고, 폰에서 열어 둔 PC 세션이 있으면 함께 끝납니다.%n%n종료하고 계속 설치할까요?
korean.InstallCancelled=설치를 취소했습니다. PocketShell은 그대로 실행 중입니다.
korean.RunAfter=PocketShell 실행하고 휴대폰 연결 (PIN) 창 열기
korean.DesktopIcon=바탕 화면에 바로 가기 만들기
korean.DeleteData=PocketShell 데이터(인증서, 연결된 폰, 세션 기록, 로그)도 지울까요?%n%n다시 설치해서 쓸 거라면 아니요를 누르세요. 지우면 폰을 다시 PIN으로 연결해야 합니다.

[Tasks]
Name: "desktopicon"; Description: "{cm:DesktopIcon}"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\PocketShell"; Filename: "{app}\PocketShell.exe"
Name: "{userdesktop}\PocketShell"; Filename: "{app}\PocketShell.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\PocketShell.exe"; Parameters: "--pair"; Description: "{cm:RunAfter}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "powershell.exe"; Parameters: "{code:StopAppParams}"; Flags: runhidden waituntilterminated; RunOnceId: "StopApp"

[UninstallDelete]
; Auto-updates add and replace files the install log does not know (newer DLLs, APKs, *.old backups): the folder is
; PocketShell's own, so remove all of it.
Type: filesandordirs; Name: "{app}"

[Code]
const
  RunKey = 'Software\Microsoft\Windows\CurrentVersion\Run';
  DesktopUrl = 'https://aka.ms/dotnet/8.0/windowsdesktop-runtime-win-x64.exe';
  AspNetUrl = 'https://aka.ms/dotnet/8.0/aspnetcore-runtime-win-x64.exe';
  ManualUrl = 'https://dotnet.microsoft.com/download/dotnet/8.0';

var
  DownloadPage: TDownloadWizardPage;
  NeedDesktop, NeedAspNet: Boolean;

{ True when %ProgramFiles%\dotnet\shared\<framework> has an 8.x version folder. }
function HasRuntime8(const Framework: String): Boolean;
var
  FindRec: TFindRec;
begin
  Result := False;
  if FindFirst(ExpandConstant('{commonpf64}\dotnet\shared\' + Framework + '\8.*'), FindRec) then
  begin
    try
      repeat
        if (FindRec.Attributes and FILE_ATTRIBUTE_DIRECTORY) <> 0 then Result := True;
      until Result or not FindNext(FindRec);
    finally
      FindClose(FindRec);
    end;
  end;
end;

procedure InitializeWizard;
begin
  { The Desktop runtime bundle also installs Microsoft.NETCore.App. }
  NeedDesktop := not (HasRuntime8('Microsoft.WindowsDesktop.App') and HasRuntime8('Microsoft.NETCore.App'));
  NeedAspNet := not HasRuntime8('Microsoft.AspNetCore.App');
#ifdef TestDownloadOnly
  { Test build (never released): download both runtimes even though they are installed, and skip running them. }
  NeedDesktop := True;
  NeedAspNet := True;
#endif
  DownloadPage := CreateDownloadPage(CustomMessage('RuntimeTitle'), CustomMessage('RuntimeDesc'), nil);
end;

{ Runs a downloaded runtime installer elevated; 0 / 3010 (reboot later) / 1638 (newer already present) are fine. }
function InstallRuntime(const FileName, Name: String): Boolean;
var
  Code: Integer;
begin
  DownloadPage.SetText(CustomMessage('RuntimeInstalling'), Name);
#ifdef TestDownloadOnly
  Result := FileExists(ExpandConstant('{tmp}\') + FileName);
  Log('TestDownloadOnly: ' + FileName + ' downloaded=' + IntToStr(Ord(Result)));
  exit;
#endif
  if not ShellExec('runas', ExpandConstant('{tmp}\') + FileName, '/install /passive /norestart', '', SW_SHOW, ewWaitUntilTerminated, Code) then
  begin
    { ShellExec failed: Code holds the Windows error (1223 = UAC declined). }
    MsgBox(FmtMessage(CustomMessage('RuntimeFailed'), [Name, IntToStr(Code), ManualUrl]), mbError, MB_OK);
    Result := False;
    exit;
  end;
  Result := (Code = 0) or (Code = 3010) or (Code = 1638);
  if not Result then
    MsgBox(FmtMessage(CustomMessage('RuntimeFailed'), [Name, IntToStr(Code), ManualUrl]), mbError, MB_OK);
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  { No welcome / folder / ready pages: the tasks page's 설치 button is the one click that starts everything. }
  if (CurPageID <> wpSelectTasks) or not (NeedDesktop or NeedAspNet) then exit;
  DownloadPage.Clear;
  if NeedDesktop then DownloadPage.Add(DesktopUrl, 'windowsdesktop-runtime-8-win-x64.exe', '');
  if NeedAspNet then DownloadPage.Add(AspNetUrl, 'aspnetcore-runtime-8-win-x64.exe', '');
  DownloadPage.Show;
  try
    try
      DownloadPage.Download;
      if NeedDesktop then
      begin
        if not InstallRuntime('windowsdesktop-runtime-8-win-x64.exe', '.NET 8 Desktop Runtime') then begin Result := False; exit; end;
        NeedDesktop := False;
      end;
      if NeedAspNet then
      begin
        if not InstallRuntime('aspnetcore-runtime-8-win-x64.exe', 'ASP.NET Core 8 Runtime') then begin Result := False; exit; end;
        NeedAspNet := False;
      end;
    except
      if DownloadPage.AbortedByUser then
        MsgBox(CustomMessage('RuntimeCancelled'), mbInformation, MB_OK)
      else
        MsgBox(FmtMessage(CustomMessage('RuntimeFailed'), ['.NET 8', GetExceptionMessage, ManualUrl]), mbError, MB_OK);
      Result := False;
    end;
  finally
    DownloadPage.Hide;
  end;
end;

{ PowerShell arguments that stop the tray and server (and psh attach windows) started from this install folder only. }
function StopAppParams(Param: String): String;
begin
  Result := '-NoProfile -ExecutionPolicy Bypass -Command "Get-Process PocketShell,psh -ErrorAction SilentlyContinue | ' +
    'Where-Object { $_.Path -like ''' + ExpandConstant('{app}') + '\*'' } | Stop-Process -Force -ErrorAction SilentlyContinue"';
end;

{ Program files are locked while the tray / server run: stop them first (an update over an existing install). }
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Code, Running: Integer;
begin
  Result := '';
  { Exit code = number of PocketShell processes running from the install folder. }
  Running := 0;
  Exec('powershell.exe', '-NoProfile -ExecutionPolicy Bypass -Command "exit @(Get-Process PocketShell,psh -ErrorAction SilentlyContinue | ' +
    'Where-Object { $_.Path -like ''' + ExpandConstant('{app}') + '\*'' }).Count"', '', SW_HIDE, ewWaitUntilTerminated, Running);
  if (Running > 0) and not WizardSilent then
    if MsgBox(CustomMessage('ConfirmStop'), mbConfirmation, MB_OKCANCEL) <> IDOK then
    begin
      Result := CustomMessage('InstallCancelled');
      exit;
    end;
  WizardForm.StatusLabel.Caption := CustomMessage('StoppingApp');
  Exec('powershell.exe', StopAppParams(''), '', SW_HIDE, ewWaitUntilTerminated, Code);
  Sleep(800);
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  AutoStart: String;
begin
  { The tray adds the auto-start entry on first run. Remove it only when it points at this install. }
  if CurUninstallStep = usUninstall then
    if RegQueryStringValue(HKCU, RunKey, 'PocketShell', AutoStart) and
       (Pos(Lowercase(ExpandConstant('{app}\')), Lowercase(AutoStart)) > 0) then
      RegDeleteValue(HKCU, RunKey, 'PocketShell');
  if (CurUninstallStep = usPostUninstall) and not UninstallSilent then
    if MsgBox(CustomMessage('DeleteData'), mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
      DelTree(ExpandConstant('{userappdata}\PocketShell'), True, True, True);
end;
