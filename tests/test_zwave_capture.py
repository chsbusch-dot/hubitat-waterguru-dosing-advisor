"""Check tools/zwave_capture.py's analysis against small cuts of real captures (WOR-771).

On 2026-10-10 (hub 2.5.2.135) the hub's Z-Wave log socket stopped sending one JSON record per
transmission (with an imeReport) and started sending its plaintext SERIAL / DRIVER / CNTRLR dump.
The script saw no frames in the new format, so its summary rows for Oct 10 to 13 said
no_pump_traffic for nights that carried hundreds of transmits to the pump plug. These checks hold
both parsers to what the raw logs show.

    python3 tests/test_zwave_capture.py     # standalone, no dependencies
    pytest tests/test_zwave_capture.py      # or under pytest

Fixtures (tests/fixtures/zwave/), cut from the capture's raw logs and sanitized: only lines from
the Z-Wave log socket are kept, never a hub log line (so no app or device names, no messages);
the plaintext records keep only SERIAL / DRIVER / CNTRLR lines, and the json records lose their
device name. The app's "start requested" and the plug's "switch is turned off" log lines the
analysis anchors on are synthesized below.

  zwave-20261012-plaintext.log  the ON Set to node 72 (decoded callback, ACK -87); a Meter Get whose
                                decoded callback line the hub dropped (raw SERIAL callback frame,
                                ACK -88); a callback whose transmit line was dropped; a
                                BinarySwitch Get with no callback in either form that the plug
                                answered after 0.03 s; a GetBackgroundRSSI reading (-109)
  zwave-20261013-plaintext.log  the ON Set (ACK -86); a Meter Get with no callback in either form,
                                answered after 0.19 s; a GetBackgroundRSSI reading (-108)
  zwave-20261008-json.log       the 22 s dose of 2026-10-08 on Long Range node 0103 (device 4674),
                                the old format: 52 frames, ON ACKed at -95, OFF bracket at 19:45:47
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "tests" / "fixtures" / "zwave"

_spec = importlib.util.spec_from_file_location("zwave_capture", ROOT / "tools" / "zwave_capture.py")
zc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(zc)


def load(name):
    return zc.read_raw(str(FIXTURES / name))


def start_requested(time, app=2200):
    return {"type": "app", "id": app, "msg": "start requested (synthetic anchor)", "time": time}


def switch_off(time, device):
    return {"type": "dev", "id": device, "msg": "switch is turned off (synthetic anchor)", "time": time}


def plain(time, text, label="DRIVER"):
    return {"sourceLabel": label, "plainTextMessage": text, "deviceId": -999, "time": time}


def transmit(time, callback_id, node=72, command="MeterCCGet", target=None):
    text = ("» [Node {:03d}] [REQ] [SendDataBridge]\n │ source node id:   1\n │ transmit options: 0x25\n"
            " │ callback id:      {}\n └─[{}]").format(node, callback_id, command)
    if target is not None:
        text += "\n     target value: {}".format("true" if target else "false")
    return plain(time, text)


def callback(time, callback_id, status="OK", ack=-88):
    text = "« [REQ] [SendDataBridge]\n   callback id:            {}\n   transmit status:        {}, took 10 ms\n" \
           "   routing attempts:       1".format(callback_id, status)
    if ack is not None:
        text += "\n   ACK RSSI:               {} dBm".format(ack)
    return plain(time, text)


def frame_at(frames, time):
    return next(f for f in frames if f["time"] == time)


# --------------------------------------------------------------------------- defaults and detection


def test_defaults_point_at_the_mesh_plug_and_a_window_past_the_off():
    args = zc.build_parser().parse_args([])
    assert (args.device, args.node) == ("4680", "48"), "the plug is device 4680, mesh node 72 = 0x48, since 2026-10-12"
    assert args.until == "20:05", "a 13m 35s dose started at 19:45 turns off at about 19:59"


def test_each_record_is_detected_by_its_own_shape():
    zwave_12, _ = load("zwave-20261012-plaintext.log")
    zwave_08, _ = load("zwave-20261008-json.log")
    assert {zc.record_format(r) for r in zwave_12} == {"plaintext"}
    assert {zc.record_format(r) for r in zwave_08} == {"json"}
    assert zc.record_format({"type": "dev", "msg": "a hub log line", "time": "2026-10-12 19:45:26.000"}) is None


def test_the_node_is_matched_by_value_not_by_spelling():
    assert zc.node_number("48") == 72 and zc.node_number("0103") == 259
    zwave, _ = load("zwave-20261012-plaintext.log")
    records = [r for r in zwave if zc.record_format(r) == "plaintext"]
    assert len(zc.plaintext_frames(records, "48")[0]) == 3, "[Node 072] is node 0x48"
    assert len(zc.plaintext_frames(records, "0048")[0]) == 3
    assert zc.plaintext_frames(records, "0103")[0] == []


# --------------------------------------------------------------------------- plaintext format


def test_oct12_on_set_callbacks_and_noise():
    zwave, _ = load("zwave-20261012-plaintext.log")
    row = zc.summarise(zwave, [start_requested("2026-10-12 19:45:26.277")], "4680", "48", "2200")
    assert row["format"] == "plaintext" and row["noise_source"] == "GetBackgroundRSSI channel 0"
    assert row["frames_device"] == 3, row
    assert row["on_frame_time"] == "2026-10-12 19:45:26.294 (+0.02 s)", row["on_frame_time"]
    assert row["on_acked"] == "yes (-87 dBm)", row["on_acked"]
    assert (row["callbacks_ok"], row["callbacks_ok_raw"], row["callbacks_failed"]) == (1, 1, 0), row
    assert (row["callbacks_missing"], row["missing_answered"]) == (1, 1), row
    assert row["noack_device"] == 0 and row["noack_times"] == "", row
    assert (row["ack_rssi_median"], row["ack_rssi_worst"]) == (-87.5, -88), row
    assert row["noise_median"] == -109, row
    assert (row["incoming_rssi_median"], row["incoming_rssi_worst"]) == (-88, -89), row
    assert row["callbacks_without_transmit"] == 1, "cb 47's transmit line is missing from the log"
    assert row["off_report_time"] == "no switch-off report" and row["off_acked"] == ""


def test_a_dropped_callback_line_is_read_from_the_raw_frame():
    zwave, _ = load("zwave-20261012-plaintext.log")
    frames = zc.plaintext_frames([r for r in zwave if zc.record_format(r) == "plaintext"], "48")[0]
    recovered = frame_at(frames, "2026-10-12 19:45:28.192")
    assert zc.verdict(recovered) == "yes (-88 dBm, raw callback frame)", zc.verdict(recovered)
    unanswered = frame_at(frames, "2026-10-12 19:47:03.869")
    assert zc.verdict(unanswered) == "no callback in log, answered after 0.03 s", zc.verdict(unanswered)


def test_oct13_missing_callback_answered_by_the_plug():
    zwave, _ = load("zwave-20261013-plaintext.log")
    row = zc.summarise(zwave, [start_requested("2026-10-13 19:45:25.769")], "4680", "48", "2200")
    assert row["on_frame_time"] == "2026-10-13 19:45:25.790 (+0.02 s)" and row["on_acked"] == "yes (-86 dBm)", row
    assert (row["frames_device"], row["noack_device"], row["missing_answered"]) == (2, 0, 1), row
    assert row["noise_median"] == -108, row
    frames = zc.plaintext_frames([r for r in zwave if zc.record_format(r) == "plaintext"], "48")[0]
    assert zc.verdict(frame_at(frames, "2026-10-13 19:49:48.804")) == "no callback in log, answered after 0.19 s"


def test_no_callback_and_no_answer_is_a_no_ack_frame():
    zwave, _ = load("zwave-20261013-plaintext.log")
    silent = [r for r in zwave if not (r["time"] == "2026-10-13 19:49:48.996"
                                       and "BridgeApplicationCommand" in r["plainTextMessage"])]
    assert len(silent) == len(zwave) - 1
    row = zc.summarise(silent, [start_requested("2026-10-13 19:45:25.769")], "4680", "48", "2200")
    assert (row["noack_device"], row["noack_share_pct"], row["noack_times"]) == (1, "50.0", "19:49:48.804"), row


def test_a_failed_decoded_callback_beats_an_ok_raw_frame():
    zwave, _ = load("zwave-20261012-plaintext.log")
    failed = [dict(r, plainTextMessage=r["plainTextMessage"].replace("OK, took", "NoAck, took"))
              if "callback id:            24" in r["plainTextMessage"] else r for r in zwave]
    row = zc.summarise(failed, [start_requested("2026-10-12 19:45:26.277")], "4680", "48", "2200")
    assert row["on_acked"] == "no (transmit status NoAck)", row["on_acked"]
    assert (row["noack_device"], row["callbacks_failed"], row["noack_times"]) == (1, 1, "19:45:26.294"), row


def test_the_off_set_itself_is_singled_out():
    zwave, _ = load("zwave-20261012-plaintext.log")
    zwave += [transmit("2026-10-12 19:59:01.000", 200, command="BinarySwitchCCSet", target=False),
              callback("2026-10-12 19:59:01.030", 200, ack=-90)]
    logs = [start_requested("2026-10-12 19:45:26.277"), switch_off("2026-10-12 19:59:01.100", 4680)]
    row = zc.summarise(zwave, logs, "4680", "48", "2200")
    assert row["off_report_time"] == "2026-10-12 19:59:01.100", row["off_report_time"]
    assert row["off_acked"] == "19:59:01.000 yes (-90 dBm)", row["off_acked"]


def test_an_off_report_without_an_off_set_says_so():
    zwave, _ = load("zwave-20261012-plaintext.log")
    logs = [start_requested("2026-10-12 19:45:26.277"), switch_off("2026-10-12 19:59:01.100", 4680)]
    row = zc.summarise(zwave, logs, "4680", "48", "2200")
    assert row["off_acked"] == "no BinarySwitchCCSet OFF to the device in the run", row["off_acked"]


def test_no_on_set_after_the_request_is_reported():
    """Oct 11: the app asked for ON, and no Set to the plug reached the log."""
    zwave = [transmit("2026-10-11 19:53:21.227", 9, node=259, command="Security2CCNonceReport"),
             callback("2026-10-11 19:53:21.300", 9, ack=-98)]
    row = zc.summarise(zwave, [start_requested("2026-10-11 19:45:26.493")], "4674", "0103", "2200")
    assert row["on_frame_time"] == "no BinarySwitchCCSet ON to the device after it" and row["on_acked"] == "", row
    assert row["frames_device"] == 1 and row["ack_rssi_median"] == -98, row


def test_a_reused_callback_id_keeps_the_unanswered_transmit():
    """Ids wrap at 255. The wrap-up's scratch parser kept pending transmits in a dict by id, so a
    later transmit with the same id dropped an unanswered one from the count (737 vs 759 on Oct 12)."""
    zwave = [transmit("2026-10-12 19:46:00.000", 5),
             transmit("2026-10-12 19:49:00.000", 5),
             callback("2026-10-12 19:49:00.030", 5)]
    row = zc.summarise(zwave, [], "4680", "48", "2200")
    assert (row["frames_device"], row["callbacks_ok"], row["noack_device"]) == (2, 1, 1), row
    assert row["noack_times"] == "19:46:00.000", row["noack_times"]


def test_a_callback_long_after_its_transmit_pairs_with_nothing():
    zwave = [transmit("2026-10-12 19:46:00.000", 7), callback("2026-10-12 19:47:10.000", 7)]
    row = zc.summarise(zwave, [], "4680", "48", "2200")
    assert (row["callbacks_ok"], row["callbacks_missing"], row["callbacks_without_transmit"]) == (0, 1, 1), row


# --------------------------------------------------------------------------- json format


def test_oct8_json_figures_are_unchanged():
    zwave, _ = load("zwave-20261008-json.log")
    logs = [start_requested("2026-10-08 19:45:25.743"), switch_off("2026-10-08 19:45:47.916", 4674)]
    row = zc.summarise(zwave, logs, "4674", "0103", "2200")
    assert row["format"] == "json" and "callbacks_ok" not in row
    assert (row["frames_device"], row["noack_device"], row["zero_ime_device"]) == (52, 0, 2), row
    assert (row["ack_rssi_median"], row["ack_rssi_worst"], row["noise_median"]) == (-93, -95, -102), row
    assert row["on_frame_time"] == "2026-10-08 19:45:25.823 (+0.08 s)" and row["on_acked"] == "yes (-95 dBm)", row
    assert row["off_acked"] == ("19:45:47.157 yes (-94 dBm); 19:45:47.358 yes (-94 dBm); "
                                "19:45:47.559 yes (-93 dBm); 19:45:47.660 unknown (zero IME)"), row["off_acked"]


def test_a_log_holding_both_formats_reads_both():
    zwave = load("zwave-20261008-json.log")[0] + load("zwave-20261012-plaintext.log")[0]
    row = zc.summarise(zwave, [], "4674", "0103", "2200")
    assert row["format"] == "mixed" and "GetBackgroundRSSI" in row["noise_source"], row
    assert row["frames_device"] == 52, row
    row = zc.summarise(zwave, [], "4680", "48", "2200")
    assert row["frames_device"] == 3, row


# --------------------------------------------------------------------------- command line


def test_analyze_uses_the_new_defaults():
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        assert zc.main(["--analyze", str(FIXTURES / "zwave-20261012-plaintext.log")]) == 0
    row = json.loads(out.getvalue())
    assert row["frames_device"] == 3 and row["on_frame_time"] == "no start-requested log line", row


# --------------------------------------------------------------------------- runner


def main() -> int:
    checks = [v for k, v in globals().items() if k.startswith("test_") and callable(v)]
    failures = 0
    for check in checks:
        try:
            check()
        except AssertionError as exc:
            failures += 1
            print(f"FAIL  {check.__name__}\n      {exc}")
        else:
            print(f"ok    {check.__name__}")
    print(f"\n{len(checks) - failures}/{len(checks)} checks passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
