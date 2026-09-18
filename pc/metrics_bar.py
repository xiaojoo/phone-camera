from PySide6.QtWidgets import (
    QFrame,
    QHBoxLayout,
    QLabel,
    QVBoxLayout,
)

import i18n

CAPTIONS = {
    "fps": "metric.fps",
    "frame_ms": "metric.frameMs",
    "resolution": "metric.size",
    "frames": "metric.frames",
    "uptime": "metric.uptime",
}


class MetricCell(QFrame):
    def __init__(self, key: str, placeholder: str = "—"):
        super().__init__()

        self.setObjectName("MetricCell")
        self._key = key

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(1)

        self.value = QLabel(placeholder)
        self.value.setObjectName("MetricValue")

        self.unit = QLabel(i18n.tr(key))
        self.unit.setObjectName("MetricUnit")

        layout.addWidget(self.value)
        layout.addWidget(self.unit)

    def set(self, text: str) -> None:
        if self.value.text() != text:
            self.value.setText(text)

    def retranslate(self) -> None:
        self.unit.setText(i18n.tr(self._key))


def divider() -> QFrame:
    line = QFrame()
    line.setFrameShape(QFrame.Shape.VLine)
    line.setFixedWidth(1)
    line.setStyleSheet("background: #272B33; border: none;")
    return line


class MetricsBar(QFrame):
    KEYS = tuple(CAPTIONS)

    def __init__(self, parent=None):
        super().__init__(parent)

        self.setObjectName("Card")

        layout = QHBoxLayout(self)
        layout.setContentsMargins(16, 11, 16, 11)
        layout.setSpacing(16)

        self._cells = {key: MetricCell(caption) for key, caption in CAPTIONS.items()}

        for index, key in enumerate(self.KEYS):
            if index:
                layout.addWidget(divider())

            layout.addWidget(self._cells[key], 1)

    def retranslate(self) -> None:
        for cell in self._cells.values():
            cell.retranslate()

    def update_telemetry(self, data: dict) -> None:
        self._cells["fps"].set(f"{data['fps']:.1f}")
        self._cells["frame_ms"].set(f"{data['frame_ms']:.1f}")
        self._cells["resolution"].set(
            f"{data['width']}x{data['height']}"
        )
        self._cells["frames"].set(f"{data['frames']:,}")
        self._cells["uptime"].set(format_duration(data["uptime"]))

    def reset(self, note: str = "—") -> None:
        for key in self.KEYS:
            self._cells[key].set(note)


def format_duration(seconds: float) -> str:
    total = int(seconds)
    hours, remainder = divmod(total, 3600)
    minutes, secs = divmod(remainder, 60)

    if hours:
        return f"{hours}:{minutes:02d}:{secs:02d}"

    return f"{minutes}:{secs:02d}"
