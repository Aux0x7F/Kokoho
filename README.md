# Kokoho

**Kokoho** is a free, open-source, tracker-free community early-warning and situational awareness app for Android in the Tucson sector.

- **Download APK & Community Hub:** [https://kokoho.tucsoncommunity.online](https://kokoho.tucsoncommunity.online)
- **Privacy Policy:** [https://kokoho.tucsoncommunity.online/privacy](https://kokoho.tucsoncommunity.online/privacy)
- **Terms of Use:** [https://kokoho.tucsoncommunity.online/terms](https://kokoho.tucsoncommunity.online/terms)

## Overview

Kokoho helps families, neighbors, and legal observers stay aware of nearby federal and local enforcement activity through passive reception of unencrypted radio frequencies, P25 trunking control channels, known fleet Wi-Fi/Bluetooth hardware identifiers (OUIs), and ADS-B aircraft broadcasts:

- **Simple At-a-Glance Monitor:** Full-screen status color (Green, Yellow, Orange, Red) and clear agency identification across **DHS, CBP (Border Patrol), ICE, HSI, AMO (Air & Marine), and FPS** by default, with optional toggles for local **Police (TPD), Sheriff (PCSO), Fire, and Search & Rescue (SAR)**.
- **Live Map & Offline Triangulation:** Displays estimated distance rings around detected signals and narrows down source positions locally using multi-point GPS and signal strength—working completely offline in the field.
- **100% Tracker-Free & Zero Google Play Services:** Built without Google Play Services (GMS), third-party analytics, or user accounts.
- **Ephemeral Community Observation Sharing:** Optional community contribution pools detections in volatile memory to help neighbors pinpoint threats more accurately without storing user locations.

## Building from Source

```bash
./gradlew assembleFossDebug
```

## License

Kokoho is free software distributed under the **GNU General Public License v3.0** (see [`LICENSE`](LICENSE)). Forked from upstream [RFSentinel](https://github.com/CIS-C0/RFSentinel).
