// Commanded pump runtime per recorded dose (seconds), the companion to dose-history.flux.
// Replace ${dosing_device} with the quoted device id for standalone use.
from(bucket: "waterguru")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "chlorine_dose" and r._field == "runtime_seconds")
  |> filter(fn: (r) => r.device_id == "${dosing_device}")
  |> filter(fn: (r) => exists r._value and r._value >= 0)
  |> group(columns: [])
  |> toFloat()
  |> keep(columns: ["_time", "_value"])
  |> sort(columns: ["_time"])
