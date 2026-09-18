from PySide6.QtCore import QLocale

ZH = "zh"
EN = "en"

TABLES = {
    ZH: {
        "app.title": "手机摄像头桥接",
        "app.subtitle": "手机摄像头视频流",
        "section.mode": "连接方式",
        "section.session": "会话",
        "mode.wifi": "Wi-Fi",
        "mode.usb": "USB 数据线",

        "wifi.address": "手机地址",
        "wifi.portTip": "手机提供视频流的端口",
        "wifi.note": "手机启动流服务后，会在自己的屏幕上显示这个地址。",

        "usb.adb": "adb",
        "usb.device": "设备",
        "usb.phonePort": "手机端口",
        "usb.pcPort": "本机端口",
        "usb.refresh": "刷新",
        "usb.note": "在手机上打开 开发者选项 → USB 调试。连接时会自动执行 adb forward。",

        "adb.searching": "搜索中…",
        "adb.probing": "检测中…",
        "adb.ready": "就绪",
        "adb.missing": "未找到",
        "adb.unavailable": "adb 不可用",
        "adb.none": "USB 上没有设备",
        "adb.forwardMissing": "adb 缺失",

        "action.waiting": "等待连接",
        "action.connect": "连接",
        "action.connectWifi": "通过 Wi-Fi 连接",
        "action.connectUsb": "通过 USB 连接",
        "action.disconnect": "断开连接",
        "action.needHost": "请输入手机 IP 地址",
        "action.needDevice": "请选择一台设备",

        "session.idle": "空闲",
        "session.transport": "通道",
        "forward.unused": "Wi-Fi 模式不使用转发",
        "forward.active": "已生效",

        "footer.hint": "Wi-Fi 需要两台设备在同一个网络。\nUSB 通过 adb 端口转发接入手机画面。",

        "view.live": "实时画面",
        "view.snapshot": "截图",
        "view.snapshotTip": "把当前帧保存为 PNG / JPEG",
        "view.fullscreen": "全屏",
        "view.fullscreenTip": "F11 · Esc 退出",
        "view.liveBadge": "直播",
        "status.ready": "就绪",

        "phase.idle": "等待连接",
        "phase.connecting": "正在连接 {url}",
        "phase.live": "{url}",
        "phase.reconnecting": "第 {n}/{total} 次重连 · {url}",
        "phase.interrupted": "视频流已中断",
        "phase.failed": "无法连接 {url}，请确认手机正在推流",
        "phase.stopped": "已断开",
        "phase.closing": "上一路视频流还在关闭中，请稍后再试。",
        "phase.forwarding": "adb forward tcp:{local} → tcp:{remote}",

        "phaseName.idle": "空闲",
        "phaseName.connecting": "连接中",
        "phaseName.live": "直播中",
        "phaseName.reconnecting": "重连中",
        "phaseName.failed": "失败",
        "phaseName.stopped": "已停止",

        "error.adbMissing": "本机没有找到 adb 可执行文件。",
        "error.noDevice": "没有选择 USB 设备。插好数据线后点刷新。",
        "error.forward": "adb forward 失败，手机可能没有授权 USB 调试。{detail}",

        "status.chooseMode": "请选择一种连接方式",
        "status.noFrame": "还没有可截图的画面",
        "status.snapshotSaved": "截图已保存 → {path}",
        "status.snapshotFailed": "无法写入该文件。",

        "metric.fps": "帧率",
        "metric.frameMs": "单帧耗时",
        "metric.size": "解码尺寸",
        "metric.frames": "累计帧数",
        "metric.uptime": "运行时长",

        "placeholder.idleTitle": "尚未连接",
        "placeholder.idleDetail": "选择连接方式后点“连接”",
        "placeholder.connectingTitle": "连接中",
        "placeholder.failedTitle": "连接失败",
        "placeholder.stoppedTitle": "已停止",

        "lang.toggle": "English",
    },
    EN: {
        "app.title": "Phone Camera Bridge",
        "app.subtitle": "MJPEG stream from the phone camera",
        "section.mode": "CONNECTION MODE",
        "section.session": "SESSION",
        "mode.wifi": "Wi-Fi",
        "mode.usb": "USB cable",

        "wifi.address": "Phone address",
        "wifi.portTip": "Port the phone serves the stream on",
        "wifi.note": "The phone shows this address on its own screen once the "
                     "stream server is running.",

        "usb.adb": "adb",
        "usb.device": "Device",
        "usb.phonePort": "Phone port",
        "usb.pcPort": "PC port",
        "usb.refresh": "Refresh",
        "usb.note": "Enable Developer options → USB debugging on the phone. "
                    "The bridge runs adb forward automatically on connect.",

        "adb.searching": "searching…",
        "adb.probing": "probing…",
        "adb.ready": "ready",
        "adb.missing": "not found",
        "adb.unavailable": "adb is not available",
        "adb.none": "No device over USB",
        "adb.forwardMissing": "adb missing",

        "action.waiting": "Waiting for a connection",
        "action.connect": "Connect",
        "action.connectWifi": "Connect via Wi-Fi",
        "action.connectUsb": "Connect via USB",
        "action.disconnect": "Disconnect",
        "action.needHost": "Enter the phone IP address",
        "action.needDevice": "Select a device",

        "session.idle": "Idle",
        "session.transport": "transport",
        "forward.unused": "forward: not used on Wi-Fi",
        "forward.active": "active",

        "footer.hint": "Wi-Fi needs both devices on the same network.\n"
                       "USB routes the phone stream through adb port forwarding.",

        "view.live": "Live view",
        "view.snapshot": "Snapshot",
        "view.snapshotTip": "Save the current frame as PNG / JPEG",
        "view.fullscreen": "Full screen",
        "view.fullscreenTip": "F11 · Esc to exit",
        "view.liveBadge": "LIVE",
        "status.ready": "Ready",

        "phase.idle": "Idle",
        "phase.connecting": "Connecting to {url}",
        "phase.live": "{url}",
        "phase.reconnecting": "Reconnecting ({n}/{total}) · {url}",
        "phase.interrupted": "Stream interrupted",
        "phase.failed": "Cannot reach {url} — is the phone streaming?",
        "phase.stopped": "Disconnected",
        "phase.closing": "The previous stream is still closing. Try again.",
        "phase.forwarding": "adb forward tcp:{local} → tcp:{remote}",

        "phaseName.idle": "idle",
        "phaseName.connecting": "connecting",
        "phaseName.live": "live",
        "phaseName.reconnecting": "reconnecting",
        "phaseName.failed": "failed",
        "phaseName.stopped": "stopped",

        "error.adbMissing": "adb executable was not found on this machine.",
        "error.noDevice": "No USB device selected. Plug in the cable and press Refresh.",
        "error.forward": "adb forward failed. The phone may not be authorised for "
                         "USB debugging. {detail}",

        "status.chooseMode": "Choose a connection mode",
        "status.noFrame": "No frame to capture yet",
        "status.snapshotSaved": "Snapshot saved → {path}",
        "status.snapshotFailed": "Could not write the file.",

        "metric.fps": "FPS",
        "metric.frameMs": "FRAME · MS",
        "metric.size": "DECODE SIZE",
        "metric.frames": "FRAMES",
        "metric.uptime": "UPTIME",

        "placeholder.idleTitle": "Not connected",
        "placeholder.idleDetail": "Pick a connection mode, then press Connect",
        "placeholder.connectingTitle": "Connecting",
        "placeholder.failedTitle": "Connection failed",
        "placeholder.stoppedTitle": "Stopped",

        "lang.toggle": "中文",
    },
}

PHASE_TITLES = {
    "idle": "placeholder.idleTitle",
    "connecting": "placeholder.connectingTitle",
    "live": "view.liveBadge",
    "reconnecting": "placeholder.connectingTitle",
    "failed": "placeholder.failedTitle",
    "stopped": "placeholder.stoppedTitle",
}

_mode = ZH


def default_code() -> str:
    return ZH if QLocale.system().language() == QLocale.Language.Chinese else EN


def set_code(code: str) -> None:
    global _mode
    _mode = code if code in TABLES else ZH


def code() -> str:
    return _mode


def tr(key: str, **fields) -> str:
    text = TABLES[_mode].get(key, TABLES[EN].get(key, key))

    return text.format(**fields) if fields else text
