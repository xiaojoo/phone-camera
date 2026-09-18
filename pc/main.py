import argparse
import sys

from PySide6.QtCore import QSettings
from PySide6.QtWidgets import QApplication, QStyleFactory

import i18n
import theme
from adb_bridge import AdbBridge
from app_window import BridgeWindow
from connection_panel import DEFAULT_PHONE_PORT, MODE_WIFI


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

    app = QApplication(sys.argv)
    app.setStyle(QStyleFactory.create("Fusion"))
    app.setOrganizationName("PhoneCamera")
    app.setApplicationName("PhoneCameraBridge")
    app.setStyleSheet(theme.STYLESHEET)

    settings = QSettings()

    i18n.set_code(
        args.lang
        or settings.value("ui/language", "")
        or i18n.default_code()
    )

    window = BridgeWindow(settings, AdbBridge())

    if args.mode or args.host:
        window.apply_cli_target(args.mode or MODE_WIFI, args.host, args.port)

    window.show()

    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
