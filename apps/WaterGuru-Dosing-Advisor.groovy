/*
 * WaterGuru Dosing Advisor (parent app)
 *
 * Install this once. It holds one child ("WaterGuru Dosing Advisor Pool") per
 * WaterGuru pool — use "Add a WaterGuru pool" to create them. All of the dosing
 * logic lives in the child app; this parent is just the container so you manage
 * every pool under a single app instead of a separate top-level app per pool.
 *
 * It is COMPLEMENTARY to the WaterGuru Integration app (which creates the
 * devices the children read) and does not duplicate that app's alerts, quiet
 * hours, pause switches or per-metric thresholds.
 *
 * All doses the children produce are ESTIMATES. Always confirm with your own
 * test kit before adding chemicals.
 *
 * Version history
 *   1.0.0 - Initial release. Parent/child: one advisor per WaterGuru pool.
 *           SLAM/CYA-aware free-chlorine dosing, WaterGuru doseAdvice
 *           pass-through, generic fallback formulas, single-message delivery to
 *           capability.notification devices.
 *   1.1.0 - Each child can now maintain a dashboard tile device and send an
 *           optional daily summary. (Parent unchanged except this version bump.)
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

def appVersion() { "1.1.0" }

definition(
    name:        "WaterGuru Dosing Advisor",
    namespace:   "chsbusch-dot",
    author:      "Chris Busch",
    description: "Create one dosing advisor per WaterGuru pool. Each reads a WaterGuru device and recommends how much of which chemical to add (SLAM/CYA-aware free chlorine plus WaterGuru's own pH/TA/CH/CYA advice), then notifies you.",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/chsbusch-dot/hubitat-waterguru-dosing-advisor/main/apps/WaterGuru-Dosing-Advisor.groovy",
    singleInstance: true,
    installOnOpen:  true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "WaterGuru Dosing Advisor", uninstall: true, install: true) {
        section {
            paragraph "Create one dosing advisor per WaterGuru pool. Each child reads a WaterGuru device and recommends how much of which chemical to add, then notifies your devices."
            paragraph "Complementary to the WaterGuru Integration app — it does not duplicate that app's alerts, quiet hours or thresholds."
        }

        section("<b>Pools</b>") {
            app(name: "childPools", appName: "WaterGuru Dosing Advisor Pool",
                namespace: "chsbusch-dot", title: "Add a WaterGuru pool", multiple: true)
        }

        section {
            input "logEnable", "bool", title: "Enable debug logging (parent)", defaultValue: false
            paragraph "<small>v${appVersion()} — doses are estimates; always confirm with your own test kit before adding chemicals.</small>"
        }
    }
}

def installed() { log.info "WaterGuru Dosing Advisor (parent) installed — ${childApps?.size() ?: 0} pool(s)" }
def updated()   { log.info "WaterGuru Dosing Advisor (parent) updated — ${childApps?.size() ?: 0} pool(s)" }
def uninstalled() { }
