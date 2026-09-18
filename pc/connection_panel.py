from dataclasses import dataclass

from PySide6.QtCore import QPointF, Qt, QThread, Signal
from PySide6.QtGui import QColor, QFont, QPalette, QPainter, QPen, QPolygonF
from PySide6.QtWidgets import (
    QButtonGroup,
    QComboBox,
    QFrame,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QSpinBox,
    QStackedWidget,
    QStyle,
    QStyledItemDelegate,
    QVBoxLayout,
    QWidget,
)

import i18n
import theme

MODE_WIFI = "wifi"
MODE_USB = "usb"

DEFAULT_PHONE_PORT = 8080

FIELD_HEIGHT = 38


@dataclass
class ConnectionRequest:
    mode: str
    url: str
    host: str
    port: int
    serial: str = ""
    remote_port: int = DEFAULT_PHONE_PORT
    local_port: int = DEFAULT_PHONE_PORT


class BackgroundTask(QThread):
    finished_with = Signal(object)

    def __init__(self, fn, *args):
        super().__init__()
        self._fn = fn
        self._args = args

    def run(self):
        try:
            self.finished_with.emit(self._fn(*self._args))
        except Exception as exc:
            self.finished_with.emit(exc)


def section(key: str) -> QLabel:
    label = QLabel(i18n.tr(key))
    label.setObjectName("SectionLabel")

    font = QFont(label.font())
    font.setWeight(QFont.Weight.Bold)
    label.setFont(font)

    return label


class PopupDelegate(QStyledItemDelegate):
    """弹层画成菜单的样子：行高、分隔线、圆角高亮条。"""

    ROW_HEIGHT = 30
    MASK = (QStyle.StateFlag.State_Selected
            | QStyle.StateFlag.State_MouseOver).value

    def sizeHint(self, option, index):
        size = super().sizeHint(option, index)
        size.setHeight(max(size.height(), self.ROW_HEIGHT))
        return size

    def paint(self, painter, option, index) -> None:
        selected = QStyle.StateFlag.State_Selected in option.state
        hovered = QStyle.StateFlag.State_MouseOver in option.state

        if selected or hovered:
            painter.save()
            painter.setRenderHint(QPainter.RenderHint.Antialiasing)
            painter.setPen(Qt.PenStyle.NoPen)
            painter.setBrush(
                QColor(theme.ACCENT_DIM if selected else theme.CARD_HOVER)
            )
            painter.drawRoundedRect(option.rect.adjusted(4, 1, -4, -1), 5, 5)
            painter.restore()

            option.state = QStyle.State(option.state.value & ~self.MASK)

            colors = option.palette
            colors.setColor(
                QPalette.ColorRole.Text,
                QColor(theme.TEXT if selected else theme.TEXT_SECONDARY),
            )
            option.palette = colors

        super().paint(painter, option, index)

        if index.row() + 1 < index.model().rowCount(index.parent()):
            painter.save()
            painter.setPen(QPen(QColor(theme.BORDER)))
            bottom = option.rect.bottom()
            painter.drawLine(option.rect.left() + 8, bottom,
                             option.rect.right() - 8, bottom)
            painter.restore()


class DeviceCombo(QComboBox):
    """QSS 一旦定义 ::drop-down，Qt 就不再画原生箭头，只能自己补一个。"""

    INSET = 17          # 让箭头到右边的留白与文字到左边的 12px 对齐

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setItemDelegate(PopupDelegate(self))

    def paintEvent(self, event) -> None:
        super().paintEvent(event)

        color = QColor(theme.TEXT_SECONDARY if self.isEnabled() else theme.TEXT_MUTED)
        pen = QPen(color)
        pen.setWidthF(1.4)
        pen.setCapStyle(Qt.PenCapStyle.RoundCap)
        pen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)

        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        painter.setPen(pen)

        center_x = self.width() - self.INSET
        center_y = self.height() / 2

        painter.drawPolyline(QPolygonF([
            QPointF(center_x - 4, center_y - 2),
            QPointF(center_x, center_y + 2),
            QPointF(center_x + 4, center_y - 2),
        ]))


