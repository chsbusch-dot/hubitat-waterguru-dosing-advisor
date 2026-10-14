#!/usr/bin/env python3
"""
Nightly passive capture of the hub's Z-Wave transmit log around the 19:45 dose (WOR-714).

The Oct 3 dose was lost because one Z-Wave frame to the pump plug (then device 4674, Long Range
node 0x0103) never took effect, and the hub keeps only about 2.5 hours of its Z-Wave log. This
script records that log while the dose happens, so a recurrence can be read at frame level. Since
2026-10-12 the plug is device 4680, classic mesh node 72 (0x48), without security.

What it does
  * Subscribes, read-only, to two hub websockets until --until (default 20:05, so the OFF of a
    13m 35s dose started at 19:45 is inside the window):
      ws://<hub>/zwaveLogsocket   the hub's Z-Wave log, in one of two formats (below)
      ws://<hub>/logsocket        the live hub log; only used to timestamp the app's ON request
                                  and the plug's OFF report, so those frames can be found
    It never sends a command, a refresh or an HTTP request to the hub. The only bytes it writes
    are the websocket handshake and, if the hub pings, the protocol's pong.
  * Writes the raw stream to <out-dir>/raw/ and appends one row per run to <out-dir>/summary.csv.
    A row is written on every path that captured nothing too (window missed because the Mac was
    asleep, socket failure, no traffic), so silence never reads as "fine".
  * After --expire (inclusive last day) it exits at once without capturing.

Two zwaveLogsocket formats, detected per record (a log may hold both):
  json       up to 2026-10-09: one record per transmission with "imeReport" (key "3" = ACK RSSI,
             key "10" = noise on Long Range frames) and "id" = node in hex ("48", "0103")
  plaintext  from 2026-10-10 (hub 2.5.2.135): the SERIAL / DRIVER / CNTRLR dump, one record per
             log line with "plainTextMessage" and deviceId -999. Nodes appear in decimal
             ("[Node 072]"); --node stays hex and is matched by value.

Definitions, json (match the 2026-10-06 baseline: 477 frames, 37 without ACK RSSI):
  frame          a transmit record to the device that carries the ACK report (imeReport key "3")
  no-ACK frame   ACK RSSI 127 (not reported): lost, or no ACK requested; unresolved either way
  zero-IME frame ACK report all zeros; counted as a frame, kept out of the RSSI figures
  ON frame       first device frame from 1 s before the app's "start requested" log line (the app
                 logs it a few ms after calling on()); on_frame_time carries the delay after it
  OFF bracket    every device frame in the 1.0 s before the plug's first "switch is turned off"
                 report, each with its ACK. The OFF frame itself cannot be singled out: the app
                 logs nothing just before off() and command events reach no socket. Match
                 noack_times against the hub's command-on/command-off event times when needed.
  noise floor    imeReport key "10"[0] on Long Range frames (inferred meaning)

Definitions, plaintext (the 2026-10-14 wrap-up's reading, WOR-771):
  frame          a "» [Node NNN] [REQ] [SendDataBridge]" transmit line to the device, paired with
                 the "« [REQ] [SendDataBridge]" callback line that carries its callback id (transmit
                 status, routing attempts, ACK RSSI). Callback ids wrap at 255, so a callback pairs
                 only within 65 s of its transmit, and a transmit whose id is reused stays counted.
                 The hub drops log lines under load (about 4% of callback lines on Oct 12 and 13;
                 transmit lines too, see callbacks_without_transmit). When the decoded callback line
                 is missing, the raw "« 0x011d00a9..." SERIAL callback frame with the same id gives
                 the status and ACK RSSI instead (callbacks_ok_raw).
  no-ACK frame   a callback with a transmit status other than OK, or no callback in either form and
                 no answer from the node within 0.35 s. A transmit without any callback that the
                 node answered within 0.35 s is a logging artefact and counts as delivered
                 (missing_answered), without an RSSI.
  zero-IME frame an OK callback without an ACK RSSI line
  ON frame       the first BinarySwitchCCSet with target value true from 1 s before the app's
                 "start requested" log line; the frame itself, not the first device frame
  OFF frames     every BinarySwitchCCSet with target value false in the run, each with its outcome
  noise floor    "[RES] [GetBackgroundRSSI]" channel 0 readings in the run. This is a different
                 measurement from the json format's imeReport key "10": -100 before Oct 10 and
                 -109 after are not the same quantity.
  incoming RSSI  the RSSI on the node's own "« [Node NNN] [REQ] [BridgeApplicationCommand]" lines

Python 3.9+ standard library only (runs under /usr/bin/python3 from launchd).

  zwave_capture.py                         nightly run, 19:40 start from launchd
  zwave_capture.py --duration-min 30 --mode test --stop-on-first-frame
  zwave_capture.py --analyze RAW_LOG       recompute a summary row from a raw log, print only
  zwave_capture.py --analyze RAW_LOG --device 4674 --node 0103    a night before the Oct 12 re-join
"""

