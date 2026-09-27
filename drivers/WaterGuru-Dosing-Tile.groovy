/*
 * WaterGuru Dosing Tile (companion driver)
 *
 * Display and telemetry device maintained by the WaterGuru Dosing Advisor Pool
 * app. Human-readable attributes support Hubitat dashboards; numeric attributes
 * support external history and aggregation through Maker API.
 */

def driverVersion() { "1.2.0" }

metadata {
    definition(
        name:      "WaterGuru Dosing Tile",
        namespace: "chsbusch-dot",
        author:    "Chris Busch",
        importUrl: "https://raw.githubusercontent.com/chsbusch-dot/hubitat-waterguru-dosing-advisor/main/drivers/WaterGuru-Dosing-Tile.groovy"
    ) {
        capability "Sensor"

        attribute "status",                 "enum", ["GREEN", "YELLOW", "RED"]
        attribute "recommendation",         "string"
        attribute "detail",                "string"
        attribute "tileHtml",              "string"
        attribute "lastCalc",              "date"

        attribute "lastDoseEpochMs",       "number"
        attribute "lastDoseMl",            "number"
        attribute "lastDoseRuntimeSeconds", "number"
        attribute "tankCapacityMl",        "number"
        attribute "tankRemainingMl",       "number"
        attribute "tankPercent",           "number"
        attribute "tankRunwayDays",        "number"
        attribute "freeChlorine",          "number"
        attribute "targetFreeChlorine",    "number"
    }
}

def installed() {
    sendEvent(name: "status",         value: "YELLOW")
    sendEvent(name: "recommendation", value: "Waiting for the first calculation…")
    sendEvent(name: "detail",         value: "This tile is filled in by its WaterGuru Dosing Advisor pool app on the next sample, or when you press \"Calculate & send now\".")
    sendEvent(name: "tileHtml",       value: "<div style='font-family:sans-serif;padding:8px 10px'>Waiting for the first WaterGuru Dosing Advisor calculation…</div>")
}

def updated() {}
