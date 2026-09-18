from PySide6.QtCore import QPointF, QRectF, QSize, Qt
from PySide6.QtGui import (
    QColor,
    QFont,
    QFontMetrics,
    QImage,
    QPainter,
    QPainterPath,
    QPen,
)
from PySide6.QtWidgets import QSizePolicy, QWidget

import i18n
import theme


class VideoView(QWidget):
    MIN_SIZE = QSize(480, 270)

    def __init__(self, parent=None):
        super().__init__(parent)

        self.setObjectName("VideoStage")
        self.setMinimumSize(self.MIN_SIZE)
        self.setSizePolicy(
            QSizePolicy.Policy.Expanding,
            QSizePolicy.Policy.Expanding,
        )
        self.setAttribute(Qt.WidgetAttribute.WA_OpaquePaintEvent)

        self._image: QImage | None = None
        self._phase = "idle"
        self._headline_key = "placeholder.idleTitle"
        self._detail_key = "placeholder.idleDetail"
        self._fields: dict = {}

    def set_frame(self, image: QImage) -> None:
        self._image = image
        self._phase = "live"
        self._detail_key = ""
        self._fields = {}

        self.update()

    def set_state(self, phase: str, key: str = "",
                  fields: dict | None = None) -> None:
        self._phase = phase
        self._headline_key = i18n.PHASE_TITLES.get(phase, "placeholder.idleTitle")
        self._detail_key = key
        self._fields = fields or {}

        if phase != "live":
            self._image = None

        self.update()

    def retranslate(self) -> None:
        self.update()

    def snapshot(self) -> QImage | None:
        return self._image

    def sizeHint(self) -> QSize:
        return QSize(960, 540)

    def paintEvent(self, event) -> None:
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)

        bounds = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        radius = 12.0

        stage = QPainterPath()
        stage.addRoundedRect(bounds, radius, radius)

        painter.setPen(QPen(QColor(theme.BORDER)))
        painter.setBrush(QColor("#000000"))
        painter.drawPath(stage)

        image = self._image

        if image is not None:
            painter.save()
            painter.setClipPath(stage)
            painter.drawPath(stage)

            scaled = image.scaled(
                self.size(),
                Qt.KeepAspectRatio,
                Qt.SmoothTransformation,
            )

            x = (self.width() - scaled.width()) // 2
            y = (self.height() - scaled.height()) // 2

            painter.drawImage(x, y, scaled)
            painter.restore()

            self._paint_live_badge(painter)
        else:
            painter.setClipPath(stage)
            self._paint_placeholder(painter, bounds)

    def _paint_placeholder(self, painter: QPainter, bounds: QRectF) -> None:
        accent = QColor(theme.PHASE_COLORS.get(self._phase, theme.TEXT_MUTED))

        ring_rect = QRectF(0, 0, 74, 74)
        ring_rect.moveCenter(bounds.center() + QPointF(0, -34))

        pen = QPen(accent)
        pen.setWidthF(1.6)
        pen.setStyle(
            Qt.SolidLine if self._phase in ("connecting", "reconnecting")
            else Qt.DashLine
        )

        painter.setPen(pen)
        painter.setBrush(Qt.NoBrush)
        painter.drawEllipse(ring_rect)

        self._paint_stage_glyph(painter, ring_rect, accent)

        title_font = QFont(self.font())
        title_font.setPointSizeF(13.5)
        title_font.setWeight(QFont.Weight.DemiBold)

        painter.setFont(title_font)
        painter.setPen(QPen(QColor(theme.TEXT)))
        painter.drawText(
            QRectF(bounds.left(), ring_rect.bottom() + 18,
                   bounds.width(), 26),
            Qt.AlignHCenter | Qt.AlignTop,
            i18n.tr(self._headline_key),
        )

        detail = i18n.tr(self._detail_key, **self._fields) if self._detail_key else ""

        if detail:
            detail_font = QFont(self.font())
            detail_font.setPointSizeF(10.5)

            painter.setFont(detail_font)
            painter.setPen(QPen(QColor(theme.TEXT_SECONDARY)))
            painter.drawText(
                QRectF(bounds.left() + 40, ring_rect.bottom() + 48,
                       bounds.width() - 80, 70),
                Qt.AlignHCenter | Qt.AlignTop | Qt.TextWordWrap,
                detail,
            )

    def _paint_stage_glyph(self, painter: QPainter, ring: QRectF,
                           color: QColor) -> None:
        center = ring.center()

        outer = QRectF(0, 0, ring.width() * 0.48, ring.height() * 0.48)
        outer.moveCenter(center)

        inner = QRectF(0, 0, outer.width() * 0.4, outer.height() * 0.4)
        inner.moveCenter(center)

        pen = QPen(color)
        pen.setWidthF(1.4)

        painter.setPen(pen)
        painter.setBrush(Qt.NoBrush)
        painter.drawEllipse(outer)

        painter.setPen(Qt.NoPen)
        painter.setBrush(QColor(color.red(), color.green(), color.blue(), 55))
        painter.drawEllipse(inner)

        if self._phase in ("failed", "stopped"):
            offset = ring.width() * 0.31

            pen.setWidthF(1.8)
            painter.setPen(pen)
            painter.setBrush(Qt.NoBrush)
            painter.drawLine(
                QPointF(center.x() - offset, center.y() + offset),
                QPointF(center.x() + offset, center.y() - offset),
            )

    def _paint_live_badge(self, painter: QPainter) -> None:
        label = i18n.tr("view.liveBadge")

        font = QFont(self.font())
        font.setPointSizeF(9.5)
        font.setWeight(QFont.Weight.Bold)
        font.setLetterSpacing(QFont.SpacingType.AbsoluteSpacing, 1.1)

        text_width = QFontMetrics(font).horizontalAdvance(label)

        chip = QRectF(14, 14, 10 + 7 + text_width + 12, 26)

        path = QPainterPath()
        path.addRoundedRect(chip, 13, 13)

        painter.setPen(Qt.NoPen)
        painter.setBrush(QColor(0, 0, 0, 150))
        painter.drawPath(path)

        dot = QRectF(chip.left() + 10, chip.center().y() - 3.5, 7, 7)

        painter.setBrush(QColor(theme.ACCENT))
        painter.drawEllipse(dot)

        painter.setFont(font)
        painter.setPen(QPen(QColor(theme.TEXT)))
        painter.drawText(
            QRectF(dot.right() + 7, chip.top(), chip.right() - dot.right() - 8,
                   chip.height()),
            Qt.AlignLeft | Qt.AlignVCenter,
            label,
        )