import argparse
import base64
import bisect
import csv
import datetime as dt
import json
import os
import re
import socket
import statistics
import struct
import sys
import threading
import time

SUMMARY_FIELDS = [
    "date", "mode", "status", "started", "ended", "frames_device", "noack_device",
    "noack_share_pct", "zero_ime_device", "ack_rssi_median", "ack_rssi_worst", "noise_median",
    "run_first", "run_last", "on_frame_time", "on_acked", "off_report_time", "off_acked",
    "noack_times", "other_node_frames", "log_lines", "reconnects", "slept_seconds", "raw_file", "note",
]


# --------------------------------------------------------------------------- websocket client

def ws_connect(host, port, resource, timeout):
    sock = socket.create_connection((host, port), timeout=timeout)
    key = base64.b64encode(os.urandom(16)).decode()
    request = (
        "GET {} HTTP/1.1\r\nHost: {}:{}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
        "Sec-WebSocket-Key: {}\r\nSec-WebSocket-Version: 13\r\n\r\n"
    ).format(resource, host, port, key)
    sock.sendall(request.encode())
    head = bytearray()
    while b"\r\n\r\n" not in head:
        chunk = sock.recv(4096)
        if not chunk:
            raise ConnectionError("closed during handshake")
        head += chunk
    status_line, _, _ = bytes(head).partition(b"\r\n")
    if b" 101 " not in status_line + b" ":
        raise ConnectionError("handshake refused: {!r}".format(status_line[:80]))
    rest = bytearray(bytes(head).split(b"\r\n\r\n", 1)[1])
    return sock, rest


def parse_frame(buf):
    """Return (fin, opcode, payload, consumed) when a whole frame is buffered, else None.
    Nothing is consumed until the frame is complete, so a read timeout cannot split a frame."""
    if len(buf) < 2:
        return None
    b1, b2 = buf[0], buf[1]
    length = b2 & 0x7F
    pos = 2
    if length == 126:
        if len(buf) < 4:
            return None
        length = struct.unpack(">H", bytes(buf[2:4]))[0]
        pos = 4
    elif length == 127:
        if len(buf) < 10:
            return None
        length = struct.unpack(">Q", bytes(buf[2:10]))[0]
        pos = 10
    mask = None
    if b2 & 0x80:
        if len(buf) < pos + 4:
            return None
        mask = bytes(buf[pos:pos + 4])
        pos += 4
    if len(buf) < pos + length:
        return None
    payload = bytes(buf[pos:pos + length])
    if mask:
        payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    return bool(b1 & 0x80), b1 & 0x0F, payload, pos + length


def send_pong(sock, payload):
    mask = os.urandom(4)
    body = bytes(b ^ mask[i % 4] for i, b in enumerate(payload[:125]))
    sock.sendall(bytes([0x80 | 0xA, 0x80 | len(body)]) + mask + body)


# --------------------------------------------------------------------------- capture

