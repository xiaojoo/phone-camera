import os
import subprocess
import time
from dataclasses import dataclass

creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)


@dataclass(frozen=True)
class AdbDevice:
    serial: str
    state: str
    model: str = ""

    @property
    def online(self) -> bool:
        return self.state == "device"


def _candidate_paths() -> list[str]:
    roots = []

    for env_key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(env_key)
        if value:
            roots.append(value)

    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        roots.append(os.path.join(local_app_data, "Android", "Sdk"))

    exe = "adb.exe" if os.name == "nt" else "adb"

    paths = [exe]
    paths += [os.path.join(root, "platform-tools", exe) for root in roots]

    return paths


class AdbBridge:
    def __init__(self, adb_path: str | None = None):
        self.adb_path = adb_path or _resolve(adb_path)
        self.error = ""

    @property
    def available(self) -> bool:
        return self.adb_path is not None

    def _run(self, *args: str, timeout: float = 6.0) -> tuple[bool, str]:
        if self.adb_path is None:
            self.error = "adb not found"
            return False, ""

        try:
            completed = subprocess.run(
                [self.adb_path, *args],
                capture_output=True,
                text=True,
                timeout=timeout,
                creationflags=creationflags,
            )
        except (OSError, subprocess.SubprocessError) as exc:
            self.error = str(exc)
            return False, ""

        return completed.returncode == 0, (completed.stdout or "").strip()

    def devices(self) -> list[AdbDevice]:
        ok, output = self._run("devices", "-l")

        if not ok:
            return []

        found = []

        for line in output.splitlines()[1:]:
            line = line.strip()

            if not line:
                continue

            parts = line.split()
            serial, state = parts[0], parts[1]
            model = ""

            for part in parts[2:]:
                if part.startswith("model:"):
                    model = part[len("model:"):].replace("_", " ")

            found.append(AdbDevice(serial, state, model))

        return found

    def wait_for_device(self, timeout: float = 8.0) -> AdbDevice | None:
        deadline = time.monotonic() + timeout

        while time.monotonic() < deadline:
            for device in self.devices():
                if device.online:
                    return device

            time.sleep(0.4)

        return None

    def forward(self, local_port: int, remote_port: int,
                serial: str | None = None) -> bool:
        base = _forward_args(serial)

        self._run(*base, "forward", "--remove", f"tcp:{local_port}")

        ok, _ = self._run(*base, "forward",
                          f"tcp:{local_port}", f"tcp:{remote_port}")

        return ok

    def remove_forward(self, local_port: int,
                       serial: str | None = None) -> None:
        self._run(*_forward_args(serial), "forward", "--remove", f"tcp:{local_port}")


def _forward_args(serial: str | None) -> tuple[str, ...]:
    return ("-s", serial) if serial else ()


def _resolve(explicit: str | None) -> str | None:
    if explicit:
        return explicit if os.path.isfile(explicit) else None

    for candidate in _candidate_paths():
        try:
            subprocess.run(
                [candidate, "version"],
                capture_output=True,
                text=True,
                timeout=4,
                creationflags=creationflags,
            )
        except (OSError, subprocess.SubprocessError):
            continue

        return candidate

    return None