class ConnectionPanel(QWidget):
    connect_requested = Signal(object)
    disconnect_requested = Signal()

    def __init__(self, adb, settings, parent=None):
        super().__init__(parent)

        self.setObjectName("SidePanel")
        self.setFixedWidth(312)

        self._adb = adb
        self._settings = settings
        self._busy_task = None
        self._forward_active = False
        self._connected = False
        self._applying = False
        self._phase = "idle"
        self._detail_key = "phase.idle"
        self._detail_fields: dict = {}
        self._texts: list[tuple[QWidget, str, str]] = []
        self._devices_empty = ""

        self._build_ui()
        self._restore()
        self._select_mode(self._settings.value("mode", MODE_WIFI))
        self.refresh_adb()

    # ---------------- translated text ----------------

    def _t(self, widget: QWidget, key: str, setter: str = "setText") -> None:
        getattr(widget, setter)(i18n.tr(key))
        self._texts.append((widget, key, setter))

    def retranslate(self) -> None:
        for widget, key, setter in self._texts:
            getattr(widget, setter)(i18n.tr(key))

        if self._devices_empty:
            self._devices.setItemText(0, i18n.tr(self._devices_empty))

        self._select_mode(self.mode)
        self.set_connected(self._connected)

    # ---------------- layout ----------------

    def _build_ui(self) -> None:
        root = QVBoxLayout(self)
        root.setContentsMargins(20, 18, 20, 18)
        root.setSpacing(14)

        header = QHBoxLayout()
        header.setSpacing(10)

        accent_bar = QFrame()
        accent_bar.setFixedWidth(3)
        accent_bar.setFixedHeight(18)
        accent_bar.setStyleSheet(
            f"background: {theme.ACCENT}; border-radius: 2px;"
        )

        self._title = QLabel()
        self._title.setStyleSheet(
            f"font-size: 15px; font-weight: 700; background: transparent;"
        )
        self._t(self._title, "app.title")

        header.addWidget(accent_bar)
        header.addWidget(self._title)
        header.addStretch(1)
        root.addLayout(header)

        self._mode_caption = section("section.mode")
        self._texts.append((self._mode_caption, "section.mode", "setText"))
        root.addWidget(self._mode_caption)
        root.addWidget(self._build_mode_switch())

        self._pages = QStackedWidget()
        self._pages.addWidget(self._build_wifi_page())
        self._pages.addWidget(self._build_usb_page())
        root.addWidget(self._pages)

        for field in (self._host, self._wifi_port, self._devices,
                      self._remote_port, self._local_port):
            field.setFixedHeight(FIELD_HEIGHT)

        root.addWidget(self._build_action_card())

        self._session_caption = section("section.session")
        self._texts.append((self._session_caption, "section.session", "setText"))
        root.addWidget(self._session_caption)
        root.addWidget(self._build_status_card())

        self._hint = QLabel()
        self._hint.setObjectName("BodyCaption")
        self._hint.setWordWrap(True)
        self._t(self._hint, "footer.hint")
        root.addWidget(self._hint)

        root.addStretch(1)

    def _build_mode_switch(self) -> QWidget:
        bar = QFrame()
        bar.setObjectName("ModeSwitch")

        layout = QHBoxLayout(bar)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(3)

        self._mode_group = QButtonGroup(self)
        self._mode_group.setExclusive(True)

        self._wifi_button = QPushButton(i18n.tr("mode.wifi"))
        self._usb_button = QPushButton(i18n.tr("mode.usb"))

        for button, mode in (
            (self._wifi_button, MODE_WIFI),
            (self._usb_button, MODE_USB),
        ):
            button.setObjectName("ModeButton")
            button.setCheckable(True)
            button.setCursor(Qt.PointingHandCursor)
            button.clicked.connect(lambda _=False, m=mode: self._select_mode(m))

            self._mode_group.addButton(button)
            layout.addWidget(button, 1)

        self._texts.append((self._wifi_button, "mode.wifi", "setText"))
        self._texts.append((self._usb_button, "mode.usb", "setText"))

        return bar

    def _build_wifi_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 4, 0, 0)
        layout.setSpacing(10)

        layout.addWidget(self._label("wifi.address"))

        self._host = QLineEdit()
        self._host.setObjectName("AddressField")
        self._host.setPlaceholderText("192.168.1.23")
        self._host.setClearButtonEnabled(True)
        self._host.returnPressed.connect(self._request_connect)

        self._wifi_port = QSpinBox()
        self._wifi_port.setObjectName("AddressField")
        self._wifi_port.setRange(1, 65535)
        self._wifi_port.setValue(DEFAULT_PHONE_PORT)
        self._wifi_port.setButtonSymbols(QSpinBox.ButtonSymbols.NoButtons)
        self._wifi_port.setAlignment(Qt.AlignCenter)
        self._wifi_port.setFixedWidth(76)
        self._t(self._wifi_port, "wifi.portTip", "setToolTip")

        row = QHBoxLayout()
        row.setContentsMargins(0, 0, 0, 0)
        row.setSpacing(8)
        row.addWidget(self._host, 1)
        row.addWidget(self._wifi_port)
        layout.addLayout(row)

        layout.addWidget(self._note("wifi.note"))
        layout.addStretch(1)

        return page

    def _build_usb_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 4, 0, 0)
        layout.setSpacing(10)

        grid = QGridLayout()
        grid.setContentsMargins(0, 0, 0, 0)
        grid.setHorizontalSpacing(10)
        grid.setVerticalSpacing(10)
        grid.setColumnStretch(1, 1)

        self._adb_value = QLabel(i18n.tr("adb.searching"))
        self._adb_value.setObjectName("MonoCaption")

        grid.addWidget(self._label("usb.adb"), 0, 0)
        grid.addWidget(self._adb_value, 0, 1)

        grid.addWidget(self._label("usb.device"), 1, 0)
        self._devices = DeviceCombo()
        grid.addWidget(self._devices, 1, 1)

        grid.addWidget(self._label("usb.phonePort"), 2, 0)
        self._remote_port = self._port_box(DEFAULT_PHONE_PORT)
        grid.addWidget(self._remote_port, 2, 1)

        grid.addWidget(self._label("usb.pcPort"), 3, 0)
        self._local_port = self._port_box(DEFAULT_PHONE_PORT)
        grid.addWidget(self._local_port, 3, 1)

        layout.addLayout(grid)

        row = QHBoxLayout()
        row.setSpacing(8)

        self._refresh = QPushButton(i18n.tr("usb.refresh"))
        self._texts.append((self._refresh, "usb.refresh", "setText"))
        self._refresh.clicked.connect(self.refresh_adb)

        self._forward_state = QLabel()
        self._forward_state.setObjectName("MonoCaption")

        row.addWidget(self._refresh)
        row.addWidget(self._forward_state, 1, Qt.AlignRight)
        layout.addLayout(row)

        self._command = QLabel()
        self._command.setObjectName("MonoCaption")
        self._command.setWordWrap(True)
        self._command.setTextInteractionFlags(Qt.TextSelectableByMouse)
        layout.addWidget(self._command)

        layout.addWidget(self._note("usb.note"))
        layout.addStretch(1)

        return page

    def _build_action_card(self) -> QWidget:
        card = QFrame()
        card.setObjectName("Card")

        layout = QVBoxLayout(card)
        layout.setContentsMargins(14, 13, 14, 13)
        layout.setSpacing(9)

        self._target = QLabel()
        self._target.setObjectName("MonoCaption")
        self._target.setWordWrap(True)
        self._t(self._target, "action.waiting")
        layout.addWidget(self._target)

        self._connect = QPushButton(i18n.tr("action.connect"))
        self._connect.setObjectName("PrimaryAction")
        self._connect.setCheckable(True)
        self._connect.setCursor(Qt.PointingHandCursor)
        self._connect.toggled.connect(self._on_toggled)
        layout.addWidget(self._connect)

        return card

    def _build_status_card(self) -> QWidget:
        card = QFrame()
        card.setObjectName("Card")

        layout = QGridLayout(card)
        layout.setContentsMargins(14, 12, 14, 12)
        layout.setHorizontalSpacing(10)
        layout.setVerticalSpacing(7)

        self._dot = QFrame()
        self._dot.setObjectName("StatusDot")
        self._dot.setStyleSheet(theme.dot_style(theme.TEXT_MUTED))

        self._status = QLabel()
        self._status.setWordWrap(True)
        self._t(self._status, "session.idle")

        layout.addWidget(self._dot, 0, 0, Qt.AlignVCenter)
        layout.addWidget(self._status, 0, 1)

        self._transport = QLabel()
        self._transport.setObjectName("MonoCaption")

        layout.addWidget(self._transport, 1, 0, 1, 2)

        return card

    def _label(self, key: str) -> QLabel:
        label = QLabel(i18n.tr(key))
        label.setObjectName("BodyCaption")
        self._texts.append((label, key, "setText"))
        return label

    def _note(self, key: str) -> QLabel:
        label = QLabel(i18n.tr(key))
        label.setObjectName("BodyCaption")
        label.setWordWrap(True)
        label.setStyleSheet(f"color: {theme.TEXT_MUTED};")
        self._texts.append((label, key, "setText"))
        return label

    def _port_box(self, value: int) -> QSpinBox:
        box = QSpinBox()
        box.setRange(1, 65535)
        box.setValue(value)
        box.setButtonSymbols(QSpinBox.ButtonSymbols.NoButtons)
        box.setAlignment(Qt.AlignCenter)
        return box

    # ---------------- behaviour ----------------

    def _select_mode(self, mode: str) -> None:
        mode = mode if mode in (MODE_WIFI, MODE_USB) else MODE_WIFI

        self._pages.setCurrentIndex(0 if mode == MODE_WIFI else 1)
        self._wifi_button.setChecked(mode == MODE_WIFI)
        self._usb_button.setChecked(mode == MODE_USB)

        self._connect.setText(
            i18n.tr("action.connectWifi") if mode == MODE_WIFI
            else i18n.tr("action.connectUsb")
        )

        self._sync_page_height()
        self._update_target()
        self.set_phase(self._phase, self._detail_key, self._detail_fields)
        self._settings.setValue("mode", mode)

    def _sync_page_height(self) -> None:
        page = self._pages.currentWidget()

        if page is not None:
            self._pages.setFixedHeight(page.sizeHint().height())

    @property
    def mode(self) -> str:
        return MODE_WIFI if self._wifi_button.isChecked() else MODE_USB

    def request(self) -> ConnectionRequest:
        if self.mode == MODE_WIFI:
            host = self._host.text().strip() or "127.0.0.1"
            port = self._wifi_port.value()

            return ConnectionRequest(
                mode=MODE_WIFI,
                host=host,
                port=port,
                url=f"http://{host}:{port}/video",
            )

        remote = self._remote_port.value()
        local = self._local_port.value()
        serial = self.current_serial()

        return ConnectionRequest(
            mode=MODE_USB,
            host="127.0.0.1",
            port=local,
            url=f"http://127.0.0.1:{local}/video",
            serial=serial,
            remote_port=remote,
            local_port=local,
        )

    def current_serial(self) -> str:
        return self._devices.currentData() or ""

    def set_phase(self, phase: str, key: str = "", fields: dict | None = None):
        self._phase = phase
        self._detail_key = key or "phase.idle"
        self._detail_fields = fields or {}

        color = theme.PHASE_COLORS.get(phase, theme.TEXT_MUTED)

        self._dot.setStyleSheet(theme.dot_style(color))
        self._status.setText(i18n.tr(self._detail_key, **self._detail_fields))
        self._status.setStyleSheet(f"color: {color};")

        transport = i18n.tr("session.transport")
        mode = i18n.tr("mode.wifi") if self.mode == MODE_WIFI else i18n.tr("mode.usb")
        phase_text = i18n.tr(f"phaseName.{phase}")

        self._transport.setText(f"{transport}: {mode}  ·  {phase_text}")

    def set_connected(self, connected: bool) -> None:
        self._connected = connected
        self._applying = True
        self._connect.setChecked(connected)
        self._applying = False

        self._connect.setText(
            i18n.tr("action.disconnect") if connected
            else i18n.tr(
                "action.connectWifi" if self.mode == MODE_WIFI
                else "action.connectUsb"
            )
        )

        for widget in (self._host, self._wifi_port, self._devices,
                       self._remote_port, self._local_port, self._refresh,
                       self._wifi_button, self._usb_button):
            widget.setEnabled(not connected)

    def set_forward_state(self, active: bool) -> None:
        self._forward_active = active
        self._update_target()

    def refresh_adb(self) -> None:
        if not self._adb.available:
            self._adb_value.setText(i18n.tr("adb.missing"))
            self._adb_value.setStyleSheet(f"color: {theme.DANGER};")
            self._devices.clear()
            self._devices_empty = "adb.unavailable"
            self._devices.addItem(i18n.tr(self._devices_empty), "")
            self._devices.setEnabled(False)
            self._command.setText("")
            self._forward_state.setText(i18n.tr("adb.forwardMissing"))
            return

        self._adb_value.setText(i18n.tr("adb.probing"))
        self._adb_value.setStyleSheet(f"color: {theme.TEXT_MUTED};")
        self._refresh.setEnabled(False)

        self._busy_task = BackgroundTask(self._adb.devices)
        self._busy_task.finished_with.connect(self._apply_devices)
        self._busy_task.start()

    def _apply_devices(self, result) -> None:
        self._refresh.setEnabled(True)
        self._adb_value.setText(i18n.tr("adb.ready"))
        self._adb_value.setStyleSheet(f"color: {theme.ACCENT};")

        previous = self.current_serial()
        self._devices.blockSignals(True)
        self._devices.clear()

        if isinstance(result, Exception) or not result:
            self._devices_empty = "adb.none"
            self._devices.addItem(i18n.tr(self._devices_empty), "")
            self._devices.blockSignals(False)
            self._sync_page_height()
            self._update_target()
            return

        self._devices_empty = ""

        for device in result:
            label = device.model or device.serial

            if device.online:
                label += f"  ·  {device.serial[:10]}"

            self._devices.addItem(label, device.serial if device.online else "")

        if previous:
            index = self._devices.findData(previous)

            if index >= 0:
                self._devices.setCurrentIndex(index)

        self._devices.blockSignals(False)
        self._sync_page_height()
        self._update_target()

    def _on_toggled(self, checked: bool) -> None:
        if self._applying:
            return

        if checked:
            self.connect_requested.emit(self.request())
        else:
            self.disconnect_requested.emit()

    def _request_connect(self) -> None:
        if not self._connected:
            self._connect.click()

    def _update_target(self) -> None:
        req = self.request()

        if self.mode == MODE_WIFI:
            filled = self._host.text().strip()
            self._target.setText(
                req.url if filled else i18n.tr("action.needHost")
            )
            self._connect.setEnabled(bool(filled))
            self._forward_state.setText(i18n.tr("forward.unused"))
            self._forward_state.setStyleSheet(f"color: {theme.TEXT_MUTED};")
            self._command.setText("")
            return

        self._connect.setEnabled(bool(req.serial))
        self._target.setText(
            req.url if req.serial else i18n.tr("action.needDevice")
        )

        mapping = f"tcp:{req.local_port} → tcp:{req.remote_port}"

        if self._forward_active:
            self._forward_state.setText(
                f"{mapping}  ·  {i18n.tr('forward.active')}"
            )
            self._forward_state.setStyleSheet(f"color: {theme.ACCENT};")
        else:
            self._forward_state.setText(mapping)
            self._forward_state.setStyleSheet(f"color: {theme.TEXT_MUTED};")

        self._command.setText(
            f"adb {'-s ' + req.serial + ' ' if req.serial else ''}"
            f"forward {mapping.replace(' → ', ' ')}"
        )

    def host_text(self) -> str:
        return self._host.text().strip()

    def wifi_port_value(self) -> int:
        return self._wifi_port.value()

    def apply_cli_target(self, mode: str, host: str | None,
                         port: int) -> None:
        if mode == MODE_USB:
            self._remote_port.setValue(port)
            self._select_mode(MODE_USB)
            return

        if host:
            self._host.setText(host)

        self._wifi_port.setValue(port)
        self._select_mode(MODE_WIFI)

    def _restore(self) -> None:
        host = self._settings.value("wifi/host", "")
        port = self._settings.value("wifi/port", DEFAULT_PHONE_PORT)

        if host:
            self._host.setText(str(host))

        try:
            self._wifi_port.setValue(int(port))
        except (TypeError, ValueError):
            pass

        self._host.textChanged.connect(self._persist)
        self._wifi_port.valueChanged.connect(self._persist)

        self._devices.currentIndexChanged.connect(self._update_target)
        self._remote_port.valueChanged.connect(self._update_target)
        self._local_port.valueChanged.connect(self._update_target)

        self._host.textChanged.connect(self._update_target)
        self._wifi_port.valueChanged.connect(self._update_target)

    def _persist(self, *_args) -> None:
        self._settings.setValue("wifi/host", self._host.text().strip())
        self._settings.setValue("wifi/port", self._wifi_port.value())
