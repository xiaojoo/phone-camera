# Phone Camera — 把手机当成电脑摄像头

手机侧用 CameraX 取流、编码成 JPEG，通过一个手写的 MJPEG HTTP 服务推出去；
电脑侧用 OpenCV 拉流并显示，支持 **Wi-Fi** 和 **USB 数据线** 两种连接方式，
界面中文/英文可切换。

项目还在早期阶段：没有音频、没有鉴权、没有多机位。

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
- **摄像头**：`后置 / 前置` 分段切换，选择会被记住；右边是补光按钮，后置显示 `闪光灯`、前置显示 `屏幕补光`，开着时文字变绿并带 `·开`
- **连接方式**：`Wi-Fi` 面板显示推流地址、在线客户端数、端口输入框；`USB 数据线` 面板显示线缆是否插入、以及电脑端要执行的 adb 命令
- **启动服务 / 停止服务**

行为：

- 服务运行时给窗口加 `FLAG_KEEP_SCREEN_ON`，手机不会自动息屏；停止服务后恢复正常
- 补光：后置用 LED 常亮（`CameraControl.enableTorch`），前置没有闪光灯，改成把这块屏幕拉到最亮当补光板；切镜头会跟着换到对应的实现，不跟随服务开关
- 按 Home 切后台：直接进入小窗（PiP）继续推流，不断开连接；小窗里只有预览画面
- 按返回键：弹出「切到后台？」，`退出` 会停服务并断开电脑连接，`小窗运行` 进入 PiP
- Android 8.0 以下没有 PiP，此时切后台就是普通后台（相机随生命周期解绑）

HTTP 接口：

| 路径 | 返回 |
| --- | --- |
| `/video` | `multipart/x-mixed-replace` 视频流 |
| `/status` | JSON：`running` `ip` `port` `clients` `camera` `light` `stream` |
| `/camera` | JSON：当前镜头 |
| `/camera?face=front` / `?face=back` | 切换镜头并返回新值 |
| `/torch` | JSON：当前补光状态 |
| `/torch?on=1` / `?on=0` | 开关补光并返回新值 |
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

## 实测表现

在 Redmi K40 Gaming（`M2012K10C`，Android 13 / API 33，天玑 mt6893）上测得：

| 指标 | 数值 |
| --- | --- |
| 解码尺寸 | 960x720（CameraX 就近取到的档位） |
| 帧率 | 10–11 FPS（限帧目标 15，瓶颈在手机侧 JPEG 编码） |
| 单帧耗时 | 99–142 ms |
| 首帧 | 打开流后约 31 ms |
| 界面从点击到出画 | 4.0 s（含探测与缓冲） |
| 关闭卡住的连接 | 0.8 s 内干净退出（服务器只发头、不发帧的最坏情况） |

采集线程对 FFmpeg 显式设了 `OPEN_TIMEOUT_MSEC=3000` / `READ_TIMEOUT_MSEC=4000`。
去掉这两个参数的话，一次失败的连接会阻塞满 30 秒，界面随之假死。

镜头切换、不息屏与小窗同样在这台机器上做过对照：系统息屏阈值 120 s，开着服务 145 s 后仍是 `Awake`，停止服务后同样时长进入 `Dozing`；
按 Home 或按返回键选「小窗运行」后 `mLastReportedPictureInPictureMode=true`，`/video` 继续出帧；
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
- 小窗（PiP）停靠的角落由系统决定，通常是右下，可拖动；API 没有"固定右上角"的接口
- 小窗里仍在出帧，但系统会把进程降到低优先级，帧率明显低于前台（实测 Redmi K40 Gaming：前台约 10 FPS，小窗约 2~3 FPS）
- `FLAG_KEEP_SCREEN_ON` 只在应用窗口可见时生效（前台或小窗）；要完全后台也不息屏得加前台服务
- 没有 PiP、或窗口完全不可见时 CameraX 会解绑，画面就停了；此时服务仍会接受连接，但没有新帧，手机侧靠重发上一帧的保活写入识别走掉的观看者，最长 30 秒回收
- 没有鉴权：同一网络里的任何设备都能拉流，别在公共或访客网络上开着服务
- 只解析 IPv4 地址；多网卡时手机界面取到的是第一个非回环 IPv4
- 帧率上限受手机编码能力影响，降 `JPEG_QUALITY`（当前 80）或降分辨率可以换更高帧率
- 电脑端 USB 方式依赖 adb；adb 不在 PATH 且没设 `ANDROID_HOME` 时，USB 面板会提示未找到

---

## 许可

本仓库以 CC0 1.0 Universal 进入公共领域，reuse 无需署名、无需授权，详见 `LICENSE`。