class Sink:
    """Collects messages from both sockets and writes the raw log."""

    def __init__(self, raw_path):
        self.lock = threading.Lock()
        self.raw = open(raw_path, "a", buffering=1)
        self.zwave = []
        self.logs = []
        self.reconnects = 0
        self.connected = {"zwave": False, "log": False}
        self.errors = []
        self.stop = threading.Event()
        self.stop_on_first_frame = False

    def meta(self, text):
        with self.lock:
            self.raw.write("# {} {}\n".format(dt.datetime.now().isoformat(), text))

    def add(self, source, text):
        now = dt.datetime.now().isoformat()
        with self.lock:
            self.raw.write("{}\t{}\t{}\n".format(now, source, text))
            try:
                record = json.loads(text)
            except ValueError:
                return
            (self.zwave if source == "zwave" else self.logs).append(record)
            if source == "zwave" and self.stop_on_first_frame:
                self.stop.set()

    def close(self):
        with self.lock:
            self.raw.close()


def run_socket(sink, source, host, port, resource, deadline):
    while time.time() < deadline and not sink.stop.is_set():
        sock = None
        try:
            sock, buf = ws_connect(host, port, resource, timeout=10)
            sock.settimeout(5)
            sink.connected[source] = True
            sink.meta("connected {}".format(source))
            parts = []
            while time.time() < deadline and not sink.stop.is_set():
                frame = parse_frame(buf)
                if frame is None:
                    try:
                        chunk = sock.recv(65536)
                    except socket.timeout:
                        continue
                    if not chunk:
                        raise ConnectionError("server closed the socket")
                    buf += chunk
                    continue
                fin, opcode, payload, consumed = frame
                del buf[:consumed]
                if opcode in (0x0, 0x1):
                    parts.append(payload)
                    if fin:
                        sink.add(source, b"".join(parts).decode("utf-8", "replace"))
                        parts = []
                elif opcode == 0x9:
                    send_pong(sock, payload)
                elif opcode == 0x8:
                    raise ConnectionError("server sent close")
        except Exception as exc:  # noqa: BLE001 - every failure is recorded, then retried
            sink.reconnects += 1
            sink.errors.append("{}: {!r}".format(source, exc))
            sink.meta("{} error, reconnecting: {!r}".format(source, exc))
            time.sleep(max(0.0, min(5.0, deadline - time.time())))
        finally:
            if sock is not None:
                try:
                    sock.close()
                except OSError:
                    pass


def capture(host, deadline, raw_path, stop_on_first_frame=False):
    sink = Sink(raw_path)
    sink.stop_on_first_frame = stop_on_first_frame
    sink.meta("capture start, until {}".format(dt.datetime.fromtimestamp(deadline).isoformat()))
    threads = [
        threading.Thread(target=run_socket, args=(sink, "zwave", host, 80, "/zwaveLogsocket", deadline), daemon=True),
        threading.Thread(target=run_socket, args=(sink, "log", host, 80, "/logsocket", deadline), daemon=True),
    ]
    for t in threads:
        t.start()
    # Sleep detection: wall time advances while the Mac sleeps, the monotonic clock does not.
    slept = 0.0
    last_wall, last_mono = time.time(), time.monotonic()
    while time.time() < deadline and not sink.stop.is_set():
        sink.stop.wait(5)
        wall, mono = time.time(), time.monotonic()
        gap = (wall - last_wall) - (mono - last_mono)
        if gap > 20:
            slept += gap
            sink.meta("clock gap of {:.0f}s: the Mac was probably asleep".format(gap))
        last_wall, last_mono = wall, mono
    for t in threads:
        t.join(timeout=15)
    sink.meta("capture end")
    sink.close()
    return sink, slept


# --------------------------------------------------------------------------- analysis

def parse_time(text):
    for fmt in ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S"):
        try:
            return dt.datetime.strptime(text, fmt)
        except (TypeError, ValueError):
            continue
    return None


CALLBACK_TIMEOUT_S = 65.0   # the Serial API's SendData callback timeout: a later callback is another transmit's
ANSWER_WINDOW_S = 0.35      # a transmit without a callback line counts as delivered if the node answered this fast
LIST_CAP = 12               # noack_times / off_acked list at most this many frames

