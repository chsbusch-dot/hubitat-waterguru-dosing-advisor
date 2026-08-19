/*
 * WaterGuru Dosing Tile (companion driver)
 *
 * A tiny, display-only virtual device. One is created and maintained per pool by
 * the "WaterGuru Dosing Advisor Pool" (child) app so the pool's latest dosing
 * recommendation can be shown on a Hubitat dashboard — apps cannot render tiles
 * themselves, so they push their result into a device instead.
 *
 * The app owns this device: it writes the attributes below via sendEvent on
 * every calculation. There is nothing to configure here and no commands to run;
 * the driver exists so the attributes are declared and therefore selectable on a
 * dashboard.
 *
 * Attributes
 *   status         GREEN / YELLOW / RED — RED when a chemical is needed or SLAM
 *                  is active, YELLOW for optional/advisory items, GREEN when all
 *                  in range. Handy for coloring a tile.
 *   recommendation Concise one-liner, e.g. "Add 4.8 gal chlorine · 1.2 lb baking soda".
 *   detail         The full multi-line recommendation (same text as the notification).
 *   tileHtml       A formatted HTML snippet for an "Attribute" dashboard tile.
 *   lastCalc       When the app last updated this tile.
 *
 * All doses are ESTIMATES. Always confirm with your own test kit before adding.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

def driverVersion() { "1.1.0" }

metadata {
    definition(
        name:      "WaterGuru Dosing Tile",
        namespace: "chsbusch-dot",
        author:    "Chris Busch",
        importUrl: "https://raw.githubusercontent.com/chsbusch-dot/hubitat-waterguru-dosing-advisor/main/drivers/WaterGuru-Dosing-Tile.groovy"
    ) {
        capability "Sensor"

        attribute "status",         "enum", ["GREEN", "YELLOW", "RED"]
        attribute "recommendation", "string"
        attribute "detail",         "string"
        attribute "tileHtml",       "string"
        attribute "lastCalc",       "date"
    }
}

// The parent app pushes attribute values with sendEvent; the only work this
// driver does on its own is to show a friendly placeholder before the first
// calculation lands.
def installed() {
    sendEvent(name: "status",         value: "YELLOW")
    sendEvent(name: "recommendation", value: "Waiting for the first calculation…")
    sendEvent(name: "detail",         value: "This tile is filled in by its WaterGuru Dosing Advisor pool app on the next sample, or when you press \"Calculate & send now\".")
    sendEvent(name: "tileHtml",       value: "<div style='font-family:sans-serif;padding:8px 10px'>Waiting for the first WaterGuru Dosing Advisor calculation…</div>")
}

def updated() {}
