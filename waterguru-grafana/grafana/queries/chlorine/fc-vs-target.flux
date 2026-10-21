// Free chlorine vs the dosing advisor's own target (ppm).
// Replace ${dosing_device} with the quoted device id for standalone use.
from(bucket: "waterguru")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "chlorine_target")
  |> filter(fn: (r) => r.device_id == "${dosing_device}")
  |> filter(fn: (r) => r._field == "measured_ppm" or r._field == "target_ppm")
  |> filter(fn: (r) => exists r._value and r._value >= 0)
  |> group(columns: ["_field"])
  |> toFloat()
  |> keep(columns: ["_time", "_value", "_field"])
  |> sort(columns: ["_time"])