TX_RE = re.compile(r"» \[Node (\d+)\] \[REQ\] \[SendData(?:Bridge)?\]")
CALLBACK_RE = re.compile(r"« \[REQ\] \[SendData(?:Bridge)?\]")
INCOMING_RE = re.compile(r"« \[Node (\d+)\] \[REQ\] \[(?:Bridge)?ApplicationCommand\]")
BACKGROUND_RE = re.compile(r"« \[RES\] \[GetBackgroundRSSI\]")
CALLBACK_ID_RE = re.compile(r"callback id:\s+(\d+)")
STATUS_RE = re.compile(r"transmit status:\s+(\w+)")
ACK_RSSI_RE = re.compile(r"ACK RSSI:\s+(-?\d+) dBm")
ATTEMPTS_RE = re.compile(r"routing attempts:\s+(\d+)")
RSSI_RE = re.compile(r"\bRSSI:\s+(-?\d+) dBm")
CHANNEL0_RE = re.compile(r"channel 0:\s+(-?\d+) dBm")
COMMAND_RE = re.compile(r"└─\[(\w+)\]")
TARGET_RE = re.compile(r"target value:\s+(true|false)")
# The raw callback frame on its SERIAL line: SOF 01, length, REQ 00, SendDataBridge a9, callback id,
# transmit status, 2 bytes of ticks, repeater count, ACK RSSI (signed; 0x7f = not reported), ...
RAW_CALLBACK_RE = re.compile(r"« 0x(01[0-9a-f]{2}00a9[0-9a-f]+)")
TX_STATUS = {0: "OK", 1: "NoAck", 2: "Fail", 3: "NotIdle", 4: "NoRoute"}


def record_format(record):
    """'plaintext' for the SERIAL / DRIVER / CNTRLR dump (hub 2.5.2.135 on), 'json' for a per-frame
    record with an imeReport (before it), None for anything else."""
    if "plainTextMessage" in record:
        return "plaintext"
    if "imeReport" in record:
        return "json"
    return None


def node_number(text):
    """A node id as the json format prints it, hex ("48", "0103"), as a number; None if not hex."""
    try:
        return int(str(text), 16)
    except ValueError:
        return None


def ack_rssi(record):
    report = record.get("imeReport") or {}
    values = report.get("3")
    return values[0] if values else None


def is_device(record, device_id, node_hex):
    node = node_number(record.get("id", ""))
    return str(record.get("deviceId")) == str(device_id) or (node is not None and node == node_number(node_hex))


def json_frames(records, device_id, node_hex):
    """Device frames from per-frame json records, and the count of records for other nodes."""
    frames, other = [], 0
    for r in records:
        if not is_device(r, device_id, node_hex):
            other += 1
            continue
        report = r.get("imeReport") or {}
        t = parse_time(r.get("time"))
        if "3" not in report or t is None:
            continue
        ack = ack_rssi(r)
        frame = {"fmt": "json", "time": r["time"], "_t": t, "ack": ack, "noise": None, "set": None}
        if ack == 127:
            frame["outcome"] = "noack"
        elif ack == 0:
            frame["outcome"] = "zero"
        elif isinstance(ack, int) and -128 < ack < 0:
            frame["outcome"] = "ok"
            noise = report.get("10")
            if noise and isinstance(noise[0], int) and noise[0] < 0:
                frame["noise"] = noise[0]
        else:
            frame["outcome"] = "other"
        frames.append(frame)
    return frames, other


