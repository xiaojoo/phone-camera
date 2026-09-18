import time

from PySide6.QtCore import QThread, Signal
from PySide6.QtGui import QImage

from camera_receiver import PhoneCameraReceiver


class Phase:
    IDLE = "idle"
    CONNECTING = "connecting"
    LIVE = "live"
    RECONNECTING = "reconnecting"
    FAILED = "failed"
    STOPPED = "stopped"


class CaptureWorker(QThread):
    frame_ready = Signal(QImage)
    telemetry_ready = Signal(dict)
    phase_changed = Signal(str, str, dict)

    MAX_CONNECT_ATTEMPTS = 3
    RECONNECT_DELAY = 1.0
    STOP_WAIT_MS = 9000

    def __init__(self, parent=None):
        super().__init__(parent)

        self._url = ""
        self._stop_requested = False

    def start_stream(self, url: str) -> bool:
        if self.isRunning():
            self._stop_requested = True

            if not self.wait(self.STOP_WAIT_MS):
                self.phase_changed.emit(
                    Phase.FAILED, "phase.closing", {},
                )
                return False

        self._url = url
        self._stop_requested = False
        self.start()
        return True

    def stop_stream(self) -> None:
        self._stop_requested = True

        if self.isRunning():
            self.wait(self.STOP_WAIT_MS)

    def run(self) -> None:
        url = self._url
        receiver = PhoneCameraReceiver(url)
        attempts = 0
        connected_once = False

        while not self._stop_requested:
            attempts += 1

            if attempts == 1 and not connected_once:
                self.phase_changed.emit(
                    Phase.CONNECTING, "phase.connecting", {"url": url}
                )
            else:
                self.phase_changed.emit(
                    Phase.RECONNECTING,
                    "phase.reconnecting",
                    {"n": attempts, "total": self.MAX_CONNECT_ATTEMPTS,
                     "url": url},
                )

            if not receiver.connect(timeout=8.0,
                                    should_stop=lambda: self._stop_requested):
                if self._stop_requested:
                    break

                if attempts >= self.MAX_CONNECT_ATTEMPTS:
                    self.phase_changed.emit(
                        Phase.FAILED, "phase.failed", {"url": url}
                    )
                    return

                time.sleep(self.RECONNECT_DELAY)
                continue

            attempts = 0
            connected_once = True
            self.phase_changed.emit(Phase.LIVE, "phase.live", {"url": url})
            self._pump_frames(receiver)

            if not self._stop_requested:
                self.phase_changed.emit(
                    Phase.RECONNECTING, "phase.interrupted", {}
                )

        receiver.release()
        self.phase_changed.emit(Phase.STOPPED, "phase.stopped", {})

    def _pump_frames(self, receiver: PhoneCameraReceiver) -> None:
        fps = 0.0
        frames = 0
        last_read = time.perf_counter()
        started = last_read
        last_emit = last_read

        while not self._stop_requested:
            ret, frame = receiver.read()

            if not ret or frame is None:
                break

            now = time.perf_counter()
            interval = now - last_read
            last_read = now

            if interval > 0:
                instantaneous = 1.0 / interval

                if fps == 0.0:
                    fps = instantaneous
                else:
                    fps = fps * 0.9 + instantaneous * 0.1

            self.frame_ready.emit(self._to_qimage(frame))
            frames += 1

            if now - last_emit >= 0.25:
                last_emit = now

                self.telemetry_ready.emit({
                    "fps": fps,
                    "frames": frames,
                    "width": receiver.width,
                    "height": receiver.height,
                    "uptime": now - started,
                    "frame_ms": interval * 1000.0,
                })

        receiver.release()

    @staticmethod
    def _to_qimage(frame):
        height, width = frame.shape[:2]

        image = QImage(
            frame.data,
            width,
            height,
            frame.strides[0],
            QImage.Format.Format_BGR888,
        )

        return image.copy()
