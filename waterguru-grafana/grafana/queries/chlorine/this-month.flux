import "date"
import "timezone"
option location = timezone.location(name: "America/Los_Angeles")

from(bucket: "waterguru")
  |> range(start: date.truncate(t: now(), unit: 1mo), stop: now())
  |> filter(fn: (r) => r._measurement == "chlorine_dose" and r._field == "volume_ml")
  |> filter(fn: (r) => r.device_id == "${dosing_device}")
  |> filter(fn: (r) => exists r._value and r._value >= 0)
  |> group(columns: [])
  |> toFloat()
  |> sum()
  |> map(fn: (r) => ({_time: now(), _value: r._value / 3785.411784}))
