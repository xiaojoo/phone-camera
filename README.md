# Phone Camera — 把手机当成电脑摄像头

手机侧用 CameraX 取流、编码成 JPEG，通过一个手写的 MJPEG HTTP 服务推出去；
电脑侧用 OpenCV 拉流并显示，支持 **Wi-Fi** 和 **USB 数据线** 两种连接方式，
界面中文/英文可切换。

项目还在早期阶段：没有音频、没有鉴权、没有多机位。

不想自己构建的话，现成的包在 **[Releases](https://github.com/xiaojoo/phone-camera/releases/latest)**：
电脑端下 `PhoneCamera-<版本>-setup.exe`（当前 0.1.1，装到当前用户，不需要管理员权限），
手机端下 `PhoneCamera-<版本>-release.apk`（Android 7.0+）。两个都没做代码签名，
Release 说明里带各自的 SHA-256，下载后对一下再用。

---

## 工作原理

```
手机 CameraX (Preview + ImageAnalysis)
  → YuvToJpegConverter (YUV_420_888 → NV21 → JPEG)
  → MjpegServer  (multipart/x-mixed-replace, 默认 8080)
      ├─ Wi-Fi   http://<手机IP>:8080/video
      └─ USB     adb forward tcp:8080 tcp:8080 → http://127.0.0.1:8080/video
  → PC  OpenCV/FFmpeg 解码 → PySide6 界面
```

USB 方式不需要手机侧改任何代码，只是把端口经 adb 转发到本机。

---

## 目录结构

| 路径 | 说明 |
| --- | --- |
| `android/` | 手机 App（Kotlin，无第三方 UI 库） |
| `android/app/src/main/java/com/camera/MainActivity.kt` | 取流、限帧、界面与语言切换 |
| `android/app/src/main/java/com/camera/MjpegServer.kt` | 手写 HTTP 服务，`/video` `/status` `/camera` `/torch` `/` |
| `android/app/src/main/java/com/camera/YuvToJpegConverter.kt` | YUV → JPEG |
| `pc/` | 电脑端桥接程序（Python 3.10+ / PySide6） |
| `pc/connection_panel.py` | 连接方式面板（Wi-Fi / USB 两段式表单） |
| `pc/capture_worker.py` | 采集线程：连接、重连、遥测 |
| `pc/camera_receiver.py` | OpenCV 拉流与超时控制 |
| `pc/adb_bridge.py` | adb 定位、设备列表、端口转发 |
| `pc/video_view.py` `pc/metrics_bar.py` `pc/theme.py` `pc/i18n.py` | 画面、指标、样式、文案 |
| `pc/assets/app_icon.ico` | 窗口/任务栏图标（16–256px 七档，同一张母图；Android 的 mipmap 同源） |

`pc/.venv/` 是本机虚拟环境，不属于仓库内容，换机器按下面的步骤重建即可。

---

## 环境要求

- **电脑**：Windows（其他平台未测），Python 3.10 以上，`adb` 在 PATH 里或设置了 `ANDROID_HOME`
- **手机**：Android 7.0（API 24）以上，需要相机权限
- **构建手机 App**：JDK 21（`app/build.gradle.kts` 里 source/target 都是 VERSION_21），Android SDK（`android/local.properties` 里是本机 SDK 路径，不要提交）

---

## 手机端

```bash
cd android
./gradlew.bat :app:assembleDebug        # Windows
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.camera/.MainActivity
```

界面从上到下：

- **状态胶囊**：`已停止 / 等待连接 / 推流中`，右侧是语言切换按钮
- **取景预览**
- **取景预览**：两个控件浮在画面自己身上，不在下面的控件区里
  - 右上角一颗胶囊写着当前角度加一个下箭头，点开是 `0° / 90° / 180° / 270°` 的下拉框，当前那个是绿的
  - 底部居中一个半圆弧拨盘：沿弧拖动改倍率，左端是这颗镜头的下限、右端是上限，弧上按整数倍率打刻度，中间是当前倍数。按下去只把圆点换成按下的绿，不长大
- **摄像头**：`后置 / 前置` 分段切换，选择会被记住；右边是补光按钮，后置显示 `闪光灯`、前置显示 `屏幕补光`，开着时文字变绿并带 `·开`
- **连接方式**：`Wi-Fi` 面板显示推流地址、在线客户端数、端口输入框；`USB 数据线` 面板显示线缆是否插入、以及电脑端要执行的 adb 命令
- **启动服务 / 停止服务**

行为：

- 服务运行时给窗口加 `FLAG_KEEP_SCREEN_ON`，手机不会自动息屏；停止服务后恢复正常
- 补光：后置用 LED 常亮（`CameraControl.enableTorch`），前置没有闪光灯，改成把这块屏幕拉到最亮当补光板；切镜头会跟着换到对应的实现，不跟随服务开关
- 按 Home 切后台：进入自绘的悬浮小窗继续推流。贴在屏幕右上角，圆角由本应用裁（16dp，和画面对齐），可以拖动挪位置，点一下回到应用
- 悬浮小窗要「显示在其他应用上层」权限；没授权就自动退回系统 PiP，角落和描边由系统决定。**看到小窗跑到右下角＝这个授权掉了**（不是代码改的）：MIUI 上通过 `adb shell appops set ... allow` 给的授权是限时的（`appops get` 会显示 `duration=+156ms` 这种），只有从系统设置里授的才留得住
- 按返回键：弹出「切到后台？」，`退出` 会停服务并断开电脑连接，`小窗运行` 进小窗（未授权时先弹一次授权引导）
- 帧率默认 30，可以用 `/fps?value=N` 在 5~30 之间调，选择会存下来；界面暂时没有这个控件
- 对焦默认 `auto`（对一次就停住），用 `/focus?mode=auto|continuous|locked` 切，同样存偏好；界面也没有控件。不设的话相机走的是 Camera2 默认的连续对焦，画面一动就重新拉风箱——电脑端实测只有 27% 的帧是清晰的，采集不能用
- 画面方向默认 0°，选择存下来。**0° ＝ 和手机预览同向**（人眼看过去是正的），90/180/270 从那里再转。界面上设的角度是**叠在缓冲区自己的方向之上**的（`ImageInfo.rotationDegrees`），所以不管相机这次给的是横缓冲区还是竖缓冲区，同一档角度给出的画面方向是同一个。0/180 输出 720x960，90/270 输出 960x720；改角度不用重新绑定，下一帧的像素就按新方向排
- 变焦存的是倍率，会按当前镜头的上限夹住：前置设 8 实际给 4 并存 4。后置 5 倍时换到前置会变 4 倍，换回后置不会自己回到 5（偏好里存的还是 5，重启才按它恢复）
- 画面上那两个浮层在进小窗 / 系统 PiP 时和顶栏、控件区一起收掉，不会留在小窗里
- 相机绑在一个常驻 RESUMED 的生命周期（`AlwaysResumedOwner`）上而不是 Activity 上，否则 Activity 一 stop CameraX 就解绑、小窗会定住；系统允许后台用相机的前提就是那个悬浮窗可见
- Android 8.0 以下既没有悬浮窗也没有 PiP，切后台就是普通后台（相机会解绑）

HTTP 接口：

| 路径 | 返回 |
| --- | --- |
| `/video` | `multipart/x-mixed-replace` 视频流 |
| `/status` | JSON：`running` `ip` `port` `clients` `camera` `light` `fps_target` `fps`（实测） `focus` `rotation` `source`（相机给的缓冲区尺寸） `source_rotation`（缓冲区自己要转多少度才正） `zoom` `stream` |
| `/camera` | JSON：当前镜头 |
| `/camera?face=front` / `?face=back` | 切换镜头并返回新值 |
| `/torch` | JSON：当前补光状态 |
| `/torch?on=1` / `?on=0` | 开关补光并返回新值 |
| `/fps` | JSON：`target` 与实测 `fps` |
| `/fps?value=30` | 改帧率（5~30，存进偏好，重启仍生效）并返回当前值 |
| `/focus` | JSON：当前对焦模式 |
| `/focus?mode=auto` | 触发一次对焦后停住；再调一次会重新对焦 |
| `/focus?mode=continuous` / `?mode=locked` | 连续对焦 / 冻结在当前镜头位置 |
| `/rotate` | JSON：当前输出方向 |
| `/rotate?value=90` | 输出画面顺时针转（只认 0/90/180/270，存进偏好）并返回当前值 |
| `/zoom` | JSON：`zoom` 当前倍率、`zoom_min` `zoom_max` 这颗镜头给的区间 |
| `/zoom?value=2.5` | 变到 2.5 倍（超出区间按上下限夹住，存进偏好）并返回当前值 |
| `/` | 一个直接嵌 `/video` 的网页，方便用手机浏览器自测 |

端口可在界面里改（1024–65535），改完会自动重启服务。

---

## 电脑端

```bash
cd pc
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements.txt
.venv/Scripts/python.exe main.py
```

命令行参数（都可以不给，界面里填）：

```
--mode {wifi,usb}   预选连接方式
--host HOST         Wi-Fi 模式下的手机 IP
--port PORT         手机推流端口，默认 8080
--lang {zh,en}      界面语言，默认取上次保存的选择，再退回系统语言
```

快捷键：`Ctrl+K` 连接/断开，`F11` 全屏（`Esc` 退出）。
截图按钮写文件到当前目录的 `snapshots/camera_*.png`。

画面区右上角的按钮：`开补光 / 关补光`、`切到前置 / 切到后置`（两个都连上之后才可用，
镜头和补光状态从手机的 `/status` 读取）、`English / 中文`、`截图`、`全屏`。

同一台电脑只能开一个桥接窗口：`adb forward` 的本机端口是全局的，两个实例会互相把
对方的流打断，所以第二个启动时会提示已有人在运行然后退出。

连接方式、地址、端口、语言都记在 QSettings（`PhoneCamera/PhoneCameraBridge`）里，下次启动自动恢复。

---

## 两种连接方式怎么用

### Wi-Fi

1. 手机和电脑连同一个网络
2. 手机上确认「连接方式」停在 Wi-Fi，把界面上的地址抄下来（或直接点复制）
3. 电脑端选 Wi-Fi，填 IP 和端口，点「通过 Wi-Fi 连接」

### USB 数据线

1. 手机打开 **开发者选项 → USB 调试**，插上数据线并在弹窗里允许调试
2. 电脑端选 USB 数据线，点刷新，设备下拉里应能看到机型
3. 确认「手机端口」（服务端口）和「本机端口」（电脑侧端口），面板会显示即将执行的
   `adb -s <serial> forward tcp:<本机> tcp:<手机>`
4. 点「通过 USB 连接」。断开、连接失败、关窗口时都会自动 `forward --remove` 回收端口

手动等价命令：

```bash
adb devices -l
adb -s <serial> forward tcp:8080 tcp:8080
adb -s <serial> forward --remove tcp:8080
```

---

## 打包发布

### 电脑端：单文件 exe

```bash
cd pc
.venv/Scripts/python.exe -m pip install pyinstaller
.venv/Scripts/pyinstaller PhoneCamera.spec    # 产出 pc/dist/PhoneCamera.exe，约 100 MB
```

`PhoneCamera.spec` 里记着全部参数（`console=False` 不带控制台、单文件、图标取
`assets/app_icon.ico`），改排除项或要带额外文件就在它里面改。注意 `assets/app_icon.ico`
必须同时作为 `datas` 打进包里：onefile 运行时 `main.py` 的 `__file__` 指向临时解压目录，
漏了它 `setWindowIcon` 会静默失败，标题栏和任务栏就变成系统默认图标。实测打包版连真机：
Wi-Fi 12.0 FPS、USB 11.7 FPS，都是 960x720，USB 那条说明冻结进程自己调 adb
建端口转发没问题。冷启动 2~4 秒（单文件每次要解压到临时目录）。

### 电脑端：安装包

```bash
ISCC packaging\PhoneCamera.iss                # 产出 pc/installer/PhoneCamera-<版本>-setup.exe
```

装到当前用户的 `AppData\Local\Programs\PhoneCamera`，不需要管理员权限，带开始菜单项、
可选桌面图标和卸载项。没装 Inno Setup 的话 `winget install JRSoftware.InnoSetup`。
安装包本身没做代码签名，第一次运行 Windows 会弹一次「未知发布者」，点仍要运行即可。

### 手机端：release 签名

签名证书不用向任何机构申请，用 JDK 自带的 `keytool` 自己生成一张自签名证书就行——它是这款
App 的身份，Android 只校验「这次安装和上次是不是同一个 keystore 签的」。

```bash
cd android && ./gradlew assembleRelease   # 产出 app/build/outputs/apk/release/PhoneCamera-0.1.1-release.apk
```

产物名由 `app/build.gradle.kts` 里的 `androidComponents` 改成和电脑端一致（默认名带的是模块名 `app`）。

keystore 已经生成好放在仓库外（`~/.android/phonecamera-release.keystore`，跟 debug.keystore
同目录但完全独立），凭据写在 `android/keystore.properties`，两个文件都不进仓库。
`app/build.gradle.kts` 读到 `keystore.properties` 就签 release，读不到就退回 debug 签名，
所以 clone 出来的机器不带密钥也能构建。证书是 RSA 2048 / SHA384withRSA / 有效期 10000 天，
`CN=PhoneCamera, O=sunxiaojie, C=CN`，SHA-256 开头 `acf6ac0f`。
这张是 2026-10-04 重新生成的：原来那张（SHA-256 开头 `80616cd5`）随 `~/.android` 目录在 9-29
被重建时丢了，找不回来。所以任何用旧证书装过的设备都要先卸载才能装新签名的包。

丢了这个 keystore 就没法覆盖升级已发出去的机器，只能先卸载再装，所以除了本机还要在别处留一份备份。
换密码用 `keytool -storepasswd` / `keytool -keypasswd`，重新生成用 `keytool -genkeypair -keystore
<路径> -alias phonecamera -keyalg RSA -keysize 2048 -validity 10000`。

手机上当前的包是 debug 签的（debug 证书是 SDK 目录里那个人人相同的 `~/.android/debug.keystore`，
不能当发布签名用），两者证书不同，release 包要覆盖安装得先卸载旧包。

### 代码签名（Windows）

安装包没有签名，用户第一次运行会看到 SmartScreen「未知发布者」，点「更多信息 → 仍要运行」即可。
要真签下去，三条路的代价：

- **Azure Artifact Signing（原 Trusted Signing）**：个人开发者最便宜的一条，但按 2026 年的文档，
  Public Trust 证书只发给美国、加拿大、欧盟、英国、澳洲、新西兰、日本、韩国、新加坡、瑞士、
  挪威、以色列的机构，个人开发者还限美加两地——国内拿不到。Private Trust 不受地域限制，
  但只在你自己装了根证书的机器上有效，公开分发没用。
- **传统 CA 的 OV/EV 证书**：大约 $150–400/年，且 CA/Browser Forum 从 2023 年起要求私钥存在
  HSM 或云 HSM 里。签完也不会立刻消掉 SmartScreen——它现在按下载信誉判定，只有 EV 才有即时
  信誉，而 EV 更贵、还要硬件 token。
- **自签名证书**：只能骗过把这张根证书导入过「受信任的根证书颁发机构」的机器，发给别人没用。

所以这里不签名，改成发布时把安装包的 SHA-256 写进 Release 说明，用户下载后自己对一下：

```powershell
certutil -hashfile PhoneCamera-0.1.1-setup.exe SHA256
```

---

## 实测表现

在 Redmi K40 Gaming（`M2012K10C`，Android 13 / API 33，天玑 mt6893）上测得：

| 指标 | 数值 |
| --- | --- |
| 缓冲区尺寸 | 960x720（CameraX 就近取到的档位，`/status` 里的 `source`）。电脑端解到的尺寸随界面设的角度变：0°/180° 是 720x960，90°/270° 是 960x720 |
| 帧率 | 目标 30 时前台 **27~28 FPS**，目标 15 时 13~14 FPS（改节流之前只有 10~11） |
| PC 侧 Wi-Fi 到达率 | AE 钉死 `[30,30]` + 合并下发后 **24~25 FPS**（手机自报 22.8~25.5，和到达率同量级＝没在链路上丢帧）；AE/AF 分两次下发时退回 20.0 |
| 对焦 | `continuous` 时清晰度 p50 41.9 / max 189.7（仍在偶尔重找），`auto` 与 `locked` 时 p50≈max（34.1~43.4，完全稳定） |
| 画面方向的代价 | 叠上 `rotationDegrees` 之后：0°/180° 输出 720x960，90°/270° 输出 960x720。四个角度各 12 s 交替测，到达帧率 24.1 / 23.6 / 22.0 / 18.6 —— 读数随时间单调降、跟角度不相关（同一档 0° 两次差 2.1），是场景变亮＋连续跑了半小时的发热，不是旋转的代价；昨天暗场景下量的是 0° 27.0~27.7 与 90° 27.0~27.9，两档无差 |
| 方向真的落到字节上 | 各角度的帧与「把 0° 帧按同角度转一次」的参照逐像素相关性 90°/180°/270° 全是 **1.000**，而「先镜像再转」只有 **-0.70**（尺子能分辨）。0° 帧与手机预览画面区的相关性 **0.961**，把 0° 帧按旧语义（缓冲区原样）摆回去只有 **-0.071** —— 0° 确实等于预览那个方向 |
| 变焦 | 区间实测后置 [1,10]、前置 [1,4]，与 `dumpsys media.camera` 的 `android.control.zoomRatioRange` 一致。在拨盘弧上点四个位置（左端 / 45° / 弧顶 / 右端）回读 1.06 / 7.74 / 5.49 / 10.0，按角度算的期望是 1.0 / 7.75 / 5.5 / 10.0；前置请求 8 倍被夹到 4.0。放大的实证：10 倍的帧与「把 1 倍的帧取中心 1/10 裁剪再放大回去」逐像素相关性 **0.981**，与整帧只有 0.194（反证），同一设置连拍两帧是 1.000（＝手机静着、量具有效） |
| 单帧耗时 | 约 37 ms（目标 30、960x720） |
| 首帧 | 打开流后约 31 ms |
| 界面从点击到出画 | 4.0 s（含探测与缓冲） |
| 关闭卡住的连接 | 0.8 s 内干净退出（服务器只发头、不发帧的最坏情况） |

采集线程对 FFmpeg 显式设了 `OPEN_TIMEOUT_MSEC=3000` / `READ_TIMEOUT_MSEC=4000`。
去掉这两个参数的话，一次失败的连接会阻塞满 30 秒，界面随之假死。

镜头切换、不息屏与小窗同样在这台机器上做过对照：系统息屏阈值 120 s，开着服务 145 s 后仍是 `Awake`，停止服务后同样时长进入 `Dozing`；
按 Home 后 `dumpsys window` 出现 `Sys2038:com.camera`（`TYPE_APPLICATION_OVERLAY`），`frame=[695,106][1058,590]`——1080 宽的屏上距右边缘 22px、距状态栏下沿 22px，即右上角，此时 `/video` 实测 16.2 FPS（目标 30）；
拖一下能挪走（同一窗口 frame 变成 `[112,968][475,1452]`），点一下回应用、悬浮窗消失、预览重新接回 Activity 的 `PreviewView`；
撤掉悬浮窗权限再按 Home 则落到系统 PiP（`mLastReportedPictureInPictureMode=true`），`/video` 同样继续出帧；
选「退出」后端口立即关闭（连接被拒），`dumpsys media.camera` 的 `Active Camera Clients` 回到空列表，重新打开应用可立刻恢复推流。

---

## 界面语言

两端都跟随系统语言（中文系统即中文），可随时切到另一种语言并记住选择，之后以记住的为准：

- 电脑端：右上角按钮切换，文案在 `pc/i18n.py`，选择存进 QSettings，也可用 `--lang` 指定
- 手机端：顶栏按钮切换，中文文案在 `res/values-zh/strings.xml`，选择存在 SharedPreferences

加新文案时两份语言表要同时补，键集保持一致；画面上的直播角标是绘制出来的，
文字宽度按实测算，别写死尺寸。

---

## 已知限制

- LED 常亮很费电也会发热，长时间用记得关；前置的「屏幕补光」只是把屏幕拉到最亮，亮度上限就是这块屏幕的上限，系统省电策略还可能压回去
- 悬浮小窗默认右上角、可拖动，但不记位置：下次进小窗仍回右上角。没有「显示在其他应用上层」权限时退回系统 PiP，角落由系统定（实测 MIUI 落在右下，`frame=[672,1741][1039,2240]`，拖到右上后下次进入仍回右下）
- 小窗里进程会被降优先级，帧率低于前台：目标 30 时实测前台 27~28、自绘悬浮窗 16.2、系统 PiP 降级 14.1 FPS
- `FLAG_KEEP_SCREEN_ON` 只在应用窗口可见时生效（前台或小窗）；要完全后台也不息屏得加前台服务
- 既没授权悬浮窗、又用不了 PiP（Android 8.0 以下）时，应用一进后台系统就禁用相机，画面会停；此时服务仍会接受连接，但没有新帧，手机侧靠重发上一帧的保活写入识别走掉的观看者，最长 30 秒回收
- 没有鉴权：同一网络里的任何设备都能拉流，别在公共或访客网络上开着服务
- 只解析 IPv4 地址；多网卡时手机界面取到的是第一个非回环 IPv4
- 帧率上限受手机编码能力影响：960x720 的软件 JPEG 编码一帧 26~38 ms，所以目标 30 实际到 27~28；降到 640x480 编码只剩 14~18 ms，能跑到 29。降 `JPEG_QUALITY`（当前 80）也能换帧率
- 节流是「距上次编码超过阈值才编」，只能对相机帧率做整数分频：相机给 30 时能稳定落在 30 / 15 / 10 这几档，设 20 实测只有 16
- AE 区间按目标帧率钉死成 `[N,N]`，暗光下曝光时间被压到 1/N 秒以内，画面会更暗更噪（实测 30 帧时 JPEG 从 30 KB 缩到 20.6 KB）；要画质优先就把下界改回 `MIN_FPS` 让 AE 自己降帧
- **AE 区间和 AF 模式必须打包成一次 `setCaptureRequestOptions` 下发**。分两次调时后一次会覆盖前一次的整套选项：实测先下 AE `[30,30]`、再单独下 AF，AE 就悄悄退回 20 fps 工作点（`/status` 里 `fps_target` 仍是 30、`fps` 却是 20.0），只有合并成一次才拿到 24~29
- 对焦靠画面对比找峰值，对着空墙/纯天花板这种低纹理目标时 `auto` 也会停在随机位置（实测同一场景两次 `auto` 拿到 43.0 和 34.1）。正确流程是先定好工作距离、让画面里有纹理，再 `/focus?mode=auto` 对一次、`/focus?mode=locked` 冻住
- 画面方向只转推流的帧，手机屏幕上那块预览不转：`PreviewView` 用的是 performance（SurfaceView）模式，硬件通路上的视图自己转不动。要连预览一起转得换 compatible（TextureView），这条路没量过，换之前要先测小窗和帧率
- 手机真的转起来画面还是会跟着转：这个控件保证的是「软件不自己改方向」——Activity 锁 portrait、分析缓冲区永远是传感器方向，实测把系统 `user_rotation` 设成 1 之后 `/status` 的方向和帧尺寸都不动。机身转了场景就转，这是物理；换挂法就点一下对应角度
- 变焦的上下限不能问 `zoomState`：实测换镜头后它只回调一次、报 `min=max=1.0` 且之后不再更新，而同一颗前置的 characteristics 写的是 `[1,4]`——拿它钳制会把前置的变焦锁死在 1 倍。所以区间从 `Camera2CameraInfo` 读 characteristics，界面和 `/status` 给的也是按它夹过的请求值，不是相机回读值
- 旋转折在 NV21 的收集循环里：90°/270° 是跨行写（每一步跳一整行），实测没有代价，但色度按 2x2 块整体旋转要求宽高都是偶数（当前 960x720 满足，换档位要重新确认）
- 相机给分析流的缓冲区方向**不是恒定的**：同一颗后置在不同次绑定里给过 960x720 也给过 720x960。所以推出去的角度是「缓冲区自己的方向 + 界面设的角度」，叠加那一步靠 `ImageInfo.rotationDegrees`。`/status` 里的 `source` 和 `source_rotation` 就是为这个留的——画面方向和设定不符时先读这两个数，能分清是缓冲区翻了还是设置没生效
- 电脑端 USB 方式依赖 adb；adb 不在 PATH 且没设 `ANDROID_HOME` 时，USB 面板会提示未找到

---

## 许可

本仓库以 CC0 1.0 Universal 进入公共领域，reuse 无需署名、无需授权，详见 `LICENSE`。
