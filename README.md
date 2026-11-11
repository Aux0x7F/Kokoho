# Kokoho

**Kokoho** (`org.tucsoncommunity.kokoho`) is an offline-first, zero-GMS Android application for passive RF, P25 trunking, Wi-Fi/BLE OUI, and ADS-B situational awareness in the Tucson sector.

- **Binary Releases & Community Hub:** [https://kokoho.tucsoncommunity.online](https://kokoho.tucsoncommunity.online)
- **Privacy Policy:** [https://kokoho.tucsoncommunity.online/privacy](https://kokoho.tucsoncommunity.online/privacy)
- **Terms of Use:** [https://kokoho.tucsoncommunity.online/terms](https://kokoho.tucsoncommunity.online/terms)

---

## Technical Architecture

### 1. Passive Signal Modalities & Agency Classification
Kokoho correlates observations across three passive receive paths against bundled and user-imported signature tables (`assets/kokoho_signatures.json`):

- **VHF / UHF / 800 MHz RF & P25 (`com.rfsentinel.app.sdr`):**
  Drives attached USB OTG RTL-SDR dongles (`RtlSdr.kt`) or local `rtl_tcp` streams (`RtlTcp.kt`) to sweep conventional federal allocations (162–174 MHz, 406–420 MHz) and decode P25 Phase 1 Trunking Signalling Blocks (`P25Tsbk.kt`, `P25Demod.kt`) for Network Access Codes (NAC) and talkgroup IDs (including PCWIN regional trunked talkgroups).
- **Fleet Wi-Fi & Bluetooth LE OUIs (`com.rfsentinel.app.service`, `com.rfsentinel.app.detect`):**
  Passively scans 802.11 beacons/probes and BLE advertisements for mobile routers, ruggedized terminals, and tactical radios (e.g., Cradlepoint, Sierra Wireless, Motorola APX/Astro, Axon fleet hardware). Colocated OUI sightings within a 90-second window fuse with concurrent RF detections into a single correlated entity track.
- **ADS-B Aircraft Telemetry (`com.rfsentinel.app.online.PoliceAircraft`, `com.rfsentinel.app.sdr.AdsbTracker`):**
  Matches 1090 MHz Mode-S broadcasts and regional ADS-B feeds against agency tail numbers, ICAO hex allocations, callsigns, and registered operators.

By default, filtering is scoped to **DHS** sub-agencies (**CBP**, **ICE**, **HSI**, **AMO**, **FPS**, and **Federal Interop**). Optional agency scopes (**Police / TPD**, **Sheriff / PCSO**, **Fire**, and **SAR**) can be toggled independently in Settings for ground RF/OUI and ADS-B aircraft.

### 2. Offline-First Spatial Engine & Client Triangulation
`KokohoPostureEngine` (`com.rfsentinel.app.kokoho.KokohoPostureEngine`) maintains a rolling spatial sample history per deterministic entity UUIDv5 within the user-configured signal decay window (default 30 minutes; configurable from 5 to 120 minutes):

1. **Single-Point / Narrow Baseline (`< 60 m` GPS spread):**
   Estimates emitter range from log-distance path loss (RSSI dBm for Wi-Fi/BLE; role-weighted SNR dB for repeater, mobile, or air-to-ground RF) and renders a user-centered uncertainty ring on the MapLibre vector map.
2. **Multi-Point Client Triangulation (`>= 60 m` GPS spread across `>= 3` samples):**
   Solves a weighted non-linear least-squares grid search over local tangent-plane coordinates to approximate the emitter center (`lat`, `lon`) and uncertainty radius (`±`), transitioning to a pinpointed marker only when residual uncertainty drops to `<= 35 m` (or exact ADS-B GPS coordinates are broadcast).
3. **Posture State Machine:**
   Evaluates active threats into four full-bleed UI posture states carried consistently across all screens:
   - `GREEN`: No active threats in enabled agency scope.
   - `YELLOW` (`PERIPHERY PRESENCE`): Active detection in sector (`> 300 m`, non-closing).
   - `ORANGE` (`HEADING THIS WAY`): Multi-sample closing velocity or approaching track vector (`<= 45°` of user bearing) or intermediate proximity (`<= 900 m` closing).
   - `RED` (`IMMINENT PROXIMITY`): High-confidence detection within `<= 300 m`.

### 3. Ephemeral Community Correlation & Zero-GMS Push (`KokohoHubClient`)
 Kokoho contains no Google Play Services (GMS), Firebase, or third-party analytics libraries:

- **Contribute Observations (Opt-Out):** When enabled, matched agency observations are submitted over TLS to `POST /v1/room/ingest`. The server correlates multi-node observations strictly in volatile RAM to refine position uncertainty, immediately stripping contributor coordinates and node identifiers before emission. Disabling the toggle immediately halts outbound reports.
- **Push Notifications (Opt-Out):** Uses an anonymous HTTP/2 Server-Sent Events stream (`GET /v1/stream`) while active and periodic coarse H3-resolution-6 regional check-ins (`POST /v1/checkin`, `AuxnetCheckinReceiver`) without transmitting device GPS coordinates.
- **Self-Hosted Live Updates (`UpdateChecker`, `UpdateInstaller`):** Queries `/v1/update/latest`, verifies SHA-256 digests and APK signing certificates, and streams installations via Android's `PackageInstaller` session API.

---

## Repository Structure

- `app/src/main/java/com/rfsentinel/app/kokoho/` — Posture engine, offline triangulation, agency signature matcher, Auxnet SSE/check-in client, and 3-tab UI controller (`Monitor` | `Map` | `Utility`).
- `app/src/main/java/com/rfsentinel/app/ui/` — MapLibre dark vector map (`RfMap.kt`), Entity Details view with inline local classification editing and `1/4`-screen radius map (`DeviceDetailActivity.kt`), and unified detection/telemetry logs (`DetectionLogActivity.kt`).
- `app/src/main/java/com/rfsentinel/app/sdr/` — RTL-SDR USB host driver, P25 Phase 1 TSBK control channel decoder, waterfall spectrum view, and NOAA antenna check.
- `app/src/main/java/com/rfsentinel/app/settings/` — Searchable Settings activity (agency toggles, ADS-B aircraft scope, signal decay time, privacy controls, RadioReference CSV/TSV channel import, and imperial/metric units).
- `app/src/main/assets/kokoho_signatures.json` — Bundled Tucson sector VHF/UHF frequencies, P25 NACs, PCWIN talkgroups, and fleet hardware OUIs.

---

## Building & Testing

Requires JDK 17+ and Android SDK (compileSdk 37, minSdk 26).

```bash
# Assemble the FOSS debug APK
./gradlew assembleFossDebug

# Run the Robolectric & JUnit test suite
./gradlew testFossDebugUnitTest
```

Output APK: `app/build/outputs/apk/foss/debug/Kokoho-v2.41.0-foss-debug.apk`

---

## License

Kokoho is free software distributed under the **GNU General Public License v3.0** (see [`LICENSE`](LICENSE)). Forked from upstream [RFSentinel](https://github.com/CIS-C0/RFSentinel); hardware driver and vocoder attributions are documented in [`app/src/main/java/com/rfsentinel/app/usb/NOTICE.md`](app/src/main/java/com/rfsentinel/app/usb/NOTICE.md) and [`app/src/main/java/com/rfsentinel/app/sdr/NOTICE.md`](app/src/main/java/com/rfsentinel/app/sdr/NOTICE.md).