def plaintext_frames(records, node_hex):
    """Rebuild per-transmit outcomes from the plaintext dump, in arrival order: each transmit line
    to a node is paired with the callback line carrying its callback id. Returns the device's
    frames, the count of transmits to other nodes, the device's incoming (time, RSSI) pairs, the
    GetBackgroundRSSI channel 0 (time, dBm) readings, and the times of callback lines whose
    transmit line the hub never logged (any node)."""
    target = node_number(node_hex)
    frames, incoming, noise, unpaired = [], [], [], []
    other = 0
    pending = {}
    for r in records:
        msg = r.get("plainTextMessage") or ""
        t = parse_time(r.get("time"))
        if t is None:
            continue
        m = TX_RE.match(msg)
        if m:
            commands = COMMAND_RE.findall(msg)
            frame = {"fmt": "plaintext", "time": r["time"], "_t": t, "command": commands[-1] if commands else "",
                     "set": None, "status": None, "ack": None, "attempts": None, "noise": None, "raw": None,
                     "raw_only": False}
            value = TARGET_RE.search(msg)
            if frame["command"] == "BinarySwitchCCSet" and value:
                frame["set"] = value.group(1) == "true"
            cb = CALLBACK_ID_RE.search(msg)
            if cb:
                # Replaces an unanswered transmit with the same id; that one stays counted, unpaired.
                pending[int(cb.group(1))] = frame
            if int(m.group(1)) == target:
                frames.append(frame)
            else:
                other += 1
            continue
        if CALLBACK_RE.match(msg):
            cb = CALLBACK_ID_RE.search(msg)
            frame = pending.pop(int(cb.group(1)), None) if cb else None
            if frame is None or not -1.0 <= (t - frame["_t"]).total_seconds() <= CALLBACK_TIMEOUT_S:
                unpaired.append(t)
                continue
            status = STATUS_RE.search(msg)
            ack = ACK_RSSI_RE.search(msg)
            attempts = ATTEMPTS_RE.search(msg)
            frame["status"] = status.group(1) if status else "?"
            frame["ack"] = int(ack.group(1)) if ack else None
            frame["attempts"] = int(attempts.group(1)) if attempts else None
            continue
        m = RAW_CALLBACK_RE.match(msg)
        if m:
            # Kept aside: the decoded callback line usually follows and wins; this is the fallback
            # for when the hub dropped that line.
            raw = bytes.fromhex(m.group(1)) if len(m.group(1)) % 2 == 0 else b""
            frame = pending.get(raw[4]) if len(raw) > 5 else None
            if frame is not None and frame["raw"] is None and -1.0 <= (t - frame["_t"]).total_seconds() <= CALLBACK_TIMEOUT_S:
                ack = raw[9] - 256 if len(raw) > 9 and raw[9] >= 128 else None
                frame["raw"] = (TX_STATUS.get(raw[5], "status 0x{:02x}".format(raw[5])), ack)
            continue
        m = INCOMING_RE.match(msg)
        if m:
            if int(m.group(1)) == target:
                rssi = RSSI_RE.search(msg)
                incoming.append((t, int(rssi.group(1)) if rssi else None))
            continue
        if BACKGROUND_RE.match(msg):
            reading = CHANNEL0_RE.search(msg)
            if reading:
                noise.append((t, int(reading.group(1))))

    answers = sorted(t for t, _ in incoming)
    for f in frames:
        if f["status"] is None and f["raw"] is not None:
            (f["status"], f["ack"]), f["raw_only"] = f["raw"], True
        if f["status"] is None:
            i = bisect.bisect_left(answers, f["_t"])
            delay = (answers[i] - f["_t"]).total_seconds() if i < len(answers) else None
            if delay is not None and delay <= ANSWER_WINDOW_S:
                f["outcome"], f["answer_s"] = "answered", delay
            else:
                f["outcome"] = "noack"
        elif f["status"] != "OK":
            f["outcome"] = "noack"
        else:
            f["outcome"] = "ok" if f["ack"] is not None else "zero"
    return frames, other, incoming, noise, unpaired


def verdict(frame):
    """One frame's outcome as the summary prints it."""
    if frame is None:
        return ""
    outcome = frame["outcome"]
    if frame["fmt"] == "json":
        if outcome == "noack":
            return "no (RSSI 127)"
        if outcome == "zero":
            return "unknown (zero IME)"
        return "yes ({} dBm)".format(frame["ack"])
    source = ", raw callback frame" if frame["raw_only"] else ""
    if outcome == "ok":
        return "yes ({} dBm{})".format(frame["ack"], source)
    if outcome == "zero":
        return "yes (no ACK RSSI{})".format(source)
    if outcome == "answered":
        return "no callback in log, answered after {:.2f} s".format(frame["answer_s"])
    if frame["status"]:
        return "no (transmit status {}{})".format(frame["status"], source)
    return "no callback in log, no answer within {} s".format(ANSWER_WINDOW_S)


