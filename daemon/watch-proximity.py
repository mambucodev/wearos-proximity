#!/usr/bin/env python3
"""
watch-proximity daemon
Monitors proximity of paired smartwatch (Pixel Watch) with multi-zone RSSI,
auto-wake display, anti-theft charger alarm, and on-wrist / unlock telemetry.

Features:
- Multi-Zone RSSI tracking:
  * Desk Zone (> +1 dB): Active presence, auto-wake, media resume, caffeine
  * Normal Zone (+1 to 0 dB): Safe near desk
  * Warning Zone (0 to -2 dB): Step-away warning notification
  * Away Zone (<= -3 dB or disconnected): Tolerance debounce strikes, screen lock, DPMS blank
- Auto-Wake on Approach: Powers on display backlight and resets idle monitor when returning to Desk
- Anti-Theft Charger Alarm: Siren alarm if AC charger unplugged while user is away
- Watch On-Body & Unlocked Telemetry: Native Wear OS integration
- MPRIS Auto-Pause and Auto-Resume
- Real-time status broadcasting to $XDG_RUNTIME_DIR/bt-proximity-status.json
- Non-blocking GNOME Shell Quick Settings & Top-Bar integration
"""

import os
import sys
import time
import json
import re
import signal
import logging
import argparse
import subprocess
import threading
from pathlib import Path

# Paths
DEFAULT_CONFIG_PATH = Path.home() / ".config" / "watch-proximity.conf"
LEGACY_CONFIG_PATH = Path.home() / ".config" / "bt-proximity-lock.conf"
RUNTIME_DIR = Path(os.environ.get("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}"))
STATUS_FILE = RUNTIME_DIR / "bt-proximity-status.json"
CONTROL_FILE = RUNTIME_DIR / "bt-proximity-control.json"
SNOOZE_FILE = RUNTIME_DIR / "bt-proximity-snooze"

# Default Configuration
DEFAULT_MAC = "64:9D:38:1A:4F:5A"
DEFAULT_DEVICE_NAME = "Pixel Watch 4"
DEFAULT_DESK_THRESHOLD = 1       # > 1 dB: Desk zone (> +1 dB triggers auto-wake)
DEFAULT_WARNING_THRESHOLD = -2   # 0 to -2 dB: Step-away warning zone
DEFAULT_AWAY_THRESHOLD = -3      # <= -3 dB: Away / lock candidate
DEFAULT_TOLERANCE_COUNT = 5      # Consecutive away samples required to trigger lock
DEFAULT_POLL_INTERVAL = 2.0      # Polling interval (seconds) when unlocked
DEFAULT_LOCKED_INTERVAL = 2.5    # Polling interval (seconds) when locked (for auto-wake & anti-theft)
DEFAULT_AUTO_WAKE = True         # Auto-wake screen when returning to desk
DEFAULT_PAUSE_MEDIA = True       # Pause media players on lock
DEFAULT_RESUME_MEDIA = True      # Resume media players on auto-wake
DEFAULT_ANTI_THEFT = True        # Sound alarm if AC charger unplugged while away
DEFAULT_ALARM_SOUND_PATH = "/run/current-system/sw/share/sounds/freedesktop/stereo/alarm-clock-elapsed.oga"

logger = logging.getLogger("watch-proximity")


