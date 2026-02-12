#define MyAppName "FinderX"
#define MyAppPublisher "Eministar"
#define MyAppURL "https://github.com/Eministar/finderX"
#ifndef MyAppVersion
  #define MyAppVersion "0.1.0"
#endif
#ifndef AppImageDir
  #define AppImageDir "..\\dist\\FinderX"
#endif

#ifexist "src\main\resources\icons\app.ico"
  #define MyAppIcon "src\main\resources\icons\app.ico"
#endif
#ifexist "installer\assets\wizard.bmp"
  #define MyWizardImage "installer\assets\wizard.bmp"
#endif
#ifexist "installer\assets\wizard-small.bmp"
  #define MyWizardSmallImage "installer\assets\wizard-small.bmp"
#endif

[Setup]
AppId={{D9D5A0F7-FA3B-4C86-9AE8-1E6B86C74474}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
AppVerName={#MyAppName} {#MyAppVersion}
VersionInfoVersion={#MyAppVersion}
VersionInfoProductName={#MyAppName}
VersionInfoCompany={#MyAppPublisher}
VersionInfoDescription={#MyAppName} Installer
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputDir=..\\dist
OutputBaseFilename={#MyAppName}-Setup-{#MyAppVersion}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
WizardResizable=no
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\FinderX.exe
ChangesAssociations=yes
DisableReadyMemo=no
ShowLanguageDialog=auto

#ifdef MyAppIcon
SetupIconFile={#MyAppIcon}
#endif
#ifdef MyWizardImage
WizardImageFile={#MyWizardImage}
#endif
#ifdef MyWizardSmallImage
WizardSmallImageFile={#MyWizardSmallImage}
#endif

[Languages]
Name: "german"; MessagesFile: "compiler:Languages\German.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[CustomMessages]
english.DesktopIcon=Create desktop shortcut
english.ExtraShortcuts=Additional shortcuts:
english.ReadyHeader=Ready to install FinderX
english.ReadyBody=Setup will now install FinderX on your computer.
english.FinishRun=Launch FinderX

german.DesktopIcon=Desktop-Verknuepfung erstellen
german.ExtraShortcuts=Zusaetzliche Verknuepfungen:
german.ReadyHeader=Bereit fuer die Installation von FinderX
german.ReadyBody=Setup installiert jetzt FinderX auf diesem Computer.
german.FinishRun=FinderX starten

[Tasks]
Name: "desktopicon"; Description: "{cm:DesktopIcon}"; GroupDescription: "{cm:ExtraShortcuts}"; Flags: unchecked

[Files]
Source: "{#AppImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\FinderX.exe"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\FinderX.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\FinderX.exe"; Description: "{cm:FinishRun}"; Flags: nowait postinstall skipifsilent

[Code]
procedure InitializeWizard;
begin
  WizardForm.WelcomeLabel1.Caption := ExpandConstant('{#MyAppName} Setup');
  WizardForm.WelcomeLabel2.Caption :=
    'Install a fast, clean desktop search experience for Windows.' + #13#10 +
    'This installer will guide you through the setup.';

  WizardForm.FinishedHeadingLabel.Caption := ExpandConstant('{#MyAppName} installation complete');
  WizardForm.FinishedLabel.Caption :=
    'FinderX was installed successfully and is ready to use.';
end;

