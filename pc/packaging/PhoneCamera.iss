; 电脑端安装包脚本。
; 先 `pyinstaller PhoneCamera.spec` 出 dist\PhoneCamera.exe，再 `ISCC packaging\PhoneCamera.iss`。
; 这里只改打包用的产品名；界面里的应用名仍是「手机摄像头桥接」/ Phone Camera Bridge。

#define MyAppName "PhoneCamera"
#define MyAppVersion "0.1.0"
#define MyAppExeName "PhoneCamera.exe"

[Setup]
; 这个 GUID 一旦发布就不要改：Inno 靠它识别「同一款程序」来做升级和卸载
AppId={{047e661d-796d-45e2-b163-c677648ed5cc}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppName}
; 装到当前用户的 AppData，不需要管理员权限，UAC 也不会弹窗
DefaultDirName={localappdata}\Programs\{#MyAppName}
DisableProgramGroupPage=yes
LicenseFile=..\..\LICENSE
OutputDir=..\installer
OutputBaseFilename={#MyAppName}-{#MyAppVersion}-setup
Compression=lzma2/max
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=lowest
; 标题栏深色模式要 Windows 10 1809 以上的 DWM 属性
MinVersion=10.0.17763
SetupIconFile=..\assets\app_icon.ico
UninstallDisplayName={#MyAppName}
WizardStyle=modern

[Languages]
; Inno Setup 官方只自带英文，简体中文的 .isl 属于第三方翻译包，需要另外放进来
; 才能加一行 Name: "chinesesimplified"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "..\dist\{#MyAppExeName}"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{userprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{userdesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent
