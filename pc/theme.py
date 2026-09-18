import sys

from PySide6.QtGui import QPalette

BG = "#0D0E10"
PANEL = "#15171B"
CARD = "#1C1F25"
CARD_HOVER = "#22262D"
BORDER = "#272B33"
BORDER_STRONG = "#343A44"

TEXT = "#F1F4F8"
TEXT_SECONDARY = "#9AA3AF"
TEXT_MUTED = "#626B77"

ACCENT = "#4CAF50"
ACCENT_DIM = "#2E5730"
INFO = "#4A9EFF"
WARN = "#F0A73C"
DANGER = "#E5534B"

MONO = '"Cascadia Mono", "Consolas", "DejaVu Sans Mono", monospace'

STYLESHEET = f"""
QWidget {{
    background: {BG};
    color: {TEXT};
    font-family: "Segoe UI Variable Text", "Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif;
    font-size: 13px;
}}

QMainWindow, QDialog {{ background: {BG}; }}

QLabel {{ background: transparent; }}

#SidePanel {{
    background: {PANEL};
    border-right: 1px solid {BORDER};
}}

#SectionLabel {{
    color: {TEXT_MUTED};
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 1.4px;
    background: transparent;
    padding-bottom: 2px;
}}

#Card {{
    background: {CARD};
    border: 1px solid {BORDER};
    border-radius: 10px;
}}

#MetricCell {{
    background: transparent;
    border: none;
}}

#VideoStage {{
    background: #000000;
    border: 1px solid {BORDER};
    border-radius: 12px;
}}

/* ---------- segmented mode switch ---------- */

#ModeSwitch {{
    background: {CARD};
    border: 1px solid {BORDER};
    border-radius: 9px;
    padding: 3px;
}}

#ModeButton {{
    background: transparent;
    border: none;
    border-radius: 6px;
    color: {TEXT_SECONDARY};
    font-size: 12px;
    font-weight: 600;
    padding: 7px 0px;
}}

#ModeButton:hover {{ color: {TEXT}; }}

#ModeButton:disabled {{ color: {TEXT_MUTED}; }}

#ModeButton:checked:disabled {{ color: {TEXT_SECONDARY}; }}

#ModeButton:checked {{
    background: {BORDER_STRONG};
    color: {TEXT};
}}

/* ---------- inputs ---------- */

QLineEdit, QComboBox, QSpinBox {{
    background: {CARD};
    border: 1px solid {BORDER};
    border-radius: 7px;
    color: {TEXT};
    padding: 7px 10px;
    selection-background-color: {ACCENT_DIM};
}}

QLineEdit:focus, QComboBox:focus, QSpinBox:focus {{
    border: 1px solid {ACCENT};
}}

QLineEdit:disabled, QSpinBox:disabled {{
    color: {TEXT_MUTED};
    background: {PANEL};
}}

QComboBox::drop-down {{
    border: none;
    width: 22px;
}}

QComboBox QAbstractItemView {{
    background: {CARD};
    border: 1px solid {BORDER_STRONG};
    border-radius: 6px;
    outline: none;
    color: {TEXT_SECONDARY};
    /* 高亮条由 DeviceCombo 的 delegate 画，样式那边不要再用调色板画方块 */
    selection-background-color: transparent;
    selection-color: {TEXT};
}}

#AddressField {{ font-family: {MONO}; }}

/* ---------- buttons ---------- */

QPushButton {{
    background: {CARD};
    border: 1px solid {BORDER};
    border-radius: 7px;
    color: {TEXT_SECONDARY};
    font-size: 12px;
    font-weight: 600;
    padding: 7px 12px;
}}

QPushButton:hover {{
    background: {CARD_HOVER};
    color: {TEXT};
    border-color: {BORDER_STRONG};
}}

QPushButton:pressed {{ background: {BORDER}; }}

QPushButton:disabled {{
    color: {TEXT_MUTED};
    background: {PANEL};
    border-color: {BORDER};
}}

#IconToggle {{
    background: transparent;
    border: 1px solid {BORDER};
    border-radius: 16px;
    color: {TEXT_SECONDARY};
    font-size: 11px;
    font-weight: 600;
    padding: 0px 13px;
    min-height: 32px;
    max-height: 32px;
}}

#IconToggle:checked {{
    background: {ACCENT_DIM};
    border-color: {ACCENT};
    color: #D6F0D7;
}}

#PrimaryAction {{
    background: {ACCENT};
    border: none;
    color: #06170A;
    font-size: 13px;
    font-weight: 700;
    padding: 11px 14px;
    border-radius: 8px;
}}

#PrimaryAction:hover {{ background: #5CBF60; }}
#PrimaryAction:pressed {{ background: #43943F; }}

#PrimaryAction:disabled {{
    background: {CARD};
    color: {TEXT_MUTED};
}}

#PrimaryAction:checked {{
    background: {DANGER};
    color: #1D0A09;
}}

#PrimaryAction:checked:hover {{ background: #EF6760; }}

/* ---------- readouts ---------- */

#MetricValue {{
    font-family: {MONO};
    font-size: 13px;
    font-weight: 600;
    color: {TEXT};
    background: transparent;
}}

#MetricUnit {{
    font-family: "Segoe UI", sans-serif;
    font-size: 10px;
    font-weight: 600;
    color: {TEXT_MUTED};
    letter-spacing: 0.6px;
    background: transparent;
}}

#MonoCaption {{
    font-family: {MONO};
    font-size: 11px;
    color: {TEXT_SECONDARY};
    background: transparent;
}}

#BodyCaption {{
    color: {TEXT_SECONDARY};
    font-size: 12px;
    background: transparent;
}}

#StatusDot {{
    background: {TEXT_MUTED};
    border-radius: 4px;
    min-width: 8px;
    max-width: 8px;
    min-height: 8px;
    max-height: 8px;
}}

QScrollBar:vertical {{
    background: transparent;
    width: 9px;
    margin: 2px;
}}

QScrollBar::handle:vertical {{
    background: {BORDER_STRONG};
    border-radius: 3px;
    min-height: 26px;
}}

QScrollBar::add-line, QScrollBar::sub-line {{ height: 0; width: 0; }}

QStatusBar {{
    background: {PANEL};
    border-top: 1px solid {BORDER};
    color: {TEXT_MUTED};
    font-size: 11px;
}}

QStatusBar::item {{ border: none; }}

/* 状态栏里的 QLabel 会被全局 QWidget 规则直接命中，父级的 font-size 传不下去；
   QStatusBar 自己的 padding 又不作用于条目布局，所以留白加在 label 上 */
QStatusBar QLabel {{
    font-size: 11px;
    padding: 4px 0px 4px 10px;
}}

QToolTip {{
    background: {CARD};
    color: {TEXT};
    border: 1px solid {BORDER_STRONG};
    padding: 5px 8px;
}}
"""

PHASE_COLORS = {
    "idle": TEXT_MUTED,
    "connecting": WARN,
    "live": ACCENT,
    "reconnecting": WARN,
    "failed": DANGER,
    "stopped": TEXT_MUTED,
}


def dot_style(color: str) -> str:
    return f"background: {color}; border-radius: 4px;"


def palette() -> QPalette:
    """样式表管不到的原生部件（下拉箭头、弹出列表滚动条）靠调色板取色。"""
    pal = QPalette()

    for role, hex_color in (
        (QPalette.ColorRole.Window, PANEL),
        (QPalette.ColorRole.Base, CARD),
        (QPalette.ColorRole.AlternateBase, CARD_HOVER),
        (QPalette.ColorRole.Button, PANEL),
        (QPalette.ColorRole.ToolTipBase, CARD),
        (QPalette.ColorRole.Text, TEXT),
        (QPalette.ColorRole.WindowText, TEXT),
        (QPalette.ColorRole.ButtonText, TEXT),
        (QPalette.ColorRole.PlaceholderText, TEXT_MUTED),
        (QPalette.ColorRole.ToolTipText, TEXT_SECONDARY),
        (QPalette.ColorRole.Highlight, ACCENT_DIM),
        (QPalette.ColorRole.HighlightedText, TEXT),
    ):
        pal.setColor(role, hex_color)

    return pal


def _colorref(hex_color: str) -> int:
    r, g, b = (int(hex_color[i:i + 2], 16) for i in (1, 3, 5))
    return b << 16 | g << 8 | r


def apply_dark_title_bar(hwnd: int) -> None:
    """Qt 管不到非客户区，标题栏只能让 Windows 的 DWM 来染色。"""
    if sys.platform != "win32":
        return

    from ctypes import byref, c_int, c_ulong, sizeof, windll

    dark = c_int(1)
    windll.dwmapi.DwmSetWindowAttribute(hwnd, 20, byref(dark), sizeof(dark))

    for attribute, hex_color in ((35, BG), (36, TEXT)):
        # Windows 10 上没有这两个属性，失败直接忽略
        color = c_ulong(_colorref(hex_color))
        windll.dwmapi.DwmSetWindowAttribute(
            hwnd, attribute, byref(color), sizeof(color)
        )