def parse_config(config_path: Path) -> dict:
    """Load configuration from config file if present, falling back to defaults."""
    config = {
        "DEVICE_MAC": DEFAULT_MAC,
        "DEVICE_NAME": DEFAULT_DEVICE_NAME,
        "DESK_THRESHOLD": DEFAULT_DESK_THRESHOLD,
        "WARNING_THRESHOLD": DEFAULT_WARNING_THRESHOLD,
        "AWAY_THRESHOLD": DEFAULT_AWAY_THRESHOLD,
        "TOLERANCE_COUNT": DEFAULT_TOLERANCE_COUNT,
        "POLL_INTERVAL": DEFAULT_POLL_INTERVAL,
        "LOCKED_INTERVAL": DEFAULT_LOCKED_INTERVAL,
        "AUTO_WAKE": DEFAULT_AUTO_WAKE,
        "PAUSE_MEDIA": DEFAULT_PAUSE_MEDIA,
        "RESUME_MEDIA": DEFAULT_RESUME_MEDIA,
        "ANTI_THEFT": DEFAULT_ANTI_THEFT,
        "ALARM_SOUND_PATH": DEFAULT_ALARM_SOUND_PATH,
    }

    target_path = config_path if config_path.exists() else (LEGACY_CONFIG_PATH if LEGACY_CONFIG_PATH.exists() else None)
    if not target_path:
        return config

    try:
        with open(target_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                k = k.strip().upper()
                v = v.strip().strip('"').strip("'")
                if k == "DEVICE_MAC":
                    config["DEVICE_MAC"] = v
                elif k == "DEVICE_NAME":
                    config["DEVICE_NAME"] = v
                elif k in ("DESK_THRESHOLD", "RSSI_DESK"):
                    config["DESK_THRESHOLD"] = int(v)
                elif k in ("WARNING_THRESHOLD", "RSSI_WARNING"):
                    config["WARNING_THRESHOLD"] = int(v)
                elif k in ("AWAY_THRESHOLD", "RSSI_AWAY", "RSSI_THRESHOLD"):
                    config["AWAY_THRESHOLD"] = int(v)
                elif k == "TOLERANCE_COUNT":
                    config["TOLERANCE_COUNT"] = int(v)
                elif k == "POLL_INTERVAL":
                    config["POLL_INTERVAL"] = float(v)
                elif k == "LOCKED_INTERVAL":
                    config["LOCKED_INTERVAL"] = float(v)
                elif k == "AUTO_WAKE":
                    config["AUTO_WAKE"] = v.lower() in ("true", "1", "yes")
                elif k == "PAUSE_MEDIA":
                    config["PAUSE_MEDIA"] = v.lower() in ("true", "1", "yes")
                elif k == "RESUME_MEDIA":
                    config["RESUME_MEDIA"] = v.lower() in ("true", "1", "yes")
                elif k == "ANTI_THEFT":
                    config["ANTI_THEFT"] = v.lower() in ("true", "1", "yes")
                elif k == "ALARM_SOUND_PATH":
                    config["ALARM_SOUND_PATH"] = v
    except Exception as e:
        logger.warning("Could not read config file %s: %s", target_path, e)

    return config


def read_runtime_controls(config: dict) -> dict:
    """Check for dynamic overrides from the GNOME extension via CONTROL_FILE."""
    if not CONTROL_FILE.exists():
        return config
    try:
        data = json.loads(CONTROL_FILE.read_text(encoding="utf-8"))
        for key in ("AUTO_WAKE", "ANTI_THEFT", "PAUSE_MEDIA", "RESUME_MEDIA"):
            if key in data:
                config[key] = bool(data[key])
    except Exception:
        pass
    return config


def check_snooze() -> int:
    """Return remaining snooze seconds, or 0 if not snoozed."""
    if not SNOOZE_FILE.exists():
        return 0
    try:
        until = float(SNOOZE_FILE.read_text().strip())
        now = time.time()
        if now < until:
            return int(until - now)
        else:
            SNOOZE_FILE.unlink(missing_ok=True)
    except Exception:
        SNOOZE_FILE.unlink(missing_ok=True)
    return 0


def estimate_distance(rssi: int | None) -> str:
    """Convert Bluetooth Classic RSSI into approximate distance representation."""
    if rssi is None:
        return "Unknown"
    if rssi > 1:
        return "~0.5m (Desk)"
    elif rssi >= 0:
        return "~1.0m (Normal)"
    elif rssi >= -2:
        return "~2.0m (Warning)"
    elif rssi == -3:
        return "~3.0m (Away)"
    else:
        return "> 3.5m (Far Away)"


def send_notification(summary: str, body: str, urgency: str = "normal"):
    """Send native desktop notification via DBus."""
    try:
        urgency_code = "1" if urgency == "normal" else "2"
        subprocess.run(
            [
                "gdbus", "call", "--session",
                "--dest", "org.freedesktop.Notifications",
                "--object-path", "/org/freedesktop/Notifications",
                "--method", "org.freedesktop.Notifications.Notify",
                "watch-proximity", "0", "dialog-information",
                summary, body, "[]", f"{{'urgency': <byte {urgency_code}>}}", "4000"
            ],
            capture_output=True, timeout=2
        )
    except Exception:
        pass


def is_screen_locked() -> bool:
    """Check if the desktop screen is currently locked."""
    try:
        res = subprocess.run(
            [
                "dbus-send", "--session", "--dest=org.gnome.ScreenSaver",
                "--type=method_call", "--print-reply",
                "/org/gnome/ScreenSaver", "org.gnome.ScreenSaver.GetActive"
            ],
            capture_output=True, text=True, timeout=2
        )
        if res.returncode == 0:
            if "boolean true" in res.stdout:
                return True
            if "boolean false" in res.stdout:
                return False
    except Exception:
        pass

    try:
        res = subprocess.run(
            ["loginctl", "show-session", "auto", "-p", "LockedHint", "--value"],
            capture_output=True, text=True, timeout=2
        )
        if res.returncode == 0:
            return res.stdout.strip() == "yes"
    except Exception:
        pass

    return False


def is_ac_online() -> bool:
    """Check if laptop AC power charger is connected."""
    try:
        ac_path = Path("/sys/class/power_supply/AC/online")
        if ac_path.exists():
            return ac_path.read_text().strip() == "1"
    except Exception:
        pass
    return False


def wake_display():
    """Wake display backlight and trigger GNOME lock screen activity."""
    logger.info("Auto-wake: illuminating display backlight and resetting idle monitor...")
    try:
        subprocess.run(
            [
                "gdbus", "call", "--session",
                "--dest", "org.gnome.Mutter.DisplayConfig",
                "--object-path", "/org/gnome/Mutter/DisplayConfig",
                "--method", "org.freedesktop.DBus.Properties.Set",
                "org.gnome.Mutter.DisplayConfig", "PowerSaveMode", "<0>"
            ],
            capture_output=True, timeout=1
        )
    except Exception as e:
        logger.debug("Failed to set PowerSaveMode to 0: %s", e)

    try:
        subprocess.run(
            [
                "gdbus", "call", "--session",
                "--dest", "org.gnome.Mutter.IdleMonitor",
                "--object-path", "/org/gnome/Mutter/IdleMonitor/Core",
                "--method", "org.gnome.Mutter.IdleMonitor.ResetIdletime"
            ],
            capture_output=True, timeout=1
        )
    except Exception as e:
        logger.debug("Failed to reset idletime: %s", e)


def blank_display():
    """Turn display backlight off immediately upon screen lock to prevent shoulder-surfing."""
    try:
        subprocess.run(
            [
                "gdbus", "call", "--session",
                "--dest", "org.gnome.Mutter.DisplayConfig",
                "--object-path", "/org/gnome/Mutter/DisplayConfig",
                "--method", "org.freedesktop.DBus.Properties.Set",
                "org.gnome.Mutter.DisplayConfig", "PowerSaveMode", "<3>"
            ],
            capture_output=True, timeout=1
        )
    except Exception as e:
        logger.debug("Failed to set PowerSaveMode to 3: %s", e)


def get_playing_mpris_players() -> list[str]:
    """Find all active MPRIS media players currently playing."""
    players = []
    try:
        res = subprocess.run(
            [
                "gdbus", "call", "--session",
                "--dest", "org.freedesktop.DBus",
                "--object-path", "/org/freedesktop/DBus",
                "--method", "org.freedesktop.DBus.ListNames"
            ],
            capture_output=True, text=True, timeout=2
        )
        if res.returncode != 0:
            return players

        destinations = re.findall(r"'org\.mpris\.MediaPlayer2\.[^']+'", res.stdout)
        for dest in destinations:
            dest = dest.strip("'")
            stat_res = subprocess.run(
                [
                    "gdbus", "call", "--session",
                    "--dest", dest,
                    "--object-path", "/org/mpris/MediaPlayer2",
                    "--method", "org.freedesktop.DBus.Properties.Get",
                    "org.mpris.MediaPlayer2.Player", "PlaybackStatus"
                ],
                capture_output=True, text=True, timeout=1
            )
            if "Playing" in stat_res.stdout:
                players.append(dest)
    except Exception:
        pass
    return players


def pause_mpris_players(players: list[str]):
    """Pause specific MPRIS players."""
    for dest in players:
        try:
            subprocess.run(
                [
                    "gdbus", "call", "--session",
                    "--dest", dest,
                    "--object-path", "/org/mpris/MediaPlayer2",
                    "--method", "org.mpris.MediaPlayer2.Player.Pause"
                ],
                capture_output=True, timeout=1
            )
        except Exception:
            pass


def resume_mpris_players(players: list[str]):
    """Resume specific MPRIS players."""
    for dest in players:
        try:
            subprocess.run(
                [
                    "gdbus", "call", "--session",
                    "--dest", dest,
                    "--object-path", "/org/mpris/MediaPlayer2",
                    "--method", "org.mpris.MediaPlayer2.Player.Play"
                ],
                capture_output=True, timeout=1
            )
        except Exception:
            pass


def lock_screen():
    """Lock GNOME user session and blank display."""
    logger.info("Executing screen lock...")
    locked = False
    try:
        res = subprocess.run(
            [
                "dbus-send", "--session", "--type=method_call",
                "--dest=org.gnome.ScreenSaver",
                "/org/gnome/ScreenSaver", "org.gnome.ScreenSaver.Lock"
            ],
            capture_output=True, timeout=2
        )
        if res.returncode == 0:
            locked = True
    except Exception:
        pass

    if not locked:
        try:
            subprocess.run(["loginctl", "lock-session"], capture_output=True, timeout=2)
        except Exception as e:
            logger.error("Failed to execute loginctl lock-session: %s", e)

    blank_display()


def get_bluetooth_rssi(mac: str) -> tuple[bool, int | None]:
    """Query RSSI for the given Bluetooth MAC address."""
    try:
        res = subprocess.run(
            ["hcitool", "rssi", mac],
            capture_output=True, text=True, timeout=3
        )
        out = (res.stdout + res.stderr).strip()
        if "Not connected" in out or res.returncode != 0:
            try:
                subprocess.Popen(
                    ["bluetoothctl", "connect", mac],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
                )
            except Exception:
                pass
            return False, None

        match = re.search(r"RSSI return value:\s*(-?\d+)", out)
        if match:
            return True, int(match.group(1))

        return True, 0
    except subprocess.TimeoutExpired:
        return False, None
    except Exception as e:
        logger.debug("Error running hcitool rssi: %s", e)
        return False, None


def query_watch_security_state() -> tuple[bool | None, bool | None]:
    """
    Query Wear OS security telemetry via ADB if connected:
    Returns (unlocked: bool | None, on_body: bool | None).
    """
    try:
        res = subprocess.run(
            [
                "adb", "shell",
                "dumpsys trust | grep 'deviceLocked='; dumpsys wear_service | grep 'Last on body state detected'"
            ],
            capture_output=True, text=True, timeout=1.5
        )
        if res.returncode != 0:
            return None, None

        out = res.stdout
        unlocked = None
        if "deviceLocked=0" in out:
            unlocked = True
        elif "deviceLocked=1" in out:
            unlocked = False

        on_body = None
        if "=OnBody" in out:
            on_body = True
        elif "=OffBody" in out:
            on_body = False

        return unlocked, on_body
    except Exception:
        return None, None


def get_watch_battery(mac: str) -> int | None:
    """Read watch battery percentage from BlueZ Battery1 D-Bus property."""
    dev_path = "/org/bluez/hci0/dev_" + mac.replace(":", "_")
    try:
        res = subprocess.run(
            [
                "gdbus", "call", "--system", "--dest", "org.bluez",
                "--object-path", dev_path,
                "--method", "org.freedesktop.DBus.Properties.Get",
                "org.bluez.Battery1", "Percentage"
            ],
            capture_output=True, text=True, timeout=1.0
        )
        if res.returncode == 0:
            m = re.search(r"byte\s+(0x[0-9a-fA-F]+|\d+)", res.stdout)
            if m:
                val = m.group(1)
                return int(val, 16) if val.startswith("0x") else int(val)
    except Exception:
        pass
    return None


def send_watch_event(event: str):
    """Send proximity events (LOCK, UNLOCK, ALARM, ALARM_STOP) to watch companion APK."""
    try:
        subprocess.run(
            ["adb", "shell", "am", "broadcast", "-a", f"dev.mambuco.watchproximity.ACTION_{event}"],
            capture_output=True, timeout=1.0
        )
    except Exception as e:
        logger.debug("Failed to dispatch watch event %s: %s", event, e)


class AntiTheftAlarm:
    """Manages audible alarm siren and tamper detection when laptop is away and locked."""
    def __init__(self, sound_path: str):
        self.sound_path = sound_path
        self.armed = False
        self.triggered = False
        self.ac_was_online = False
        self._siren_proc: subprocess.Popen | None = None

    def arm(self):
        self.ac_was_online = is_ac_online()
        self.armed = True
        logger.info("Anti-Theft armed. AC Online: %s", self.ac_was_online)

    def check_tamper(self) -> bool:
        if not self.armed:
            return False
        current_ac = is_ac_online()
        if self.ac_was_online and not current_ac:
            logger.warning("TAMPER DETECTED: AC charger was unplugged while away!")
            return True
        return False

    def trigger(self):
        if self.triggered:
            return
        self.triggered = True
        logger.warning("TRIGGERING AUDIBLE ANTI-THEFT SIREN!")
        send_watch_event("ALARM")

        try:
            subprocess.run(["wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "0"], capture_output=True, timeout=1)
            subprocess.run(["wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", "1.0"], capture_output=True, timeout=1)
        except Exception:
            pass

        def siren_loop():
            while self.triggered:
                try:
                    self._siren_proc = subprocess.Popen(
                        ["pw-play", "--volume", "1.0", self.sound_path],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
                    )
                    self._siren_proc.wait()
                except Exception:
                    time.sleep(1)

        threading.Thread(target=siren_loop, daemon=True).start()

    def disarm(self):
        if self.triggered:
            logger.info("Anti-Theft alarm disarmed. Silencing siren.")
            send_watch_event("ALARM_STOP")
        self.triggered = False
        self.armed = False
        if self._siren_proc:
            try:
                self._siren_proc.terminate()
            except Exception:
                pass
            self._siren_proc = None


def write_status(data: dict):
    """Write current state to tmpfs JSON file atomically for GNOME extension & PAM."""
    try:
        temp_file = STATUS_FILE.with_suffix(".tmp")
        temp_file.write_text(json.dumps(data), encoding="utf-8")
        temp_file.replace(STATUS_FILE)
    except Exception as e:
        logger.debug("Failed to write status file: %s", e)


def run_calibration(mac: str, desk_th: int, warn_th: int, away_th: int, interval: float):
    """Interactive calibration mode: print continuous RSSI readings, zone, and visual bar."""
    print("\n--- watch-proximity Calibration Mode ---")
    print(f"Target Device: {mac}")
    print(f"Zones: Desk (> {desk_th} dB) | Normal (+1 to 0 dB) | Warning (0 to {warn_th} dB) | Away (<= {away_th} dB)")
    print(f"Sampling every {interval}s. Walk away to observe readings.\nPress Ctrl+C to stop.\n")

    try:
        while True:
            connected, rssi = get_bluetooth_rssi(mac)
            locked = is_screen_locked()
            status_lock = "[LOCKED]" if locked else "[UNLOCKED]"

            if not connected or rssi is None:
                print(f"{status_lock} Device: DISCONNECTED / OUT OF RANGE -> Zone: AWAY (Lock candidate)")
            else:
                bars = max(0, min(10, int((rssi + 20) / 2)))
                meter = "█" * bars + "░" * (10 - bars)
                if rssi > desk_th:
                    zone = "DESK (Auto-wake / Active)"
                elif rssi >= 0:
                    zone = "NORMAL (Safe)"
                elif rssi >= warn_th:
                    zone = "WARNING (Step-away alert)"
                else:
                    zone = "AWAY (Lock trigger)"
                dist = estimate_distance(rssi)
                print(f"{status_lock} RSSI: {rssi:+3d} dB  [{meter}]  Dist: {dist:<16} Zone: {zone}")

            time.sleep(interval)
    except KeyboardInterrupt:
        print("\nCalibration ended.")


def run_daemon(config: dict):
    mac = config["DEVICE_MAC"]
    device_name = config["DEVICE_NAME"]
    desk_th = config["DESK_THRESHOLD"]
    warn_th = config["WARNING_THRESHOLD"]
    away_th = config["AWAY_THRESHOLD"]
    tolerance = config["TOLERANCE_COUNT"]
    poll_interval = config["POLL_INTERVAL"]
    locked_interval = config["LOCKED_INTERVAL"]

    logger.info("Starting watch-proximity daemon for %s (%s)", device_name, mac)
    logger.info("Zones: Desk > %d dB | Normal +1 to 0 dB | Warning 0 to %d dB | Away <= %d dB (Strikes: %d)",
                desk_th, warn_th, away_th, tolerance)

    strikes = 0
    armed = False
    notified_standby = False
    warned_step_away = False
    paused_players: list[str] = []
    anti_theft = AntiTheftAlarm(config["ALARM_SOUND_PATH"])
    prev_locked = is_screen_locked()

    while True:
        config = read_runtime_controls(config)
        snooze_left = check_snooze()

        locked = is_screen_locked()
        if locked and not prev_locked:
            send_watch_event("LOCK")
        elif not locked and prev_locked:
            send_watch_event("UNLOCK")
        prev_locked = locked

        ac_online = is_ac_online()

        # Handle Snooze mode
        if snooze_left > 0:
            write_status({
                "state": "SNOOZED",
                "rssi": None,
                "distance_est": "Snoozed",
                "device_name": device_name,
                "device_mac": mac,
                "connected": False,
                "strikes": 0,
                "max_strikes": tolerance,
                "is_locked": locked,
                "ac_online": ac_online,
                "anti_theft_armed": False,
                "alarm_active": False,
                "auto_wake": config["AUTO_WAKE"],
                "snooze_remaining": snooze_left,
                "watch_unlocked": None,
                "watch_on_body": None,
                "updated_at": time.time(),
            })
            time.sleep(2.0)
            continue

        # Sample Bluetooth proximity
        connected, rssi = get_bluetooth_rssi(mac)
        dist_str = estimate_distance(rssi)

        # -----------------------------------------------------------------
        # SCREEN IS LOCKED STATE (Auto-Wake & Anti-Theft Alarm)
        # -----------------------------------------------------------------
        if locked:
            if config["ANTI_THEFT"] and anti_theft.armed:
                if anti_theft.check_tamper():
                    anti_theft.trigger()

            if connected and rssi is not None and rssi > desk_th:
                logger.info("%s returned to Desk zone (%d dB). Triggering Auto-Wake!", device_name, rssi)
                anti_theft.disarm()

                if config["AUTO_WAKE"]:
                    wake_display()

                if config["RESUME_MEDIA"] and paused_players:
                    logger.info("Resuming media playback for %s...", paused_players)
                    resume_mpris_players(paused_players)
                    paused_players = []

                strikes = 0
                armed = True
                warned_step_away = False

            watch_unlocked, watch_on_body = query_watch_security_state()

            write_status({
                "state": "ALARM" if anti_theft.triggered else ("WAKING" if (connected and rssi is not None and rssi > desk_th) else "LOCKED"),
                "rssi": rssi,
                "distance_est": dist_str,
                "device_name": device_name,
                "device_mac": mac,
                "connected": connected,
                "strikes": strikes,
                "max_strikes": tolerance,
                "is_locked": True,
                "ac_online": ac_online,
                "anti_theft_armed": anti_theft.armed,
                "alarm_active": anti_theft.triggered,
                "auto_wake": config["AUTO_WAKE"],
                "snooze_remaining": 0,
                "watch_unlocked": watch_unlocked,
                "watch_on_body": watch_on_body,
                "updated_at": time.time(),
            })

            time.sleep(locked_interval)
            continue

        # -----------------------------------------------------------------
        # SCREEN IS UNLOCKED STATE
        # -----------------------------------------------------------------
        if anti_theft.armed or anti_theft.triggered:
            anti_theft.disarm()

        if connected and rssi is not None and rssi > desk_th:
            if not armed:
                logger.info("%s detected at desk (%d dB). Proximity lock ARMED.", device_name, rssi)
                armed = True
                notified_standby = False
            strikes = 0
            warned_step_away = False
            current_state = "DESK"
        elif connected and rssi is not None and rssi >= 0:
            current_state = "NORMAL"
            strikes = 0
            warned_step_away = False
        elif connected and rssi is not None and rssi >= warn_th:
            current_state = "WARNING"
        else:
            current_state = "AWAY" if (not connected or (rssi is not None and rssi <= away_th)) else "WARNING"

        if not armed:
            if not notified_standby:
                logger.warning("%s not detected nearby. Proximity lock in STANDBY.", device_name)
                send_notification(
                    "Proximity Lock: Standby",
                    f"{device_name} is not nearby. Lock trigger suspended until watch reconnects."
                )
                notified_standby = True

            watch_unlocked, watch_on_body = query_watch_security_state()

            write_status({
                "state": "STANDBY",
                "rssi": rssi,
                "distance_est": dist_str,
                "device_name": device_name,
                "device_mac": mac,
                "connected": connected,
                "strikes": 0,
                "max_strikes": tolerance,
                "is_locked": False,
                "ac_online": ac_online,
                "anti_theft_armed": False,
                "alarm_active": False,
                "auto_wake": config["AUTO_WAKE"],
                "snooze_remaining": 0,
                "watch_unlocked": watch_unlocked,
                "watch_on_body": watch_on_body,
                "updated_at": time.time(),
            })
            time.sleep(3.0)
            continue

        if current_state == "WARNING":
            if not warned_step_away:
                logger.info("Entering warning zone (%d dB).", rssi)
                send_notification("Proximity Warning", f"Moving away from laptop ({dist_str}).")
                warned_step_away = True
            strikes = max(0, strikes - 1)

        elif current_state == "AWAY":
            strikes += 1
            logger.warning(
                "Away reading (RSSI: %s, Connected: %s). Strike %d/%d",
                f"{rssi} dB" if rssi is not None else "None", connected, strikes, tolerance
            )

        if strikes >= tolerance:
            logger.warning("Tolerance limit reached (%d/%d away strikes). Triggering screen lock!",
                           strikes, tolerance)

            if config["PAUSE_MEDIA"]:
                playing = get_playing_mpris_players()
                if playing:
                    logger.info("Pausing active media players: %s", playing)
                    pause_mpris_players(playing)
                    paused_players = playing

            lock_screen()

            if config["ANTI_THEFT"]:
                anti_theft.arm()

            strikes = 0
            armed = False
            warned_step_away = False
            time.sleep(locked_interval)
            continue

        watch_unlocked, watch_on_body = query_watch_security_state()

        write_status({
            "state": current_state,
            "rssi": rssi,
            "distance_est": dist_str,
            "device_name": device_name,
            "device_mac": mac,
            "connected": connected,
            "strikes": strikes,
            "max_strikes": tolerance,
            "is_locked": False,
            "ac_online": ac_online,
            "anti_theft_armed": False,
            "alarm_active": False,
            "auto_wake": config["AUTO_WAKE"],
            "snooze_remaining": 0,
            "watch_unlocked": watch_unlocked,
            "watch_on_body": watch_on_body,
            "updated_at": time.time(),
        })

        time.sleep(poll_interval)


def main():
    parser = argparse.ArgumentParser(description="watch-proximity Daemon & Security Guard")
    parser.add_argument(
        "--config", type=Path, default=DEFAULT_CONFIG_PATH,
        help=f"Path to configuration file (default: {DEFAULT_CONFIG_PATH})"
    )
    parser.add_argument("--mac", type=str, help="Device Bluetooth MAC address")
    parser.add_argument("--desk", type=int, help="Desk zone threshold in dB (default: 1)")
    parser.add_argument("--warning", type=int, help="Warning zone threshold in dB (default: -2)")
    parser.add_argument("--away", type=int, help="Away zone threshold in dB (default: -3)")
    parser.add_argument("--tolerance", type=int, help="Consecutive strikes required to lock (default: 5)")
    parser.add_argument("--interval", type=float, help="Polling interval in seconds (default: 2.0)")
    parser.add_argument("--calibrate", "-c", action="store_true", help="Run interactive calibration mode")
    parser.add_argument("--verbose", "-v", action="store_true", help="Enable verbose debug logging")

    args = parser.parse_args()

    log_level = logging.DEBUG if args.verbose else logging.INFO
    logging.basicConfig(
        level=log_level,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S"
    )

    config = parse_config(args.config)

    if args.mac:
        config["DEVICE_MAC"] = args.mac
    if args.desk is not None:
        config["DESK_THRESHOLD"] = args.desk
    if args.warning is not None:
        config["WARNING_THRESHOLD"] = args.warning
    if args.away is not None:
        config["AWAY_THRESHOLD"] = args.away
    if args.tolerance is not None:
        config["TOLERANCE_COUNT"] = args.tolerance
    if args.interval is not None:
        config["POLL_INTERVAL"] = args.interval

    def handle_exit(signum, frame):
        logger.info("Received termination signal %s. Exiting cleanly.", signum)
        STATUS_FILE.unlink(missing_ok=True)
        sys.exit(0)

    signal.signal(signal.SIGINT, handle_exit)
    signal.signal(signal.SIGTERM, handle_exit)

    if args.calibrate:
        run_calibration(
            config["DEVICE_MAC"],
            config["DESK_THRESHOLD"],
            config["WARNING_THRESHOLD"],
            config["AWAY_THRESHOLD"],
            config["POLL_INTERVAL"]
        )
    else:
        run_daemon(config)


if __name__ == "__main__":
    main()
