import time

import cv2
import numpy as np

OPEN_TIMEOUT_MS = 3000
READ_TIMEOUT_MS = 4000


class PhoneCameraReceiver:
    def __init__(self, url: str):
        self.url = url
        self.cap = None
        self.width = 0
        self.height = 0

    def connect(self, timeout: float = 8.0,
                should_stop=None) -> bool:
        deadline = time.monotonic() + timeout
        params = [
            cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, OPEN_TIMEOUT_MS,
            cv2.CAP_PROP_READ_TIMEOUT_MSEC, READ_TIMEOUT_MS,
        ]

        while time.monotonic() < deadline:
            if should_stop is not None and should_stop():
                break

            cap = cv2.VideoCapture(self.url, cv2.CAP_FFMPEG, params)

            if cap.isOpened():
                ret, frame = cap.read()

                if ret and frame is not None:
                    self.cap = cap
                    self.height, self.width = frame.shape[:2]
                    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
                    return True

            cap.release()
            time.sleep(0.2)

        self.cap = None
        return False

    def read(self) -> tuple[bool, np.ndarray | None]:
        if self.cap is None:
            return False, None

        return self.cap.read()

    def release(self) -> None:
        if self.cap is not None:
            self.cap.release()
            self.cap = None

        self.width = 0
        self.height = 0
