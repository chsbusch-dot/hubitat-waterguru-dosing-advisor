import "date"
import "timezone"
option location = timezone.location(name: "America/Los_Angeles")

from(bucket: "waterguru")
  |> range(start: date.truncate(t: v.timeRangeStart, unit: 1mo), stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "chlorine_dose" and r._field == "volume_ml")
  |> filter(fn: (r) => r.device_id == "${dosing_device}")
  |> filter(fn: (r) => exists r._value and r._value >= 0)
  |> group(columns: [])
  |> toFloat()
  |> aggregateWindow(every: 1mo, fn: sum, createEmpty: false, timeSrc: "_start")
  |> sort(columns: ["_time"])
  |> map(fn: (r) => ({Period: string(v: date.year(t: r._time)) + "-" + (if date.month(t: r._time) < 10 then "0" else "") + string(v: date.month(t: r._time)), "US gallons": r._value / 3785.411784}))
