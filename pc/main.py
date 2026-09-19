import argparse
import os
import sys
from pathlib import Path

from cv2.utils import logging as cv_logging
from PySide6.QtCore import QLockFile, QDir, QSettings
from PySide6.QtGui import QIcon
from PySide6.QtWidgets import (
    QApplication,
    QMessageBox,
    QStyleFactory,
)

import i18n
import theme
from adb_bridge import AdbBridge
from app_window import BridgeWindow
from connection_panel import DEFAULT_PHONE_PORT, MODE_WIFI

LOCK_NAME = "phone-camera-bridge.lock"

ICON_PATH = Path(__file__).resolve().parent / "assets" / "app_icon.ico"

# FFmpeg 每次读超时都会往控制台刷 WARN，界面已经报了状态，别让它看着像报错
cv_logging.setLogLevel(cv_logging.LOG_LEVEL_ERROR)


def parse_args():
    parser = argparse.ArgumentParser(
        description="PhoneCameraBridge: PC console for the phone camera",
    )

    parser.add_argument(
        "--mode",
        choices=("wifi", "usb"),
        help="connection mode to preselect",
    )
    parser.add_argument(
        "--host",
        help="phone IP address for Wi-Fi mode",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=DEFAULT_PHONE_PORT,
        help="phone stream port (default: %(default)s)",
    )

    parser.add_argument(
        "--lang",
        choices=i18n.TABLES,
        help="interface language (default: saved choice, then system locale)",
    )

    return parser.parse_args()


def main():
    args = parse_args()

    if sys.platform == "win32":
        # 不设这个，任务栏按钮会把 python.exe 当成宿主、画它自己的图标
        import ctypes

        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(
            "PhoneCamera.Bridge"
        )

    app = QApplication(sys.argv)
    app.setStyle(QStyleFactory.create("Fusion"))
    app.setOrganizationName("PhoneCamera")
    app.setApplicationName("PhoneCameraBridge")
    app.setStyleSheet(theme.STYLESHEET)
    app.setPalette(theme.palette())
    app.setWindowIcon(QIcon(str(ICON_PATH)))

    settings = QSettings()

    i18n.set_code(
        args.lang
        or settings.value("ui/language", "")
        or i18n.default_code()
    )

    lock = QLockFile(os.path.join(QDir.tempPath(), LOCK_NAME))

    if not lock.tryLock(100):
        QMessageBox.warning(
            None,
            i18n.tr("app.title"),
            i18n.tr("error.alreadyRunning"),
        )
        return 1

    app._instance_lock = lock

    window = BridgeWindow(settings, AdbBridge())

    if args.mode or args.host:
        window.apply_cli_target(args.mode or MODE_WIFI, args.host, args.port)

    window.show()

    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