def capped(items):
    return items[:LIST_CAP] + (["..."] if len(items) > LIST_CAP else [])


def summarise(zwave, logs, device_id, node_hex, app_id, fallback_from=None):
    """Compute the per-night figures from parsed socket records, whichever format each Z-Wave record
    is in. Without a start-requested log line the run is every device frame at or after
    fallback_from (HH:MM), or every device frame."""
    out = {}
    formats = {record_format(r) for r in zwave} - {None}
    jframes, jother = json_frames([r for r in zwave if record_format(r) == "json"], device_id, node_hex)
    pframes, pother, incoming, background, unpaired = plaintext_frames(
        [r for r in zwave if record_format(r) == "plaintext"], node_hex)
    frames = sorted(jframes + pframes, key=lambda f: f["_t"])

    start_req = None
    off_report = None
    for entry in logs:
        t = parse_time(entry.get("time"))
        msg = str(entry.get("msg", ""))
        if t is None:
            continue
        if entry.get("type") == "app" and str(entry.get("id")) == str(app_id) and "start requested" in msg:
            if start_req is None:
                start_req = t
        if entry.get("type") == "dev" and str(entry.get("id")) == str(device_id) and "switch is turned off" in msg:
            if start_req is not None and t >= start_req and off_report is None:
                off_report = t

    if start_req is not None:
        run_from = start_req - dt.timedelta(seconds=1)

        def in_run(t):
            return t >= run_from
    elif fallback_from:
        hh, mm = (int(x) for x in fallback_from.split(":"))

        def in_run(t):
            return (t.hour, t.minute) >= (hh, mm)
    else:
        def in_run(t):
            return True

    run = [f for f in frames if in_run(f["_t"])]
    noack = [f for f in run if f["outcome"] == "noack"]
    zero = [f for f in run if f["outcome"] == "zero"]
    valid = [f["ack"] for f in run if f["outcome"] == "ok"]
    noise = [f["noise"] for f in run if f["noise"] is not None] + [v for t, v in background if in_run(t)]

    # json: the first device frame after the request. plaintext: the ON Set itself.
    on_frames = [f for f in run if f["fmt"] == "json" or f["set"] is True]
    on_frame = on_frames[0] if (start_req is not None and on_frames) else None
    off_frames = []
    for f in run:
        if f["fmt"] == "json":
            if off_report is not None and off_report - dt.timedelta(seconds=1.0) <= f["_t"] <= off_report:
                off_frames.append(f)
        elif f["set"] is False:
            off_frames.append(f)

    out["frames_device"] = len(run)
    out["noack_device"] = len(noack)
    out["noack_share_pct"] = "{:.1f}".format(100.0 * len(noack) / len(run)) if run else ""
    out["zero_ime_device"] = len(zero)
    out["ack_rssi_median"] = statistics.median(valid) if valid else ""
    out["ack_rssi_worst"] = min(valid) if valid else ""
    out["noise_median"] = statistics.median(noise) if noise else ""
    out["run_first"] = run[0]["time"] if run else ""
    out["run_last"] = run[-1]["time"] if run else ""
    if on_frame:
        out["on_frame_time"] = "{} (+{:.2f} s)".format(on_frame["time"], (on_frame["_t"] - start_req).total_seconds())
    elif start_req is None:
        out["on_frame_time"] = "no start-requested log line"
    elif "plaintext" in formats:
        out["on_frame_time"] = "no BinarySwitchCCSet ON to the device after it"
    else:
        out["on_frame_time"] = "no device frame after it"
    out["on_acked"] = verdict(on_frame)
    out["off_report_time"] = off_report.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3] if off_report else "no switch-off report"
    if off_frames:
        out["off_acked"] = "; ".join(capped(["{} {}".format(f["time"][11:], verdict(f)) for f in off_frames]))
    elif off_report is None:
        out["off_acked"] = ""
    elif "plaintext" in formats:
        out["off_acked"] = "no BinarySwitchCCSet OFF to the device in the run"
    else:
        out["off_acked"] = "no device frame in the 1.0 s before it"
    out["noack_times"] = " ".join(capped([f["time"][11:] for f in noack]))
    out["other_node_frames"] = jother + pother
    out["log_lines"] = len(logs)

    # Not summary.csv columns (its header stays as it is); --analyze prints them, a capture run
    # puts the plaintext ones in the note.
    out["format"] = "mixed" if len(formats) > 1 else (formats.pop() if formats else "")
    out["noise_source"] = {"json": "imeReport key 10 (Long Range frames)",
                           "plaintext": "GetBackgroundRSSI channel 0",
                           "mixed": "both: imeReport key 10 and GetBackgroundRSSI channel 0"}.get(out["format"], "")
    if pframes or out["format"] in ("plaintext", "mixed"):
        prun = [f for f in run if f["fmt"] == "plaintext"]
        rssi_in = [v for t, v in incoming if in_run(t) and v is not None]
        out["callbacks_ok"] = sum(1 for f in prun if f["status"] == "OK" and not f["raw_only"])
        out["callbacks_ok_raw"] = sum(1 for f in prun if f["status"] == "OK" and f["raw_only"])
        out["callbacks_failed"] = sum(1 for f in prun if f["status"] not in (None, "OK"))
        out["callbacks_missing"] = sum(1 for f in prun if f["status"] is None)
        out["missing_answered"] = sum(1 for f in prun if f["outcome"] == "answered")
        out["multi_attempt"] = sum(1 for f in prun if (f["attempts"] or 1) > 1)
        out["incoming_rssi_median"] = statistics.median(rssi_in) if rssi_in else ""
        out["incoming_rssi_worst"] = min(rssi_in) if rssi_in else ""
        out["callbacks_without_transmit"] = sum(1 for t in unpaired if in_run(t))
    return out


