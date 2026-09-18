from datetime import datetime
from pathlib import Path

from PySide6.QtCore import Qt, QSettings
from PySide6.QtGui import QAction, QKeySequence
from PySide6.QtWidgets import (
    QFileDialog,
    QHBoxLayout,
    QLabel,
    QMainWindow,
    QMessageBox,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

import i18n
import theme
from adb_bridge import AdbBridge
from capture_worker import CaptureWorker, Phase
from connection_panel import (
    MODE_WIFI,
    BackgroundTask,
    ConnectionPanel,
    ConnectionRequest,
)
from metrics_bar import MetricsBar
from video_view import VideoView


class BridgeWindow(QMainWindow):
    def __init__(self, settings: QSettings, adb: AdbBridge):
        super().__init__()

        self.setWindowTitle(i18n.tr("app.title"))
        self.resize(1220, 720)
        self.setMinimumSize(1080, 640)

        self._settings = settings
        self._adb = adb
        self._active: ConnectionRequest | None = None
        self._forward: tuple[int, str] | None = None
        self._forward_pending = False
        self._forward_task: BackgroundTask | None = None
        self._status_key = "status.ready"
        self._status_fields: dict = {}

        self._build_ui()
        self._apply_shortcuts()

        self._worker = CaptureWorker(self)
        self._worker.frame_ready.connect(self._video.set_frame)
        self._worker.telemetry_ready.connect(self._metrics.update_telemetry)
        self._worker.phase_changed.connect(self._on_phase)

        self._video.set_state(Phase.IDLE, "placeholder.idleDetail")
        self._set_status("status.chooseMode")

    # ---------------- layout ----------------

    def _build_ui(self) -> None:
        root = QWidget()
        root.setObjectName("Root")

        shell = QHBoxLayout(root)
        shell.setContentsMargins(0, 0, 0, 0)
        shell.setSpacing(0)

        self._panel = ConnectionPanel(self._adb, self._settings)
        self._panel.connect_requested.connect(self._start_stream)
        self._panel.disconnect_requested.connect(self._stop_stream)

        shell.addWidget(self._panel)
        shell.addWidget(self._build_stage())

        self.setCentralWidget(root)

        self._status = QLabel(i18n.tr("status.ready"))
        self.statusBar().addWidget(self._status, 1)

    def _build_stage(self) -> QWidget:
        stage = QWidget()
        stage.setObjectName("Stage")

        layout = QVBoxLayout(stage)
        layout.setContentsMargins(0, 18, 22, 18)
        layout.setSpacing(12)

        header = QHBoxLayout()
        header.setSpacing(10)

        self._stage_title = QLabel(i18n.tr("view.live"))
        self._stage_title.setStyleSheet(
            f"font-size: 15px; font-weight: 700; color: {theme.TEXT};"
        )

        self._stage_subtitle = QLabel(i18n.tr("app.subtitle"))
        self._stage_subtitle.setObjectName("BodyCaption")

        self._snapshot = QPushButton(i18n.tr("view.snapshot"))
        self._snapshot.setObjectName("IconToggle")
        self._snapshot.setToolTip(i18n.tr("view.snapshotTip"))
        self._snapshot.clicked.connect(self._save_snapshot)

        self._fullscreen = QPushButton(i18n.tr("view.fullscreen"))
        self._fullscreen.setObjectName("IconToggle")
        self._fullscreen.setToolTip(i18n.tr("view.fullscreenTip"))
        self._fullscreen.setCheckable(True)
        self._fullscreen.toggled.connect(self._toggle_fullscreen)

        self._language = QPushButton(i18n.tr("lang.toggle"))
        self._language.setObjectName("IconToggle")
        self._language.setCursor(Qt.PointingHandCursor)
        self._language.clicked.connect(self._switch_language)

        header.addWidget(self._stage_title)
        header.addSpacing(10)
        header.addWidget(self._stage_subtitle)
        header.addStretch(1)
        header.addWidget(self._language)
        header.addWidget(self._snapshot)
        header.addWidget(self._fullscreen)
        layout.addLayout(header)

        self._video = VideoView()
        layout.addWidget(self._video, 1)

        self._metrics = MetricsBar()
        layout.addWidget(self._metrics)

        return stage

    def _apply_shortcuts(self) -> None:
        connect_action = QAction("Connect / Disconnect", self)
        connect_action.setShortcut(QKeySequence("Ctrl+K"))
        connect_action.triggered.connect(self._toggle_connection)
        self.addAction(connect_action)

        full_screen_action = QAction("Toggle full screen", self)
        full_screen_action.setShortcut(QKeySequence("F11"))
        full_screen_action.triggered.connect(self._fullscreen.toggle)
        self.addAction(full_screen_action)

    # ---------------- connection ----------------

    def _toggle_connection(self) -> None:
        if self._active is None:
            self._start_stream(self._panel.request())
        else:
            self._stop_stream()

    def _start_stream(self, request: ConnectionRequest) -> None:
        self._active = request
        self._panel.set_connected(True)
        self._metrics.reset("…")

        if request.mode == MODE_WIFI:
            self._panel.set_phase(
                Phase.CONNECTING, "phase.connecting", {"url": request.url}
            )
            self._worker.start_stream(request.url)
            return

        if not self._adb.available:
            self._fail("error.adbMissing")
            return

        if not request.serial:
            self._fail("error.noDevice")
            return

        self._panel.set_phase(
            Phase.CONNECTING,
            "phase.forwarding",
            {"local": request.local_port, "remote": request.remote_port},
        )

        self._forward = (request.local_port, request.serial)
        self._forward_pending = True
        self._forward_task = BackgroundTask(
            self._adb.forward,
            request.local_port,
            request.remote_port,
            request.serial,
        )
        self._forward_task.finished_with.connect(self._on_forward_done)
        self._forward_task.start()

    def _on_forward_done(self, ok) -> None:
        self._forward_pending = False
        request = self._active

        if request is None:
            self._teardown_forward()
            return

        if isinstance(ok, bool) and ok:
            self._panel.set_forward_state(True)
            self._worker.start_stream(request.url)
            return

        self._forward = None
        self._panel.set_forward_state(False)
        self._fail("error.forward", {"detail": self._adb.error.strip()})

    def _stop_stream(self) -> None:
        self._active = None

        self._worker.stop_stream()
        self._panel.set_connected(False)
        self._panel.set_phase(Phase.STOPPED, "phase.stopped", {})
        self._metrics.reset()

        self._teardown_forward()

    def _teardown_forward(self) -> None:
        if self._forward_pending:
            return

        forward = self._forward
        self._forward = None

        if forward is None or not self._adb.available:
            return

        self._panel.set_forward_state(False)
        self._forward_task = BackgroundTask(
            self._adb.remove_forward,
            forward[0],
            forward[1],
        )
        self._forward_task.start()

    def _fail(self, key: str, fields: dict | None = None) -> None:
        fields = fields or {}

        self._active = None
        self._status_key = key
        self._status_fields = fields
        self._panel.set_connected(False)
        self._panel.set_phase(Phase.FAILED, key, fields)
        self._video.set_state(Phase.FAILED, key, fields)
        self._status.setText(i18n.tr(key, **fields))

    def _on_phase(self, phase: str, key: str, fields: dict) -> None:
        self._status_key = key
        self._status_fields = fields

        self._panel.set_phase(phase, key, fields)
        self._video.set_state(phase, key, fields)
        self._status.setText(i18n.tr(key, **fields))

        if phase in (Phase.FAILED, Phase.STOPPED):
            self._metrics.reset()

            if self._active is not None:
                self._active = None
                self._panel.set_connected(False)
                self._teardown_forward()

    # ---------------- language ----------------

    def _switch_language(self) -> None:
        i18n.set_code(i18n.EN if i18n.code() == i18n.ZH else i18n.ZH)
        self._settings.setValue("ui/language", i18n.code())
        self.retranslate()

    def retranslate(self) -> None:
        self.setWindowTitle(i18n.tr("app.title"))

        self._stage_title.setText(i18n.tr("view.live"))
        self._stage_subtitle.setText(i18n.tr("app.subtitle"))
        self._snapshot.setText(i18n.tr("view.snapshot"))
        self._snapshot.setToolTip(i18n.tr("view.snapshotTip"))
        self._fullscreen.setText(i18n.tr("view.fullscreen"))
        self._fullscreen.setToolTip(i18n.tr("view.fullscreenTip"))
        self._language.setText(i18n.tr("lang.toggle"))

        self._panel.retranslate()
        self._metrics.retranslate()
        self._video.retranslate()

        self._status.setText(i18n.tr(self._status_key, **self._status_fields))

    # ---------------- view actions ----------------

    def _set_status(self, key: str, **fields) -> None:
        self._status_key = key
        self._status_fields = fields
        self._status.setText(i18n.tr(key, **fields))

    def _save_snapshot(self) -> None:
        image = self._video.snapshot()

        if image is None:
            self._set_status("status.noFrame")
            return

        folder = Path.cwd() / "snapshots"
        folder.mkdir(exist_ok=True)

        default = folder / f"camera_{datetime.now():%Y%m%d_%H%M%S}.png"

        chosen, _ = QFileDialog.getSaveFileName(
            self,
            i18n.tr("view.snapshot"),
            str(default),
            "PNG (*.png);;JPEG (*.jpg)",
        )

        if not chosen:
            return

        if image.save(chosen):
            self._set_status("status.snapshotSaved", path=chosen)
        else:
            QMessageBox.warning(
                self, i18n.tr("view.snapshot"), i18n.tr("status.snapshotFailed")
            )

    def _toggle_fullscreen(self, enabled: bool) -> None:
        if enabled:
            self.showFullScreen()
        else:
            self.showNormal()

    def showEvent(self, event) -> None:
        super().showEvent(event)
        theme.apply_dark_title_bar(int(self.winId()))

    def keyPressEvent(self, event) -> None:
        if event.key() == Qt.Key_Escape and self.isFullScreen():
            self._fullscreen.setChecked(False)
            return

        super().keyPressEvent(event)

    def closeEvent(self, event) -> None:
        self._worker.stop_stream()

        if self._forward_task is not None:
            self._forward_task.wait(3000)

        forward = self._forward
        self._forward = None

        if forward is not None and self._adb.available:
            self._adb.remove_forward(forward[0], forward[1])

        self._settings.setValue("wifi/host", self._panel.host_text())
        self._settings.setValue("wifi/port", self._panel.wifi_port_value())

        event.accept()

    def apply_cli_target(self, mode: str, host: str | None,
                         port: int) -> None:
        self._panel.apply_cli_target(mode, host, port)
