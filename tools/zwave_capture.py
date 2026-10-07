#!/usr/bin/env python3
"""
Nightly passive capture of the hub's Z-Wave transmit log around the 19:45 dose (WOR-714).

The Oct 3 dose was lost because one Z-Wave frame to the pump plug (device 4674, Long Range node
0x0103) never took effect, and the hub keeps only about 2.5 hours of its per-frame transmit log.
This script records that log while the dose happens, so a recurrence can be read at frame level.

What it does
  * Subscribes, read-only, to two hub websockets until --until (default 19:55):
      ws://<hub>/zwaveLogsocket   one JSON record per transmission, with an ACK RSSI report
      ws://<hub>/logsocket        the live hub log; only used to timestamp the app's ON request
                                  and the plug's OFF report, so those two frames can be found
    It never sends a command, a refresh or an HTTP request to the hub. The only bytes it writes
    are the websocket handshake and, if the hub pings, the protocol's pong.
  * Writes the raw stream to <out-dir>/raw/ and appends one row per run to <out-dir>/summary.csv.
    A row is written on every path that captured nothing too (window missed because the Mac was
    asleep, socket failure, no traffic), so silence never reads as "fine".
  * After --expire (inclusive last day) it exits at once without capturing.

Definitions (match the 2026-10-06 baseline: 477 frames, 37 without ACK RSSI):
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

Python 3.9+ standard library only (runs under /usr/bin/python3 from launchd).

  zwave_capture.py                         nightly run, 19:40 start from launchd
  zwave_capture.py --duration-min 30 --mode test --stop-on-first-frame
  zwave_capture.py --analyze RAW_LOG       recompute a summary row from a raw log, print only
"""

import argparse
import base64
import csv
import datetime as dt
import json
import os
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


def ack_rssi(record):
    report = record.get("imeReport") or {}
    values = report.get("3")
    return values[0] if values else None


def is_device(record, device_id, node_hex):
    return str(record.get("deviceId")) == str(device_id) or str(record.get("id", "")).lower() == node_hex.lower()


def summarise(zwave, logs, device_id, node_hex, app_id, fallback_from=None):
    """Compute the per-night figures from parsed socket records. Without a start-requested log line
    the run is every device frame at or after fallback_from (HH:MM), or every device frame."""
    out = {}
    frames = [r for r in zwave if is_device(r, device_id, node_hex) and "3" in (r.get("imeReport") or {})]
    for r in frames:
        r["_t"] = parse_time(r.get("time"))
    frames = [r for r in frames if r["_t"] is not None]
    frames.sort(key=lambda r: r["_t"])
    other = [r for r in zwave if not is_device(r, device_id, node_hex)]

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

    run = frames
    if start_req is not None:
        run = [r for r in frames if r["_t"] >= start_req - dt.timedelta(seconds=1)]
    elif fallback_from:
        hh, mm = (int(x) for x in fallback_from.split(":"))
        run = [r for r in frames if (r["_t"].hour, r["_t"].minute) >= (hh, mm)]

    noack = [r for r in run if ack_rssi(r) == 127]
    zero = [r for r in run if ack_rssi(r) == 0]
    valid = [ack_rssi(r) for r in run if isinstance(ack_rssi(r), int) and -128 < ack_rssi(r) < 0]
    noise = []
    for r in run:
        n = (r.get("imeReport") or {}).get("10")
        if isinstance(ack_rssi(r), int) and -128 < ack_rssi(r) < 0 and n and isinstance(n[0], int) and n[0] < 0:
            noise.append(n[0])

    def acked(r):
        if r is None:
            return ""
        v = ack_rssi(r)
        if v == 127:
            return "no (RSSI 127)"
        if v == 0:
            return "unknown (zero IME)"
        return "yes ({} dBm)".format(v)

    on_frame = run[0] if (start_req is not None and run) else None
    bracket = []
    if off_report is not None:
        bracket = [r for r in run if off_report - dt.timedelta(seconds=1.0) <= r["_t"] <= off_report]

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
    else:
        out["on_frame_time"] = "no start-requested log line" if start_req is None else "no device frame after it"
    out["on_acked"] = acked(on_frame)
    out["off_report_time"] = off_report.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3] if off_report else "no switch-off report"
    if bracket:
        out["off_acked"] = "; ".join("{} {}".format(r["time"][11:], acked(r)) for r in bracket)
    else:
        out["off_acked"] = "" if off_report is None else "no device frame in the 1.0 s before it"
    out["noack_times"] = " ".join(r["time"][11:] for r in noack[:12]) + (" ..." if len(noack) > 12 else "")
    out["other_node_frames"] = len(other)
    out["log_lines"] = len(logs)
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

def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--hub", default="192.168.1.147")
    p.add_argument("--device", default="4674", help="Hubitat device id of the pump plug")
    p.add_argument("--node", default="0103", help="Z-Wave node id of the pump plug, hex")
    p.add_argument("--app", default="2200", help="Hubitat app id of the dosing app")
    p.add_argument("--until", default="19:55", help="local HH:MM to stop capturing")
    p.add_argument("--duration-min", type=float, help="capture for N minutes instead of --until")
    p.add_argument("--expire", default="2026-10-13", help="last day to capture (inclusive)")
    p.add_argument("--out-dir", default=os.path.expanduser("~/Library/Logs/wgda-zwave"))
    p.add_argument("--mode", default="nightly")
    p.add_argument("--stop-on-first-frame", action="store_true",
                   help="test runs: end as soon as one Z-Wave frame from any node arrives")
    p.add_argument("--analyze", metavar="RAW_LOG", help="print a summary row for a raw log and exit")
    p.add_argument("--run-from", default=None,
                   help="HH:MM; without a start-requested log line, count device frames from here (nightly: 19:44)")
    args = p.parse_args(argv)

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