def read_raw(raw_path):
    """Parse a raw log: 'ts<TAB>source<TAB>json' lines, or the older 'ts<TAB>json' zwave-only lines."""
    zwave, logs = [], []
    with open(raw_path) as fh:
        for line in fh:
            if line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 2:
                source, payload = "zwave", parts[1]
            elif len(parts) >= 3:
                source, payload = parts[1], parts[2]
            else:
                continue
            try:
                record = json.loads(payload)
            except ValueError:
                continue
            (zwave if source == "zwave" else logs).append(record)
    return zwave, logs


def append_summary(path, row):
    new = not os.path.exists(path) or os.path.getsize(path) == 0
    with open(path, "a", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=SUMMARY_FIELDS, extrasaction="ignore")
        if new:
            writer.writeheader()
        writer.writerow(row)


# --------------------------------------------------------------------------- main

def build_parser():
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--hub", default="192.168.1.147")
    p.add_argument("--device", default="4680", help="Hubitat device id of the pump plug (4674 before 2026-10-12)")
    p.add_argument("--node", default="48",
                   help="Z-Wave node id of the pump plug in hex, as the json log prints it: classic nodes two "
                        "uppercase digits (48 = node 72), Long Range four (0103 = node 259, before 2026-10-12)")
    p.add_argument("--app", default="2200", help="Hubitat app id of the dosing app")
    p.add_argument("--until", default="20:05",
                   help="local HH:MM to stop capturing (20:05: a 13m 35s dose started at 19:45 turns off at about 19:59)")
    p.add_argument("--duration-min", type=float, help="capture for N minutes instead of --until")
    p.add_argument("--expire", default="2026-10-13", help="last day to capture (inclusive)")
    p.add_argument("--out-dir", default=os.path.expanduser("~/Library/Logs/wgda-zwave"))
    p.add_argument("--mode", default="nightly")
    p.add_argument("--stop-on-first-frame", action="store_true",
                   help="test runs: end as soon as one Z-Wave frame from any node arrives")
    p.add_argument("--analyze", metavar="RAW_LOG", help="print a summary row for a raw log and exit")
    p.add_argument("--run-from", default=None,
                   help="HH:MM; without a start-requested log line, count device frames from here (nightly: 19:44)")
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)

    if args.analyze:
        zwave, logs = read_raw(args.analyze)
        row = summarise(zwave, logs, args.device, args.node, args.app, args.run_from)
        print(json.dumps(row, indent=2, default=str))
        return 0

    now = dt.datetime.now()
    if now.date() > dt.date.fromisoformat(args.expire):
        print("{} expired after {}; not capturing".format(now.isoformat(timespec="seconds"), args.expire))
        return 0

    os.makedirs(os.path.join(args.out_dir, "raw"), exist_ok=True)
    summary_path = os.path.join(args.out_dir, "summary.csv")
    stamp = now.strftime("%Y%m%d") + ("" if args.mode == "nightly" else "-{}-{}".format(args.mode, now.strftime("%H%M%S")))
    raw_path = os.path.join(args.out_dir, "raw", "zwave-{}.log".format(stamp))
    row = {"date": now.strftime("%Y-%m-%d"), "mode": args.mode, "started": now.isoformat(timespec="seconds"),
           "raw_file": raw_path, "reconnects": 0, "slept_seconds": 0}

    if args.duration_min:
        deadline = time.time() + args.duration_min * 60
    else:
        hh, mm = (int(x) for x in args.until.split(":"))
        deadline = now.replace(hour=hh, minute=mm, second=0, microsecond=0).timestamp()

    try:
        if time.time() >= deadline:
            row.update(status="missed_window", ended=row["started"], raw_file="",
                       note="started after {} (Mac asleep or off at the scheduled start?)".format(args.until))
        else:
            sink, slept = capture(args.hub, deadline, raw_path, args.stop_on_first_frame)
            fallback = args.run_from or ("19:44" if args.mode == "nightly" and not args.duration_min else None)
            row.update(summarise(sink.zwave, sink.logs, args.device, args.node, args.app, fallback))
            row["ended"] = dt.datetime.now().isoformat(timespec="seconds")
            row["reconnects"] = sink.reconnects
            row["slept_seconds"] = int(slept)
            notes = []
            if not sink.connected["zwave"]:
                status = "socket_failed"
                notes.append("never connected to /zwaveLogsocket")
            elif not sink.zwave:
                status = "no_frames"
                notes.append("connected but no Z-Wave traffic at all")
            elif row["frames_device"] == 0:
                status = "no_pump_traffic"
                notes.append("socket live (other nodes seen) but no frames to the pump: no dose tonight?")
            else:
                status = "ok"
            if slept:
                status += "+slept"
            if not sink.connected["log"]:
                notes.append("log socket never connected, so ON/OFF frames are unanchored")
            if row.get("format") in ("plaintext", "mixed"):
                notes.append("plaintext log: callbacks ok {} (+{} from the raw frame), failed {}, missing {} ({} answered "
                             "within {} s); incoming RSSI median {}; noise is GetBackgroundRSSI channel 0".format(
                                 row["callbacks_ok"], row["callbacks_ok_raw"], row["callbacks_failed"], row["callbacks_missing"],
                                 row["missing_answered"], ANSWER_WINDOW_S, row["incoming_rssi_median"]))
            if sink.errors:
                notes.append("errors: " + "; ".join(sink.errors[:3]))
            row["status"] = status
            row["note"] = " | ".join(notes)
    except Exception as exc:  # noqa: BLE001 - always leave a row behind
        row.update(status="error", ended=dt.datetime.now().isoformat(timespec="seconds"), note=repr(exc))

    append_summary(summary_path, row)
    print(json.dumps({k: row.get(k, "") for k in SUMMARY_FIELDS}, default=str))
    return 0


if __name__ == "__main__":
    sys.exit(main())
