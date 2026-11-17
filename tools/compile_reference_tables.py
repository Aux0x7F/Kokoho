#!/usr/bin/env python3
"""
Kokoho Unified Reference Lookup Compiler (`tools/compile_reference_tables.py`)

Ingests public-domain & open-source aviation, spectrum, hardware OUI, and cellular datasets:
  1. FAA Releasable Aircraft DB (`ReleasableAircraft.zip`: `MASTER.txt` + `ACFTREF.txt`)
  2. `sdr-enthusiasts/plane-alert-db` (`plane-alert-db.csv`, `plane-alert-pia.csv`, `pol`, `gov`, `mil`, `civ`)
  3. `wiedehopf/tar1090-db` (`tar1090-aircraft.csv.gz`)
  4. OurAirports (`airports.csv` + `runways.csv` — airports, runways, & hospital helipads)
  5. FCC ULS Land Mobile (`fcc_lmpriv` + `fcc_lmcomm`: `HD.dat`, `EN.dat`, `FR.dat`, `LO.dat`, `EM.dat`)
  6. DHS CISA NIFOG v2.01/2.02 nationwide interoperability channels + PCWIN municipal exclusions
  7. IEEE Registration Authority (`ieee_oui.csv` 24-bit, `ieee_mam.csv` 28-bit, `ieee_oui36.csv` 36-bit)
  8. Bluetooth SIG Company Identifiers (`bt_company_identifiers.yaml`)
  9. FCC ULS Cellular (`fcc_l_cell.zip`) + surveyed AZ `cell_towers.json` (+ optional OpenCelliD / MLS drops)

Outputs:
  - Full Hub SQLite database (`kokoho_reference.sqlite3`)
  - Compact Android offline asset tables (`app/src/main/assets/reference/*` + `assets/aircraft/law_enforcement.tsv`)
"""

import argparse
import csv
import gzip
import io
import json
import os
import re
import sqlite3
import zipfile
from collections import defaultdict
from pathlib import Path

# Known FBI / DOJ / DHS covert front companies & ISR aviation contractors (public OSINT / BuzzFeed / FAA records)
COVERT_SHELL_OWNERS_RE = re.compile(
    r"\b("
    r"FVX RESEARCH|OBRERO|KQM AVIATION|NBR AVIATION|PXW SERVICES|PSL SURVEYS|"
    r"LCB LEASING|NG RESEARCH|RKT PRODUCTIONS|OBR LEASING|OTV LEASING|NBY PRODUCTIONS|"
    r"KLJ AVIATION|MVR AVIATION|FOS SERVICES|SLK AVIATION|TKR HELICOPTERS|"
    r"WORLDWIDE AIRCRAFT LEASING|SIERRA NEVADA CORP|STRAIGHT FLIGHT|LASAI AVIATION|"
    r"TENAX AEROSPACE|DYNAMIC AVIATION|AERO PRECISION|AIRBORNE TACTICAL ADVANTAGE"
    r")\b",
    re.IGNORECASE,
)

EXPLICIT_LE_DHS_RE = re.compile(
    r"\b("
    r"HOMELAND SECURITY|CUSTOMS AND BORDER|BORDER PATROL|IMMIGRATION AND CUSTOMS|"
    r"FEDERAL BUREAU OF INVESTIGATION|DRUG ENFORCEMENT|MARSHALS SERVICE|ALCOHOL TOBACCO|"
    r"POLICE|SHERIFF|HIGHWAY PATROL|STATE PATROL|STATE TROOPER|DEPT OF PUBLIC SAFETY|"
    r"DEPARTMENT OF PUBLIC SAFETY|TEXAS DEPT OF PUBLIC SAFETY|ARIZONA DEPT OF PUBLIC SAFETY|"
    r"ROYAL CANADIAN MOUNTED|ONTARIO PROVINCIAL POLICE|SURETE DU QUEBEC"
    r")\b",
    re.IGNORECASE,
)

MEDEVAC_OWNER_RE = re.compile(
    r"\b("
    r"AIR EVAC|LIFENET|AIR METHODS|GUARDIAN FLIGHT|NATIVE AIR|PHI HEALTH|PHI AIR MEDICAL|"
    r"MED-TRANS|MERCY AIR|REACH AIR MEDICAL|CLASSIC AIR MEDICAL|SURVIVAL FLIGHT|"
    r"CAREFLIGHT|LIFE FLIGHT|LIFEFLIGHT|MEDFLIGHT|STARFLIGHT|BOSTON MEDFLIGHT|"
    r"CALSTAR|AERO MED|BANNER HEALTH|MAYO CLINIC|INTERMOUNTAIN LIFE"
    r")\b",
    re.IGNORECASE,
)

FLIGHT_SCHOOL_OWNER_RE = re.compile(
    r"\b("
    r"EMBRY-RIDDLE|EMBRY RIDDLE|UND AEROSPACE|UNIVERSITY OF NORTH DAKOTA|"
    r"ATP FLIGHT|ATP USA|CAE OXFORD|LUFTHANSA AVIATION TRAINING|AIRLINE TRAINING CENTER|"
    r"AEROGUARD|TRANSPAC AVIATION|WESTWIND SCHOOL|SIERRA CHARLIE AVIATION|"
    r"FLIGHT TRAINING|FLIGHT SCHOOL|FLIGHT ACADEMY|FLYING CLUB|AVIATION ACADEMY|"
    r"CIVIL AIR PATROL|SPARTAN EDUCATION|PURDUE UNIVERSITY|AUBURN UNIVERSITY|"
    r"WESTERN MICHIGAN UNIVERSITY|LETOURNEAU|BAYLOR INSTITUTE"
    r")\b",
    re.IGNORECASE,
)

AIRLINE_OWNER_RE = re.compile(
    r"\b("
    r"SOUTHWEST AIRLINES|AMERICAN AIRLINES|UNITED AIRLINES|DELTA AIR LINES|"
    r"JETBLUE|ALASKA AIRLINES|FRONTIER AIRLINES|SPIRIT AIRLINES|ALLEGIANT AIR|"
    r"HAWAIIAN AIRLINES|SUN COUNTRY|SKYWEST AIRLINES|REPUBLIC AIRWAYS|ENVOY AIR|"
    r"ENDEAVOR AIR|PSA AIRLINES|MESA AIRLINES|HORIZON AIR|COMMUTAIR|PIEDMONT AIRLINES|"
    r"FEDERAL EXPRESS|FEDEX|UNITED PARCEL SERVICE|ATLAS AIR|KALITTA AIR|ABX AIR|AMERIFLIGHT"
    r")\b",
    re.IGNORECASE,
)

TRAINER_MODEL_RE = re.compile(
    r"\b("
    r"PA-?38|PA38|TOMAHAWK|PA-?28|P28[A-Z]|CHEROKEE|ARCHER|WARRIOR|CADET|PA-?44|PA44|SEMINOLE|"
    r"C-?150|C-?152|C-?172|150[A-Z]?|152|172[A-Z]?|SKYHAWK|DA20|DA-?20|DA40|DA-?40|"
    r"SR20|AA-?5|CHEETAH|TIGER|SGS ?2-33|ASK-?21|BLANIK|GROB ?103"
    r")\b",
    re.IGNORECASE,
)

SURVEILLANCE_MODEL_RE = re.compile(
    r"\b("
    r"PC-?12|C182|182[A-Z]|C206|206[A-Z]|T206|U206|C208|208B?|C210|210[A-Z]|"
    r"B300|B350|BE20|BE30|200|300|DHC-?8|DH8[A-D]|P68|DA42|DA62|AS350|H125|EC120|EC135|H145|B407|B429|B206|OH-?58|UH-?1|AW139|AW119|MD500|369[A-Z]+|R44|R66"
    r")\b",
    re.IGNORECASE,
)

HELI_ICAO_TYPES = {
    "AS50", "AS55", "EC20", "EC30", "EC35", "EC45", "EC55", "B06", "B06T", "B407",
    "B412", "B429", "B430", "B212", "UH1", "H60", "H72", "H47", "H64", "MD52",
    "MD60", "H500", "R22", "R44", "R66", "A109", "A119", "A139", "A169", "S76", "S92",
}

TACTICAL_OUI_KEYWORDS = [
    ("AXON ENTERPRISE", "BODY_CAM", ""),
    ("TASER INTERNATIONAL", "BODY_CAM", ""),
    ("ZEPCAM", "BODY_CAM", "POLICE"),
    ("DIGITAL ALLY", "BODY_CAM", "POLICE"),
    ("UTILITY ASSOCIATES", "BODY_CAM", "POLICE"),
    ("WATCHGUARD VIDEO", "BODY_CAM", "POLICE"),
    ("GETAC", "VEHICLE_KIT", "POLICE"),
    ("CYBERKAR", "VEHICLE_KIT", "POLICE"),
    ("CRADLEPOINT", "FLEET_ROUTER", "DHS_CBP"),
    ("ERICSSON ENTERPRISE WIRELESS", "FLEET_ROUTER", "DHS_CBP"),
    ("PEPLINK", "FLEET_ROUTER", "DHS_HSI"),
    ("PEPWAVE", "FLEET_ROUTER", "DHS_HSI"),
    ("SIERRA WIRELESS", "FLEET_ROUTER", "DHS_CBP"),
    ("SEMTECH", "FLEET_ROUTER", "DHS_CBP"),
    ("INSEEGO", "FLEET_ROUTER", "DHS_CBP"),
    ("NOVATEL WIRELESS", "FLEET_ROUTER", "DHS_CBP"),
    ("DIGI INTERNATIONAL", "FLEET_ROUTER", "DHS_CBP"),
    ("PERSISTENT SYSTEMS", "TACTICAL_MESH", "DHS_CBP"),
    ("SILVUS TECHNOLOGIES", "TACTICAL_MESH", "DHS_CBP"),
    ("TRELLISWARE", "TACTICAL_MESH", "DHS_CBP"),
    ("DOMO TACTICAL", "TACTICAL_MESH", "DHS_HSI"),
    ("COBHAM", "TACTICAL_MESH", "DHS_HSI"),
    ("PERCEPTICS", "BORDER_ALPR", "DHS_CBP"),
    ("GENETEC", "ALPR", "POLICE"),
    ("ELSAG", "ALPR", "POLICE"),
    ("LEONARDO", "ALPR", "POLICE"),
    ("FLOCK SAFETY", "FLOCK_ALPR", "POLICE"),
    ("SKYDIO", "DRONE", "DRONE"),
    ("PARROT", "DRONE", "DRONE"),
    ("TEAL DRONES", "DRONE", "DRONE"),
    ("RED CAT", "DRONE", "DRONE"),
    ("AEROVIRONMENT", "DRONE", "DRONE"),
    ("SHIELD AI", "DRONE", "DRONE"),
    ("ANDURIL", "DRONE", "DRONE"),
    ("AUTEL ROBOTICS", "DRONE", "DRONE"),
    ("SZ DJI TECHNOLOGY", "DRONE", "DRONE"),
    ("UAVIONIX", "DRONE", "DRONE"),
    ("FREEFLY SYSTEMS", "DRONE", "DRONE"),
    ("BRINC DRONES", "DRONE", "DRONE"),
    ("APPLIED CONCEPTS", "PATROL_RADAR", "POLICE"),
    ("KUSTOM SIGNALS", "PATROL_RADAR", "POLICE"),
    ("DECATUR ELECTRONICS", "PATROL_RADAR", "POLICE"),
    ("MOTOROLA SOLUTIONS", "LMR_RADIO", "POLICE"),
    ("L3HARRIS", "LMR_RADIO", "DHS_INTEROP"),
    ("HARRIS CORPORATION", "LMR_RADIO", "DHS_INTEROP"),
    ("EF JOHNSON", "LMR_RADIO", "POLICE"),
    ("TAIT INTERNATIONAL", "LMR_RADIO", "POLICE"),
    ("KNOX COMPANY", "FIRE_KNOX", "FIRE"),
    ("3SI SECURITY", "BAIT_TRACKER", "POLICE"),
]

# Complete DHS CISA NIFOG Interop Channels (VHF / UHF / 700 / 800 / Federal LE & Incident Response)
NIFOG_CHANNELS = [
    # Federal Law Enforcement (NIFOG Federal Interop)
    (167_087_500, "FEDLEC-1", "NIFOG Federal Law Enforcement Calling (Simplex/Out)", "DHS_INTEROP", "DIRECT_TACTICAL", "P25"),
    (162_087_500, "FEDLEC-1-IN", "NIFOG Federal Law Enforcement Calling Mobile Input", "DHS_INTEROP", "MOBILE_INPUT", "P25"),
    (167_250_000, "FEDTAC-1", "NIFOG Federal Law Enforcement Tactical 1", "DHS_INTEROP", "DIRECT_TACTICAL", "P25"),
    (167_750_000, "FEDTAC-2", "NIFOG Federal Law Enforcement Tactical 2", "DHS_INTEROP", "DIRECT_TACTICAL", "P25"),
    (168_112_500, "FEDTAC-3", "NIFOG Federal Law Enforcement Tactical 3", "DHS_INTEROP", "DIRECT_TACTICAL", "P25"),
    (168_462_500, "LE-6", "NIFOG Federal LE Interop 6 Direct", "DHS_INTEROP", "DIRECT_TACTICAL", "P25"),
    (167_462_500, "LE-2-IN", "NIFOG Federal LE Interop 2 Mobile Input", "DHS_INTEROP", "MOBILE_INPUT", "P25"),
    (162_262_500, "LE-3-IN", "NIFOG Federal LE Interop 3 Mobile Input", "DHS_INTEROP", "MOBILE_INPUT", "P25"),
    (162_837_500, "LE-4-IN", "NIFOG Federal LE Interop 4 Mobile Input", "DHS_INTEROP", "MOBILE_INPUT", "P25"),
    (163_287_500, "LE-5-IN", "NIFOG Federal LE Interop 5 Mobile Input", "DHS_INTEROP", "MOBILE_INPUT", "P25"),
    (414_037_500, "LE-B", "NIFOG UHF Federal Law Enforcement Calling Simplex", "DHS_INTEROP", "DIRECT_TACTICAL", "P25"),
    (409_987_500, "LE-10", "NIFOG UHF Federal Law Enforcement Tactical 10", "DHS_INTEROP", "DIRECT_TACTICAL", "P25"),
    (410_187_500, "LE-16", "NIFOG UHF Federal Law Enforcement Tactical 16", "DHS_INTEROP", "DIRECT_TACTICAL", "P25"),
    (418_987_500, "LE-B-IN", "NIFOG UHF Federal LE Mobile Input", "DHS_INTEROP", "MOBILE_INPUT", "P25"),
    (419_187_500, "LE-11-IN", "NIFOG UHF Federal LE 11 Mobile Input", "DHS_INTEROP", "MOBILE_INPUT", "P25"),
    # VHF Non-Federal Public Safety Interop (NIFOG)
    (155_475_000, "VLAW31", "NIFOG VHF Law Enforcement National Interop", "POLICE", "DIRECT_TACTICAL", "FM"),
    (155_482_500, "VLAW32", "NIFOG VHF Law Enforcement Tactical Interop", "POLICE", "DIRECT_TACTICAL", "FM"),
    (151_137_500, "VTAC11", "NIFOG VHF Tactical Interop 11", "POLICE", "DIRECT_TACTICAL", "FM"),
    (154_452_500, "VTAC12", "NIFOG VHF Tactical Interop 12", "POLICE", "DIRECT_TACTICAL", "FM"),
    (158_737_500, "VTAC13", "NIFOG VHF Tactical Interop 13", "POLICE", "DIRECT_TACTICAL", "FM"),
    (159_472_500, "VTAC14", "NIFOG VHF Tactical Interop 14", "POLICE", "DIRECT_TACTICAL", "FM"),
    (154_280_000, "VFIRE21", "NIFOG VHF Fire Mutual Aid 21", "FIRE", "DIRECT_TACTICAL", "FM"),
    (154_265_000, "VFIRE22", "NIFOG VHF Fire Mutual Aid 22", "FIRE", "DIRECT_TACTICAL", "FM"),
    (154_295_000, "VFIRE23", "NIFOG VHF Fire Mutual Aid 23", "FIRE", "DIRECT_TACTICAL", "FM"),
    (155_340_000, "VMED28", "NIFOG VHF EMS / Air-to-Ground Medical", "FIRE", "DIRECT_TACTICAL", "FM"),
    (155_347_500, "VMED29", "NIFOG VHF EMS Tactical 29", "FIRE", "DIRECT_TACTICAL", "FM"),
    (155_160_000, "VSAR16", "National VHF Search & Rescue Simplex", "SAR", "DIRECT_TACTICAL", "FM"),
    # UHF Non-Federal Public Safety Interop (NIFOG)
    (453_212_500, "UCALL40", "NIFOG UHF Calling Repeater Output", "POLICE", "REPEATER", "FM"),
    (458_212_500, "UCALL40D", "NIFOG UHF Calling Direct / Input", "POLICE", "DIRECT_TACTICAL", "FM"),
    (453_462_500, "UTAC41", "NIFOG UHF Tactical 41 Output", "POLICE", "REPEATER", "FM"),
    (458_462_500, "UTAC41D", "NIFOG UHF Tactical 41 Direct / Input", "POLICE", "DIRECT_TACTICAL", "FM"),
    (453_712_500, "UTAC42", "NIFOG UHF Tactical 42 Output", "POLICE", "REPEATER", "FM"),
    (458_712_500, "UTAC42D", "NIFOG UHF Tactical 42 Direct / Input", "POLICE", "DIRECT_TACTICAL", "FM"),
    (453_862_500, "UTAC43", "NIFOG UHF Tactical 43 Output", "POLICE", "REPEATER", "FM"),
    (458_862_500, "UTAC43D", "NIFOG UHF Tactical 43 Direct / Input", "POLICE", "DIRECT_TACTICAL", "FM"),
    # 700 MHz Nationwide Interop (NIFOG) — Direct / Mobile Inputs (799-805 MHz) & Simplex
    (769_243_750, "7CALL50", "NIFOG 700 MHz Calling Output", "POLICE", "REPEATER", "P25"),
    (799_243_750, "7CALL50-IN", "NIFOG 700 MHz Calling Mobile Input", "POLICE", "MOBILE_INPUT", "P25"),
    (769_143_750, "7TAC51", "NIFOG 700 MHz Tactical 51 Output", "POLICE", "REPEATER", "P25"),
    (799_143_750, "7TAC51-IN", "NIFOG 700 MHz Tactical 51 Mobile Input", "POLICE", "MOBILE_INPUT", "P25"),
    (769_643_750, "7TAC52", "NIFOG 700 MHz Tactical 52 Output", "POLICE", "REPEATER", "P25"),
    (799_643_750, "7TAC52-IN", "NIFOG 700 MHz Tactical 52 Mobile Input", "POLICE", "MOBILE_INPUT", "P25"),
    (770_143_750, "7LAW61", "NIFOG 700 MHz Law Enforcement 61 Output", "POLICE", "REPEATER", "P25"),
    (800_143_750, "7LAW61-IN", "NIFOG 700 MHz Law Enforcement 61 Mobile Input", "POLICE", "MOBILE_INPUT", "P25"),
    (770_643_750, "7LAW62", "NIFOG 700 MHz Law Enforcement 62 Output", "POLICE", "REPEATER", "P25"),
    (800_643_750, "7LAW62-IN", "NIFOG 700 MHz Law Enforcement 62 Mobile Input", "POLICE", "MOBILE_INPUT", "P25"),
    (773_256_250, "7CALL70", "NIFOG 700 MHz Calling 70 Output", "POLICE", "REPEATER", "P25"),
    (803_256_250, "7CALL70-IN", "NIFOG 700 MHz Calling 70 Mobile Input", "POLICE", "MOBILE_INPUT", "P25"),
    (774_006_250, "7LAW81", "NIFOG 700 MHz Law Enforcement 81 Output", "POLICE", "REPEATER", "P25"),
    (804_006_250, "7LAW81-IN", "NIFOG 700 MHz Law Enforcement 81 Mobile Input", "POLICE", "MOBILE_INPUT", "P25"),
    (774_506_250, "7LAW82", "NIFOG 700 MHz Law Enforcement 82 Output", "POLICE", "REPEATER", "P25"),
    (804_506_250, "7LAW82-IN", "NIFOG 700 MHz Law Enforcement 82 Mobile Input", "POLICE", "MOBILE_INPUT", "P25"),
    # 800 MHz Nationwide NPSPAC Interop (Rebanded)
    (851_012_500, "8CALL90", "NIFOG 800 MHz NPSPAC Calling Output", "POLICE", "REPEATER", "FM"),
    (806_012_500, "8CALL90-IN", "NIFOG 800 MHz NPSPAC Calling Mobile Input", "POLICE", "MOBILE_INPUT", "FM"),
    (851_512_500, "8TAC91", "NIFOG 800 MHz NPSPAC Tactical 91 Output", "POLICE", "REPEATER", "FM"),
    (806_512_500, "8TAC91-IN", "NIFOG 800 MHz NPSPAC Tactical 91 Mobile Input", "POLICE", "MOBILE_INPUT", "FM"),
    (852_012_500, "8TAC92", "NIFOG 800 MHz NPSPAC Tactical 92 Output", "POLICE", "REPEATER", "FM"),
    (807_012_500, "8TAC92-IN", "NIFOG 800 MHz NPSPAC Tactical 92 Mobile Input", "POLICE", "MOBILE_INPUT", "FM"),
    (852_512_500, "8TAC93", "NIFOG 800 MHz NPSPAC Tactical 93 Output", "POLICE", "REPEATER", "FM"),
    (807_512_500, "8TAC93-IN", "NIFOG 800 MHz NPSPAC Tactical 93 Mobile Input", "POLICE", "MOBILE_INPUT", "FM"),
    (853_012_500, "8TAC94", "NIFOG 800 MHz NPSPAC Tactical 94 Output", "POLICE", "REPEATER", "FM"),
    (808_012_500, "8TAC94-IN", "NIFOG 800 MHz NPSPAC Tactical 94 Mobile Input", "POLICE", "MOBILE_INPUT", "FM"),
]


def dms_to_decimal(deg_s: str, min_s: str, sec_s: str, dir_s: str):
    try:
        d = float(deg_s)
        m = float(min_s or 0)
        s = float(sec_s or 0)
        val = d + m / 60.0 + s / 3600.0
        if dir_s.strip().upper() in ("S", "W"):
            val = -val
        return round(val, 6)
    except Exception:
        return None


def classify_fcc_agency(service_code: str, licensee: str) -> tuple[str, bool]:
    """Returns (agency_code, is_commercial_or_non_le_municipal)."""
    up = licensee.upper()
    if any(k in up for k in ("SCHOOL", "UNIFIED", "UNIVERSITY", "COLLEGE", "BUS ", "TRANSIT", "SUN TRAN",
                             "WATER", "ELECTRIC", "GAS ", "POWER", "SANITATION", "WASTE", "STREETS",
                             "PARKS ", "RECREATION", "AIRPORT AUTHORITY", "HOUSING", "LIBRARY",
                             "IRRIGATION", "AGRICULTURAL", "MEDICAL RESPONSE", "AMBULANCE", "RURAL METRO",
                             "RAILROAD", "UNION PACIFIC", "BNSF",
                             "HOSPITAL", "MEDICAL CENTER", "BANNER", "TMC", "CASINO", "RESORT", "HOTEL",
                             "RAYTHEON", "FREEPORT", "ASARCO", "CATERPILLAR", "WALMART", "TARGET")):
        return ("COMMERCIAL", True)
    if service_code in ("IG", "YG", "YB", "YI", "YU", "GB", "GU", "GJ", "GO"):
        # Check if an explicit law-enforcement or fire agency holds an IG license
        if "SHERIFF" in up:
            return ("SHERIFF", False)
        if "POLICE" in up:
            return ("POLICE", False)
        if "FIRE" in up:
            return ("FIRE", False)
        return ("COMMERCIAL", True)
    # Public safety pool (PW / YW / YE / SG / SY / GE / GP / GF)
    if "FIRE" in up or "NORTHWEST FIRE" in up or "GOLDER RANCH" in up or "GREEN VALLEY FIRE" in up or "RINCON VALLEY" in up or "DREXEL HEIGHTS" in up:
        return ("FIRE", False)
    if "SEARCH AND RESCUE" in up or "RESCUE" in up:
        return ("SAR", False)
    if "SHERIFF" in up:
        return ("SHERIFF", False)
    if any(k in up for k in (
        "POLICE", "PUBLIC SAFETY", "HIGHWAY PATROL", "LAW ENFORCEMENT",
        "COUNTY", "CITY OF", ", CITY", "TOWN OF", ", TOWN",
        "STATE OF ARIZONA", "ARIZONA, STATE", "ARIZONA DEPT", "DEPT OF PUBLIC SAFETY",
        "NATION", "TRIBE", "INDIAN COMMUNITY", "WIRELESS INTEGRATED", "REGIONAL WIRELESS"
    )):
        return ("SHERIFF" if ("COUNTY" in up and "CITY" not in up and service_code == "PW") else "POLICE", False)
    if service_code in ("PW", "YW", "YE", "SG", "SY", "GE", "GP", "GF"):
        return ("POLICE", False)
    return ("COMMERCIAL", True)


def emission_to_mode(em: str) -> str:
    e = em.upper()
    if any(k in e for k in ("8K10F1E", "8K10F1D", "8K10F1W", "9K80D7W", "9K80F1E")):
        return "P25"
    if any(k in e for k in ("7K60FXE", "7K60FXD", "7K60F1E", "7K60F1W")):
        return "DMR"
    if any(k in e for k in ("4K00F1E", "4K00F1D")):
        return "NXDN"
    return "FM"


def build_aircraft_tables(raw_dir: Path, conn: sqlite3.Connection, assets_dir: Path, existing_le_tsv: Path):
    print("[1/5] Compiling Aircraft Lookup Tables (FAA MASTER + ACFTREF + plane-alert-db + tar1090-db) ...")
    cur = conn.cursor()
    cur.execute("""
        CREATE TABLE IF NOT EXISTS ref_aircraft (
            hex TEXT PRIMARY KEY,
            reg TEXT NOT NULL,
            icao_type TEXT NOT NULL,
            model TEXT NOT NULL,
            owner TEXT NOT NULL,
            state TEXT NOT NULL,
            airframe_kind TEXT NOT NULL,
            registrant_kind TEXT NOT NULL,
            disposition TEXT NOT NULL,
            agency_hint TEXT NOT NULL,
            source TEXT NOT NULL
        )
    """)

    # Preserve existing law_enforcement.tsv entries first (filtering out tiny consumer quadcopters like Yuneec/Mavic)
    le_entries = {}
    if existing_le_tsv.exists():
        for line in existing_le_tsv.read_text(encoding="utf-8").splitlines():
            if not line.strip() or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) >= 3:
                hx = parts[0].strip().lower()
                reg = parts[1].strip()
                own = parts[2].strip()
                mdl = parts[3].strip() if len(parts) > 3 else ""
                if not re.search(r"\b(YUNEEC|DJI MAVIC|PHANTOM [234]|SPARK|INSPIRE 1)\b", mdl, re.I):
                    le_entries[hx] = (reg, own, mdl)

    # 1a. Parse FAA ACFTREF.txt + MASTER.txt
    acft_ref = {}
    faa_rows = {}
    with zipfile.ZipFile(raw_dir / "ReleasableAircraft.zip") as zf:
        with zf.open("ACFTREF.txt") as f:
            reader = csv.reader(io.TextIOWrapper(f, encoding="utf-8-sig", errors="replace"))
            next(reader, None)
            for r in reader:
                if len(r) < 9:
                    continue
                code = r[0].strip()
                mfr = r[1].strip()
                mdl = r[2].strip()
                tacft = r[3].strip()
                seats = int(r[8].strip()) if r[8].strip().isdigit() else 0
                acft_ref[code] = (mfr, mdl, tacft, seats)

        with zf.open("MASTER.txt") as f:
            reader = csv.reader(io.TextIOWrapper(f, encoding="utf-8-sig", errors="replace"))
            next(reader, None)
            for r in reader:
                if len(r) < 34:
                    continue
                hx = r[33].strip().lower()
                if len(hx) != 6:
                    continue
                n_num = r[0].strip()
                reg = ("N" + n_num) if n_num and not n_num.upper().startswith("N") else n_num
                mfr_code = r[2].strip()
                reg_type_code = r[5].strip()
                owner = r[6].strip()
                state = r[10].strip().upper()
                t_acft = r[18].strip()
                mfr, mdl, ref_t, seats = acft_ref.get(mfr_code, ("", "", t_acft, 0))
                eff_t = t_acft or ref_t
                full_model = f"{mfr} {mdl}".strip()

                air_kind = {
                    "1": "G",  # Glider
                    "2": "L",  # Balloon
                    "3": "L",  # Blimp
                    "4": "F",  # Fixed single
                    "5": "F",  # Fixed multi
                    "6": "H",  # Rotorcraft / Helicopter!
                    "7": "U",  # Weight-shift
                    "8": "U",  # Powered parachute
                    "9": "H",  # Gyroplane
                }.get(eff_t, "F")

                reg_kind = {
                    "1": "I",  # Individual
                    "2": "C",  # Partnership
                    "3": "C",  # Corporation
                    "4": "I",  # Co-Owned
                    "5": "G",  # Government
                    "7": "L",  # LLC
                    "8": "C",  # Non-citizen corp
                    "9": "I",  # Non-citizen co-owned
                }.get(reg_type_code, "I")

                faa_rows[hx] = {
                    "reg": reg,
                    "icao_type": mdl[:12],
                    "model": full_model,
                    "owner": owner,
                    "state": state,
                    "kind": air_kind,
                    "reg_kind": reg_kind,
                    "seats": seats,
                    "source": "FAA",
                }

    # 1b. Enrich / add global & ICAO type codes from tar1090-db (`aircraft.csv.gz`)
    with gzip.open(raw_dir / "tar1090-aircraft.csv.gz", "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\r\n").split(";")
            if len(parts) < 5:
                continue
            hx = parts[0].strip().lower()
            if len(hx) != 6:
                continue
            reg = parts[1].strip()
            icao_t = parts[2].strip().upper()
            flags = parts[3].strip()
            desc = parts[4].strip()
            own = parts[6].strip() if len(parts) > 6 else ""
            if hx in faa_rows:
                if icao_t:
                    faa_rows[hx]["icao_type"] = icao_t
                if icao_t in HELI_ICAO_TYPES:
                    faa_rows[hx]["kind"] = "H"
            else:
                # Keep Mexican (XC-/XA-/XB-), Canadian (C-), military, or special airframes
                is_border_or_interesting = (
                    reg.startswith(("XC-", "XA-", "XB-", "C-F", "C-G"))
                    or hx.startswith(("0d", "c0", "ae", "adf"))
                    or icao_t in HELI_ICAO_TYPES
                )
                if is_border_or_interesting:
                    kind = "H" if icao_t in HELI_ICAO_TYPES else ("G" if "GLID" in desc.upper() else "F")
                    faa_rows[hx] = {
                        "reg": reg,
                        "icao_type": icao_t,
                        "model": desc,
                        "owner": own,
                        "state": "",
                        "kind": kind,
                        "reg_kind": "G" if reg.startswith("XC-") else "C",
                        "seats": 0,
                        "source": "TAR1090",
                    }

    # 1c. Overlay `plane-alert-db.csv` and `plane-alert-pia.csv`
    pa_overrides = {}
    pia_file = raw_dir / "plane-alert-pia.csv"
    if pia_file.exists():
        with open(pia_file, "r", encoding="utf-8-sig", errors="replace") as f:
            reader = csv.reader(f)
            next(reader, None)
            for r in reader:
                if len(r) >= 1 and len(r[0].strip()) == 6:
                    hx = r[0].strip().lower()
                    reg = r[1].strip() if len(r) > 1 else "PIA"
                    op = r[2].strip() if len(r) > 2 and r[2].strip() else "Privacy ICAO Address (Masked Surveillance/Corp)"
                    tp = r[3].strip() if len(r) > 3 else ""
                    ic = r[4].strip().upper() if len(r) > 4 else ""
                    pa_overrides[hx] = ("PIA", reg, op, tp, ic, "PIA")

    pa_main = raw_dir / "plane-alert-db.csv"
    if pa_main.exists():
        with open(pa_main, "r", encoding="utf-8-sig", errors="replace") as f:
            reader = csv.reader(f)
            next(reader, None)
            for r in reader:
                if len(r) < 6 or len(r[0].strip()) != 6:
                    continue
                hx = r[0].strip().lower()
                reg = r[1].strip()
                op = r[2].strip()
                tp = r[3].strip()
                ic = r[4].strip().upper()
                cmpg = r[5].strip()  # Pol, Gov, Mil, Civ
                tags = " ".join(r[6:10]).strip()
                pa_overrides[hx] = (cmpg, reg, op, tp, ic, tags)

    db_batch = []
    compact_meta_lines = []
    sw_states = {"AZ", "NM", "CA", "NV", "UT", "CO", "TX"}

    all_hexes = sorted(set(faa_rows.keys()) | set(pa_overrides.keys()) | set(le_entries.keys()))
    for hx in all_hexes:
        base = faa_rows.get(hx, {
            "reg": "", "icao_type": "", "model": "", "owner": "",
            "state": "", "kind": "F", "reg_kind": "C", "seats": 0, "source": "PA",
        })
        reg = base["reg"]
        icao_t = base["icao_type"]
        model = base["model"]
        owner = base["owner"]
        state = base["state"]
        kind = base["kind"]
        reg_kind = base["reg_kind"]
        src = base["source"]
        pa_cmpg = ""
        pa_tags = ""

        if hx in pa_overrides:
            pa_cmpg, pa_reg, pa_op, pa_tp, pa_ic, pa_tags = pa_overrides[hx]
            if pa_reg:
                reg = pa_reg
            if pa_op:
                owner = pa_op
            if pa_tp:
                model = pa_tp
            if pa_ic:
                icao_t = pa_ic
            if icao_t in HELI_ICAO_TYPES or "HELI" in model.upper() or "AS350" in model.upper() or "EC135" in model.upper():
                kind = "H"
            if pa_cmpg == "PIA":
                reg_kind = "P"
            src = f"{src}+PA" if src != "PA" else "PA"

        combined = f"{owner} {model} {icao_t} {pa_tags}".upper()
        is_drone_toy = bool(re.search(r"\b(YUNEEC|DJI |MAVIC|MATRICE|PHANTOM [234]|SMALL UAS|QUADROCOPTER)\b", combined))
        if is_drone_toy:
            kind = "U"

        # Classify disposition & agency_hint
        disp = "CIV"
        agency = ""
        if MEDEVAC_OWNER_RE.search(combined) or "AIR AMBULANCE" in combined or "MEDEVAC" in combined:
            disp = "MED"
        elif AIRLINE_OWNER_RE.search(owner):
            disp = "AIR"
        elif kind in ("G", "L", "U") and not EXPLICIT_LE_DHS_RE.search(owner) and pa_cmpg != "Pol":
            disp = "TRN"
        elif FLIGHT_SCHOOL_OWNER_RE.search(combined) and not EXPLICIT_LE_DHS_RE.search(owner):
            disp = "TRN"
        elif (
            pa_cmpg == "Pol"
            or EXPLICIT_LE_DHS_RE.search(owner)
            or COVERT_SHELL_OWNERS_RE.search(owner)
            or hx in le_entries
        ):
            if not is_drone_toy:
                disp = "LE"
                if re.search(r"\b(CUSTOMS|BORDER|HOMELAND|ICE|ERO|HSI|CBP)\b", combined):
                    agency = "DHS_AMO"
                elif "SHERIFF" in combined:
                    agency = "SHERIFF"
                elif "FIRE" in combined or "FOREST SERVICE" in combined:
                    agency = "FIRE"
                elif "RESCUE" in combined:
                    agency = "SAR"
                elif COVERT_SHELL_OWNERS_RE.search(owner):
                    agency = "DHS_AMO"
                else:
                    agency = "POLICE"
        elif pa_cmpg == "PIA" and SURVEILLANCE_MODEL_RE.search(combined):
            disp = "LE"
            agency = "DHS_AMO"
        elif pa_cmpg == "Gov" or reg_kind == "G":
            if any(k in combined for k in ("FOREST", "FIRE", "LAND MANAGEMENT")):
                disp = "LE"
                agency = "FIRE"
            elif not FLIGHT_SCHOOL_OWNER_RE.search(combined):
                disp = "GOV"
                agency = "POLICE"
        elif pa_cmpg == "Mil" or hx.startswith("ae"):
            disp = "MIL"
        elif TRAINER_MODEL_RE.search( f"{icao_t} {model}" ):
            disp = "TRN"

        if disp == "LE" and kind != "U" and owner:
            le_entries[hx] = (reg or hx.upper(), owner[:80], (model or icao_t)[:60])

        db_batch.append((hx, reg, icao_t, model, owner, state, kind, reg_kind, disp, agency, src))

        # Include in compact Android offline `aircraft_meta.tsv.gz` when:
        #  - Special disposition (LE, GOV, MED, TRN, AIR, MIL)
        #  - Helicopter (kind == 'H')
        #  - Registered in AZ / Southwest border states
        #  - Surveillance-capable model owned by LLC / Corp / PIA
        keep_mobile = (
            disp in ("LE", "GOV", "MED", "TRN", "MIL")
            or kind == "H"
            or state in sw_states
            or (reg_kind in ("L", "C", "P") and bool(SURVEILLANCE_MODEL_RE.search(f"{icao_t} {model}")))
        )
        if keep_mobile:
            short_owner = owner[:44].replace("\t", " ")
            short_type = (icao_t or model[:16]).replace("\t", " ")
            compact_meta_lines.append(
                f"{hx}\t{reg}\t{short_type}\t{kind}\t{reg_kind}\t{disp}\t{agency}\t{short_owner}"
            )

    cur.executemany(
        "INSERT OR REPLACE INTO ref_aircraft VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        db_batch,
    )
    conn.commit()

    # Write upgraded `law_enforcement.tsv`
    le_out = [
        "# Law-enforcement & tactical government/shell aircraft by ICAO 24-bit hex, registration, owner, model.",
        "# Sources: FAA Releasable Aircraft Registry, Transport Canada, sdr-enthusiasts/plane-alert-db (Pol/Gov/PIA).",
    ]
    for hx in sorted(le_entries.keys()):
        reg, own, mdl = le_entries[hx]
        le_out.append(f"{hx}\t{reg}\t{own}\t{mdl}")
    existing_le_tsv.write_text("\n".join(le_out) + "\n", encoding="utf-8")

    # Write `assets/reference/aircraft_meta.tsv.gz`
    ref_dir = assets_dir / "reference"
    ref_dir.mkdir(parents=True, exist_ok=True)
    with gzip.open(ref_dir / "aircraft_meta.tsv.gz", "wt", encoding="utf-8", compresslevel=9) as gz:
        gz.write("# hex\treg\ticaotype\tkind\treg_kind\tdisp\tagency\towner\n")
        for ln in compact_meta_lines:
            gz.write(ln + "\n")

    print(
        f"  -> Hub ref_aircraft: {len(db_batch):,} rows | "
        f"APK law_enforcement.tsv: {len(le_entries):,} rows | "
        f"APK aircraft_meta.tsv.gz: {len(compact_meta_lines):,} rows ({(ref_dir / 'aircraft_meta.tsv.gz').stat().st_size:,} B)"
    )


def build_airfield_tables(raw_dir: Path, conn: sqlite3.Connection, assets_dir: Path):
    print("[2/5] Compiling Airfields, Runways & Hospital Helipads (OurAirports) ...")
    cur = conn.cursor()
    cur.execute("""
        CREATE TABLE IF NOT EXISTS ref_airfields (
            ident TEXT PRIMARY KEY,
            kind TEXT NOT NULL,
            name TEXT NOT NULL,
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            elev_ft INTEGER NOT NULL,
            region TEXT NOT NULL,
            max_runway_ft INTEGER NOT NULL,
            is_hospital_helipad INTEGER NOT NULL
        )
    """)

    max_rwy = defaultdict(int)
    with open(raw_dir / "runways.csv", "r", encoding="utf-8-sig", errors="replace") as f:
        for r in csv.DictReader(f):
            ident = (r.get("airport_ident") or "").strip()
            try:
                ln = int(float(r.get("length_ft") or 0))
            except Exception:
                ln = 0
            if ident and ln > max_rwy[ident]:
                max_rwy[ident] = ln

    keep_regions = {"US-AZ", "US-NM", "US-CA", "US-NV", "US-UT", "US-TX", "MX-SON", "MX-CHH", "MX-BCN"}
    az_border_box = lambda lat, lon: 30.5 <= lat <= 37.2 and -115.2 <= lon <= -108.2

    db_rows = []
    apk_lines = ["# ident\tkind\tlat\tlon\telev_ft\tmax_rwy_ft\tis_hosp\tname"]
    with open(raw_dir / "airports.csv", "r", encoding="utf-8-sig", errors="replace") as f:
        for r in csv.DictReader(f):
            kind = (r.get("type") or "").strip()
            if kind == "closed" or not kind:
                continue
            reg = (r.get("iso_region") or "").strip()
            if reg not in keep_regions:
                continue
            try:
                lat = round(float(r["latitude_deg"]), 5)
                lon = round(float(r["longitude_deg"]), 5)
            except Exception:
                continue
            elev = int(float(r["elevation_ft"])) if (r.get("elevation_ft") or "").strip().lstrip("-").isdigit() else 2500
            ident = (r.get("ident") or "").strip()
            name = (r.get("name") or "").strip().replace("\t", " ")
            rwy = max_rwy.get(ident, 0)
            up_name = name.upper()
            is_hosp = 1 if (
                kind == "heliport" and any(k in up_name for k in ("HOSPITAL", "MEDICAL", "HEALTH", "BANNER", "CLINIC", "TRAUMA", "ST JOSEPH", "ST MARY", "TMC", "NORTHWEST MED"))
            ) else 0
            db_rows.append((ident, kind, name, lat, lon, elev, reg, rwy, is_hosp))
            if reg in ("US-AZ", "MX-SON") or az_border_box(lat, lon):
                apk_lines.append(f"{ident}\t{kind}\t{lat}\t{lon}\t{elev}\t{rwy}\t{is_hosp}\t{name[:60]}")

    cur.executemany("INSERT OR REPLACE INTO ref_airfields VALUES (?,?,?,?,?,?,?,?,?)", db_rows)
    conn.commit()
    out_p = assets_dir / "reference" / "az_airfields.tsv"
    out_p.write_text("\n".join(apk_lines) + "\n", encoding="utf-8")
    print(f"  -> Hub ref_airfields: {len(db_rows):,} rows | APK az_airfields.tsv: {len(apk_lines)-1:,} rows ({out_p.stat().st_size:,} B)")


def build_fcc_and_nifog_tables(raw_dir: Path, conn: sqlite3.Connection, assets_dir: Path):
    print("[3/5] Compiling FCC ULS Arizona Land-Mobile (l_LMpriv + l_LMcomm) + DHS NIFOG Tables ...")
    cur = conn.cursor()
    cur.execute("""
        CREATE TABLE IF NOT EXISTS ref_rf_az (
            freq_hz INTEGER NOT NULL,
            cls TEXT NOT NULL,
            agency TEXT NOT NULL,
            mode TEXT NOT NULL,
            callsign TEXT NOT NULL,
            licensee TEXT NOT NULL,
            station_class TEXT NOT NULL,
            lat REAL,
            lon REAL,
            county TEXT NOT NULL,
            PRIMARY KEY (freq_hz, callsign, station_class)
        )
    """)
    cur.execute("CREATE INDEX IF NOT EXISTS idx_ref_rf_az_hz ON ref_rf_az(freq_hz)")

    db_rows = []
    # Aggregate per-frequency summary for compact Android fast lookup
    # Priority: NIFOG > PS_MOB > PS_REP > IG_COMM
    freq_summary = {}

    for pool_dir_name in ("fcc_lmpriv", "fcc_lmcomm"):
        pdir = raw_dir / pool_dir_name
        if not pdir.exists():
            continue
        # Step 1: Find all USIs with a location in AZ from LO.dat
        az_locs = {}  # (usi, loc_num) -> (lat, lon, county)
        az_usis = set()
        with open(pdir / "LO.dat", "r", encoding="latin1", errors="replace") as f:
            for line in f:
                parts = line.split("|")
                if len(parts) > 26 and parts[14].strip() == "AZ":
                    usi = parts[1].strip()
                    loc_num = parts[8].strip()
                    county = parts[13].strip().upper()
                    lat = dms_to_decimal(parts[19], parts[20], parts[21], parts[22])
                    lon = dms_to_decimal(parts[23], parts[24], parts[25], parts[26])
                    az_usis.add(usi)
                    az_locs[(usi, loc_num)] = (lat, lon, county)

        # Step 2: Filter Active ('A') headers in HD.dat for AZ USIs
        active_hd = {}  # usi -> (callsign, service_code)
        with open(pdir / "HD.dat", "r", encoding="latin1", errors="replace") as f:
            for line in f:
                parts = line.split("|")
                if len(parts) > 6 and parts[1].strip() in az_usis and parts[5].strip() == "A":
                    active_hd[parts[1].strip()] = (parts[4].strip(), parts[6].strip())

        # Step 3: Licensee names from EN.dat (Entity Type 'L' = Licensee, never 'CL' Contact Attorney)
        en_name = {}
        with open(pdir / "EN.dat", "r", encoding="latin1", errors="replace") as f:
            for line in f:
                parts = line.split("|")
                if len(parts) > 17 and parts[1].strip() in active_hd and (parts[5].strip() == "L" or parts[1].strip() not in en_name):
                    nm = parts[7].strip() or f"{parts[8].strip()} {parts[10].strip()}".strip()
                    if nm:
                        en_name[parts[1].strip()] = nm

        # Step 4: Emission modes from EM.dat
        em_mode = {}
        em_path = pdir / "EM.dat"
        if em_path.exists():
            with open(em_path, "r", encoding="latin1", errors="replace") as f:
                for line in f:
                    parts = line.split("|")
                    if len(parts) > 7 and parts[1].strip() in active_hd:
                        m = emission_to_mode(parts[7].strip())
                        if m != "FM" or parts[1].strip() not in em_mode:
                            em_mode[parts[1].strip()] = m

        # Step 5: Frequencies from FR.dat
        with open(pdir / "FR.dat", "r", encoding="latin1", errors="replace") as f:
            for line in f:
                parts = line.split("|")
                if len(parts) < 11:
                    continue
                usi = parts[1].strip()
                if usi not in active_hd:
                    continue
                loc_num = parts[6].strip()
                if (usi, loc_num) not in az_locs:
                    continue
                st_cls = parts[8].strip().upper()
                try:
                    mhz = float(parts[10].strip())
                except Exception:
                    continue
                if mhz < 30.0 or mhz > 960.0:
                    continue
                hz = int(round(mhz * 1_000_000))
                callsign, svc = active_hd[usi]
                licensee = en_name.get(usi, callsign)
                lat, lon, county = az_locs[(usi, loc_num)]
                mode = em_mode.get(usi, "FM")
                agency, is_comm = classify_fcc_agency(svc, licensee)
                # 47 CFR 90.7: FB* / FXO / FX2 are fixed base/repeater outputs; MO* / FX1 (control station) transmit on mobile inputs!
                is_fixed_rep = st_cls.startswith("FB") or st_cls in ("FXO", "FX2", "FX")
                if is_comm:
                    cls = "IG_COMM"
                else:
                    cls = "PS_REP" if is_fixed_rep else "PS_MOB"

                db_rows.append((hz, cls, agency, mode, callsign, licensee[:70], st_cls, lat, lon, county))

                # Rank PS_REP above PS_MOB on the same frequency so base/repeater outputs (which often co-license talkaround MO)
                # are ALWAYS suppressed as repeaters, while dedicated mobile-input channels (no FB*) remain PS_MOB!
                is_so_az = county in ("PIMA", "SANTA CRUZ", "COCHISE", "PINAL")
                rank = (
                    2 if is_so_az else 1,
                    3 if cls == "PS_REP" else (2 if cls == "PS_MOB" else 1),
                )
                prev = freq_summary.get(hz)
                if prev is None or rank > prev[0]:
                    freq_summary[hz] = (rank, cls, agency, mode, callsign, licensee[:48].replace("\t", " "), lat, lon)

    # 3b. Ingest RadioReference Arizona Conventional Channels (`rr_az_conventional.csv` — 605 NTIA AZ Federal + DPS + Counties)
    conv_csv = raw_dir / "rr_az_conventional.csv"
    if conv_csv.exists():
        with open(conv_csv, "r", encoding="utf-8-sig", errors="replace") as f:
            for r in csv.DictReader(f):
                try:
                    hz = int(round(float(r["FrequencyMHz"]) * 1_000_000))
                except Exception:
                    continue
                src_page = (r.get("SourcePage") or "").strip()
                cat = (r.get("Category") or "").strip()
                lic = (r.get("License") or "").strip()
                ch_type = (r.get("Type") or "").strip().upper()
                tone = (r.get("ToneNAC") or "").strip()
                alpha = (r.get("AlphaTag") or "").strip()
                desc = (r.get("Description") or "").strip()
                raw_mode = (r.get("Mode") or "FM").strip().upper()
                tag = (r.get("Tag") or "").strip()
                mode = "P25" if "P25" in raw_mode else ("DMR" if "DMR" in raw_mode else ("NXDN" if "NXDN" in raw_mode else ("AM" if "AM" in raw_mode else "FM")))
                up_all = f"{src_page} {cat} {alpha} {desc} {tag}".upper()

                is_non_le = (
                    src_page == "AZ_MED_HELI"
                    or tag in ("Military", "Railroad", "Transportation", "Public Works", "Utilities", "Business", "Schools", "Hospital", "Media", "Ham", "Aircraft")
                    or any(k in up_all for k in (
                        "CIVIL AIR PATROL", "WESTERN AREA POWER", "WAPA", "AIR TRAFFIC", "FAA ",
                        "AIR FORCE BASE", "MARINE CORPS", "PROVING GROUND", "CAMP NAVAJO", "GOLDWATER RANGE",
                        "TRANSPORTATION SECURITY", "MARINA", "SCHOOL", "TRANSIT", "PUBLIC WORKS",
                        "WATER", "SANITATION", "PARKS", "HOSPITAL", "MEDEVAC", "MEDICAL HELICOPTER"
                    ))
                )
                if is_non_le:
                    ag = "COMMERCIAL"
                    cls = "IG_COMM"
                else:
                    if re.search(r"\b(IMMIGRATION|ICE|ERO)\b", up_all):
                        ag = "DHS_ICE"
                    elif re.search(r"\bHSI\b|HOMELAND SECURITY INV", up_all):
                        ag = "DHS_HSI"
                    elif re.search(r"\b(OMAHA|CBP AIR)\b|AIR (&|AND) MARINE", up_all):
                        ag = "DHS_AMO"
                    elif re.search(r"PROTECTIVE SERVICE|\bFPS\b", up_all):
                        ag = "DHS_FPS"
                    elif re.search(r"\b(CUSTOMS|BORDER PATROL|CBP|USBP)\b", up_all):
                        ag = "DHS_CBP"
                    elif any(k in up_all for k in ("SHERIFF", "PCSO", "PCSD", "CCSO", "MCSO", "YCSO")):
                        ag = "SHERIFF"
                    elif any(k in up_all for k in ("FIRE", "WILDFIRE", "HOTSHOT", "TFD")):
                        ag = "FIRE"
                    elif "SEARCH AND RESCUE" in up_all or "RESCUE" in up_all or "SAR " in up_all:
                        ag = "SAR"
                    elif any(k in up_all for k in ("FED COMMON", "INTEROP", "MUTUAL AID")):
                        ag = "DHS_INTEROP" if "FED" in up_all else "POLICE"
                    else:
                        ag = "POLICE"
                    # Only pure Mobile ('M') channels are alertable simplex/inputs; RM/BM/B/F are fixed repeaters/bases!
                    cls = "PS_MOB" if ch_type == "M" else "PS_REP"

                call_or_alpha = (alpha or lic or "RR-AZ")[:24].replace("\t", " ")
                full_desc = f"{cat}: {desc}"[:70].replace("\t", " ")
                if tone and "NAC" in tone.upper():
                    full_desc = f"{full_desc} [{tone}]"[:70]
                db_rows.append((hz, cls, ag, mode, call_or_alpha, full_desc, ch_type or "RM", None, None, src_page))

                rank = (5, 3 if cls == "PS_REP" else (2 if cls == "PS_MOB" else 1))
                prev = freq_summary.get(hz)
                if prev is None or (prev[1] == "PS_REP" and cls == "PS_MOB" and False) or rank > prev[0]:
                    # Never let a mobile entry downgrade an already-known fixed repeater on the same frequency
                    if prev is not None and prev[1] == "PS_REP" and cls == "PS_MOB":
                        pass
                    else:
                        freq_summary[hz] = (rank, cls, ag, mode, call_or_alpha, full_desc[:48], None, None)

    # 3c. Ingest RadioReference Arizona Trunked Site & Control Channels (`rr_az_trunk_sites.csv`)
    cur.execute("""
        CREATE TABLE IF NOT EXISTS ref_trunk_sites_az (
            system_name TEXT NOT NULL,
            sid TEXT NOT NULL,
            nac TEXT NOT NULL,
            site_name TEXT NOT NULL,
            freq_hz INTEGER NOT NULL,
            is_control INTEGER NOT NULL,
            PRIMARY KEY (system_name, site_name, freq_hz)
        )
    """)
    site_csv = raw_dir / "rr_az_trunk_sites.csv"
    site_rows = []
    if site_csv.exists():
        with open(site_csv, "r", encoding="utf-8-sig", errors="replace") as f:
            for r in csv.DictReader(f):
                try:
                    hz = int(round(float(r["FrequencyMHz"]) * 1_000_000))
                except Exception:
                    continue
                sys_nm = (r.get("System") or "").strip()
                sid = (r.get("SID") or "").strip()
                nac = (r.get("NAC") or "").strip()
                site_nm = (r.get("Site") or "").strip()
                is_ctrl = 1 if (r.get("IsControl") or "").strip() == "1" else 0
                site_rows.append((sys_nm, sid, nac, site_nm, hz, is_ctrl))
                is_mil = sys_nm in ("USAF_3D6", "FT_HUACHUCA", "YPG")
                cls = "IG_COMM" if is_mil else "PS_REP"
                ag = "COMMERCIAL" if is_mil else "POLICE"
                lbl = f"{sys_nm}-{nac}"[:20]
                desc = f"{sys_nm} {site_nm} ({'Ctrl' if is_ctrl else 'Voice'})"[:48].replace("\t", " ")
                db_rows.append((hz, cls, ag, "P25", lbl, desc, "FB8" if is_ctrl else "FB2", None, None, sys_nm))
                prev = freq_summary.get(hz)
                if prev is None or prev[0][0] < 6:
                    freq_summary[hz] = ((6, 3), cls, ag, "P25", lbl, desc, None, None)
        if site_rows:
            cur.executemany("INSERT OR REPLACE INTO ref_trunk_sites_az VALUES (?,?,?,?,?,?)", site_rows)

    # 3d. Overlay NIFOG nationwide channels
    for hz, alpha, desc, agency, role, mode in NIFOG_CHANNELS:
        cls = "NIFOG_REP" if role == "REPEATER" else ("NIFOG_IN" if role == "MOBILE_INPUT" else "NIFOG_TAC")
        db_rows.append((hz, cls, agency, mode, alpha, desc, role, None, None, "NATIONWIDE"))
        freq_summary[hz] = ((9, 9), cls, agency, mode, alpha, desc, None, None)

    cur.executemany("INSERT OR REPLACE INTO ref_rf_az VALUES (?,?,?,?,?,?,?,?,?,?)", db_rows)

    # 3e. RadioReference P25 Talkgroup CSVs (`rr_az_talkgroups.csv` + any drop-in exports)
    cur.execute("""
        CREATE TABLE IF NOT EXISTS ref_talkgroups_az (
            system_name TEXT NOT NULL,
            tg_dec INTEGER NOT NULL,
            alpha_tag TEXT NOT NULL,
            mode TEXT NOT NULL,
            description TEXT NOT NULL,
            tag TEXT NOT NULL,
            category TEXT NOT NULL,
            agency TEXT NOT NULL,
            PRIMARY KEY (system_name, tg_dec)
        )
    """)
    tg_files = sorted({p for pat in ("*6643*.csv", "*7252*.csv", "*7735*.csv", "*3759*.csv", "*talkgroup*.csv") for p in raw_dir.glob(pat)})
    tg_map = {}
    for tg_csv in tg_files:
        default_sys = "PCWIN" if "7735" in tg_csv.name or "pcwin" in tg_csv.name.lower() else ("AZWINS" if "3759" in tg_csv.name or "azwins" in tg_csv.name.lower() else tg_csv.stem.upper())
        with open(tg_csv, "r", encoding="utf-8-sig", errors="replace") as f:
            for r in csv.DictReader(f):
                sys_nm = (r.get("System") or default_sys).strip().upper()
                dec_s = (r.get("Decimal") or r.get("DEC") or r.get("Talkgroup") or "").strip()
                if not dec_s.isdigit():
                    continue
                dec = int(dec_s)
                alpha = (r.get("Alpha Tag") or r.get("Alpha") or "").strip().replace("\t", " ")
                mode = (r.get("Mode") or "D").strip().replace("\t", " ")
                desc = (r.get("Description") or "").strip().replace("\t", " ")
                tag = (r.get("Tag") or "").strip().replace("\t", " ")
                cat = (r.get("Category") or "").strip().replace("\t", " ")
                up_all = f"{sys_nm} {alpha} {desc} {tag} {cat}".upper()
                if (
                    sys_nm in ("USAF_3D6", "FT_HUACHUCA", "YPG")
                    or tag in ("Public Works", "Transportation", "Utilities", "Hospital", "Schools", "Business", "Media", "Military", "Other", "Deprecated")
                    or any(k in up_all for k in (
                        "TRANSIT", "PUBLIC WORKS", "STREETS", "WATER", "SANITATION", "PARKS",
                        "SCHOOL", "UNIFIED", "HOSPITAL", "MEDICAL", "AMBULANCE", "DEPARTMENT OF TRANSPORTATION",
                        "ADOT", "RADIO TECHNICIAN", "AIRPORT AUTHORITY", "LOCAL GOVERNMENT", "ANIMAL"
                    ))
                ):
                    ag = "IGNORE"
                elif re.search(r"\b(IMMIGRATION|ICE|ERO)\b", up_all):
                    ag = "DHS_ICE"
                elif re.search(r"\bHSI\b", up_all):
                    ag = "DHS_HSI"
                elif re.search(r"\b(CUSTOMS|BORDER PATROL|CBP|USBP)\b", up_all):
                    ag = "DHS_CBP"
                elif re.search(r"PROTECTIVE SERVICE|\bFPS\b", up_all):
                    ag = "DHS_FPS"
                elif re.search(r"\b(FEDERAL|USMS|MARSHALS|ATF|FBI|DEA)\b", up_all):
                    ag = "DHS_INTEROP"
                elif any(k in up_all for k in ("SHERIFF", "PCSO", "PCSD")):
                    ag = "SHERIFF"
                elif any(k in up_all for k in ("FIRE", "TFD", "RURAL METRO")):
                    ag = "FIRE"
                elif "RESCUE" in up_all or "SAR" in up_all:
                    ag = "SAR"
                else:
                    ag = "POLICE"
                tg_map[(sys_nm, dec)] = (sys_nm, dec, alpha, mode, desc[:80], tag, cat[:60], ag)

    if tg_map:
        tg_rows = sorted(tg_map.values())
        cur.executemany("INSERT OR REPLACE INTO ref_talkgroups_az VALUES (?,?,?,?,?,?,?,?)", tg_rows)
        old_tg = assets_dir / "reference" / "az_talkgroups.tsv"
        if old_tg.exists():
            old_tg.unlink()
        out_tg = assets_dir / "reference" / "az_talkgroups.tsv.gz"
        with gzip.open(out_tg, "wt", encoding="utf-8", compresslevel=9) as gz:
            gz.write("# sys\ttg_dec\tagency\tmode\talpha\tdescription\n")
            for sys_nm, dec, alpha, mode, desc, tag, cat, ag in tg_rows:
                gz.write(f"{sys_nm}\t{dec}\t{ag}\t{mode}\t{alpha}\t{desc}\n")
        print(f"  -> RadioReference Talkgroups ingested: {len(tg_rows):,} rows | APK az_talkgroups.tsv.gz ({out_tg.stat().st_size:,} B)")

    conn.commit()

    out_rf = assets_dir / "reference" / "az_fcc_rf.tsv.gz"
    with gzip.open(out_rf, "wt", encoding="utf-8", compresslevel=9) as gz:
        gz.write("# freq_hz\tcls\tagency\tmode\tcallsign\tlicensee\tlat\tlon\n")
        for hz in sorted(freq_summary.keys()):
            _, cls, agency, mode, callsign, licensee, lat, lon = freq_summary[hz]
            lat_s = f"{lat:.4f}" if lat is not None else ""
            lon_s = f"{lon:.4f}" if lon is not None else ""
            gz.write(f"{hz}\t{cls}\t{agency}\t{mode}\t{callsign}\t{licensee}\t{lat_s}\t{lon_s}\n")

    print(
        f"  -> Hub ref_rf_az: {len(db_rows):,} rows | "
        f"APK az_fcc_rf.tsv.gz: {len(freq_summary):,} unique AZ+NIFOG channels ({out_rf.stat().st_size:,} B)"
    )


def build_ieee_and_ble_tables(raw_dir: Path, conn: sqlite3.Connection, assets_dir: Path):
    print("[4/5] Compiling IEEE MA-L (24-bit) / MA-M (28-bit) / MA-S (36-bit) & Bluetooth SIG Tables ...")
    cur = conn.cursor()
    cur.execute("""
        CREATE TABLE IF NOT EXISTS ref_mac_blocks (
            prefix_hex TEXT PRIMARY KEY,
            bit_len INTEGER NOT NULL,
            vendor TEXT NOT NULL,
            role TEXT NOT NULL,
            agency TEXT NOT NULL
        )
    """)
    cur.execute("""
        CREATE TABLE IF NOT EXISTS ref_ble_companies (
            company_id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            role TEXT NOT NULL
        )
    """)

    mac_rows = []
    apk_mac_lines = ["# prefix_hex\tbit_len\trole\tagency\tvendor"]

    for fname, bits in (("ieee_oui.csv", 24), ("ieee_mam.csv", 28), ("ieee_oui36.csv", 36)):
        with open(raw_dir / fname, "r", encoding="utf-8-sig", errors="replace") as f:
            reader = csv.reader(f)
            next(reader, None)
            for r in reader:
                if len(r) < 3:
                    continue
                assign = re.sub(r"[^0-9A-Fa-f]", "", r[1]).upper()
                org = " ".join(r[2].split()).replace("\t", " ")
                if not assign or not org:
                    continue
                up_org = org.upper()
                role = ""
                agency = ""
                if "AXON NETWORKS" in up_org:
                    role = "CONSUMER_ISP"
                elif "COMPEX" in up_org:
                    role = "INFRA_BACKHAUL"
                else:
                    for kw, r_code, ag_code in TACTICAL_OUI_KEYWORDS:
                        if kw in up_org:
                            role = r_code
                            agency = ag_code
                            break
                mac_rows.append((assign, bits, org[:80], role, agency))
                # For mobile asset bundle: keep ALL 28-bit/36-bit under shared roots (70B3D5, 8C1F64, 0050C2) + ALL role-tagged blocks!
                if role or (bits in (28, 36) and assign.startswith(("70B3D5", "8C1F64", "0050C2", "F8B568", "C47C8D", "E8B470"))):
                    apk_mac_lines.append(f"{assign}\t{bits}\t{role or 'CIVIL_SUBBLOCK'}\t{agency}\t{org[:64]}")

    cur.executemany("INSERT OR REPLACE INTO ref_mac_blocks VALUES (?,?,?,?,?)", mac_rows)

    # Parse Bluetooth SIG `bt_company_identifiers.yaml`
    ble_rows = []
    bt_yaml = (raw_dir / "bt_company_identifiers.yaml").read_text(encoding="utf-8", errors="replace")
    for m in re.finditer(r"-\s+value:\s+0x([0-9A-Fa-f]{4})\s*\r?\n\s+name:\s+['\"]?([^'\"\r\n]+)", bt_yaml):
        cid = int(m.group(1), 16)
        nm = m.group(2).strip().replace("\t", " ")
        up_nm = nm.upper()
        role = ""
        for kw, r_code, _ in TACTICAL_OUI_KEYWORDS:
            if kw in up_nm:
                role = r_code
                break
        ble_rows.append((cid, nm[:70], role))

    cur.executemany("INSERT OR REPLACE INTO ref_ble_companies VALUES (?,?,?)", ble_rows)
    conn.commit()

    out_ieee = assets_dir / "reference" / "ieee_subblocks.tsv.gz"
    with gzip.open(out_ieee, "wt", encoding="utf-8", compresslevel=9) as gz:
        for ln in apk_mac_lines:
            gz.write(ln + "\n")

    print(
        f"  -> Hub ref_mac_blocks: {len(mac_rows):,} rows + ref_ble_companies: {len(ble_rows):,} rows | "
        f"APK ieee_subblocks.tsv.gz: {len(apk_mac_lines)-1:,} rows ({out_ieee.stat().st_size:,} B)"
    )


def build_cellular_tables(raw_dir: Path, phone_cells_json: Path, conn: sqlite3.Connection, assets_dir: Path):
    print("[5/5] Compiling Arizona Cellular Ground-Truth Tables (FCC l_cell.zip + Surveyed Cells + Optional Drops) ...")
    cur = conn.cursor()
    cur.execute("""
        CREATE TABLE IF NOT EXISTS ref_cells_az (
            id_key TEXT PRIMARY KEY,
            rat TEXT NOT NULL,
            mcc TEXT NOT NULL,
            mnc TEXT NOT NULL,
            area INTEGER NOT NULL,
            cell_id INTEGER NOT NULL,
            lat REAL,
            lon REAL,
            operator TEXT NOT NULL,
            source TEXT NOT NULL
        )
    """)
    cur.execute("""
        CREATE TABLE IF NOT EXISTS ref_fcc_cell_sites_az (
            callsign TEXT NOT NULL,
            loc_num TEXT NOT NULL,
            licensee TEXT NOT NULL,
            county TEXT NOT NULL,
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            PRIMARY KEY (callsign, loc_num)
        )
    """)

    # 5a. FCC l_cell.zip AZ licensed cellular base-station towers
    fcc_sites = []
    cell_zip = raw_dir / "fcc_l_cell.zip"
    if cell_zip.exists():
        with zipfile.ZipFile(cell_zip) as zf:
            hd_active = {}
            with zf.open("HD.dat") as f:
                for line in io.TextIOWrapper(f, encoding="latin1", errors="replace"):
                    p = line.split("|")
                    if len(p) > 6 and p[5].strip() == "A":
                        hd_active[p[1].strip()] = p[4].strip()
            en_names = {}
            with zf.open("EN.dat") as f:
                for line in io.TextIOWrapper(f, encoding="latin1", errors="replace"):
                    p = line.split("|")
                    if len(p) > 7 and p[1].strip() in hd_active and p[7].strip() and (p[5].strip() == "L" or p[1].strip() not in en_names):
                        en_names[p[1].strip()] = p[7].strip()
            with zf.open("LO.dat") as f:
                for line in io.TextIOWrapper(f, encoding="latin1", errors="replace"):
                    p = line.split("|")
                    if len(p) > 26 and p[1].strip() in hd_active and p[14].strip() == "AZ":
                        lat = dms_to_decimal(p[19], p[20], p[21], p[22])
                        lon = dms_to_decimal(p[23], p[24], p[25], p[26])
                        if lat is not None and lon is not None:
                            usi = p[1].strip()
                            fcc_sites.append((hd_active[usi], p[8].strip(), en_names.get(usi, "")[:60], p[13].strip().upper(), lat, lon))
    cur.executemany("INSERT OR REPLACE INTO ref_fcc_cell_sites_az VALUES (?,?,?,?,?,?)", fcc_sites)

    # 5b. Surveyed Southern AZ LTE/NR cells + Pre-extracted OpenCelliD (310-314, 334) & MLS (`az_sonora_cells_merged.tsv.gz`)
    rat_idx_map = {"GSM": 0, "CDMA": 1, "WCDMA": 2, "UMTS": 2, "TDSCDMA": 3, "LTE": 4, "NR": 5}
    rat_names = ["GSM", "CDMA", "WCDMA", "TDSCDMA", "LTE", "NR"]
    # Key: packed_u63 -> (rat_idx, mcc_i, mnc_i, cid, area, lat, lon, op, src, is_survey)
    packed_cells = {}

    def pack_cell_key(r_idx: int, mcc_i: int, mnc_i: int, cid_i: int) -> int:
        return ((r_idx & 0x7) << 58) | ((mcc_i & 0x3FF) << 48) | ((mnc_i & 0x3FF) << 38) | (cid_i & 0x3FFFFFFFFF)

    merged_tsv = raw_dir / "az_sonora_cells_merged.tsv.gz"
    if merged_tsv.exists():
        with gzip.open(merged_tsv, "rt", encoding="utf-8", errors="replace") as f:
            for line in f:
                if not line or line[0] == "#":
                    continue
                p = line.rstrip("\r\n").split("\t")
                if len(p) < 7:
                    continue
                r_idx = rat_idx_map.get(p[0].strip().upper())
                if r_idx is None:
                    continue
                try:
                    mcc_i = int(p[1])
                    mnc_i = int(p[2])
                    area = int(p[3])
                    cid = int(p[4])
                    lat = float(p[5])
                    lon = float(p[6])
                    src = p[10] if len(p) > 10 else "OCID_MLS"
                except ValueError:
                    continue
                pk = pack_cell_key(r_idx, mcc_i, mnc_i, cid)
                packed_cells[pk] = (r_idx, mcc_i, mnc_i, cid, area, lat, lon, "", src, 0)

    if phone_cells_json.exists():
        items = json.loads(phone_cells_json.read_text(encoding="utf-8"))
        for c in items:
            rat = (c.get("rat") or "").strip().upper()
            r_idx = rat_idx_map.get(rat)
            mcc = (c.get("mcc") or "").strip()
            mnc = (c.get("mnc") or "").strip()
            area = c.get("area")
            cid = c.get("cellId")
            if r_idx is None or not mcc.isdigit() or not mnc.isdigit() or not area or not cid or area in (0, 65534, 65535) or cid <= 0:
                continue
            mcc_i = int(mcc)
            mnc_i = int(mnc)
            pk = pack_cell_key(r_idx, mcc_i, mnc_i, int(cid))
            lat = c.get("bestLat")
            lon = c.get("bestLon")
            op = (c.get("operator") or "").replace("\t", " ")
            # Preserve MLS/OCID coordinates if field survey didn't have a GPS fix
            prev = packed_cells.get(pk)
            if lat is None and prev is not None:
                lat, lon = prev[5], prev[6]
            packed_cells[pk] = (r_idx, mcc_i, mnc_i, int(cid), int(area), lat, lon, op, "FIELD_SURVEY", 1)

    db_cell_rows = []
    sorted_pks = sorted(packed_cells.keys())
    for pk in sorted_pks:
        r_idx, mcc_i, mnc_i, cid, area, lat, lon, op, src, _ = packed_cells[pk]
        r_name = rat_names[r_idx]
        id_key = f"{r_name} {mcc_i:03d}-{mnc_i} {cid}"
        db_cell_rows.append((id_key, r_name, f"{mcc_i:03d}", str(mnc_i), area, cid, lat, lon, op, src))

    cur.executemany("INSERT OR REPLACE INTO ref_cells_az VALUES (?,?,?,?,?,?,?,?,?,?)", db_cell_rows)
    conn.commit()

    old_cells_tsv = assets_dir / "reference" / "az_cells.tsv"
    if old_cells_tsv.exists():
        old_cells_tsv.unlink()
    out_cells = assets_dir / "reference" / "az_cells.tsv.gz"
    with gzip.open(out_cells, "wt", encoding="utf-8", compresslevel=9) as gz:
        gz.write("# rat_idx\tmcc\tmnc\tcell_id\tarea\tlat_e4\tlon_e4\tis_survey\n")
        for pk in sorted_pks:
            r_idx, mcc_i, mnc_i, cid, area, lat, lon, op, src, is_surv = packed_cells[pk]
            lat_e4 = int(round(lat * 10000)) if lat is not None else 0
            lon_e4 = int(round(lon * 10000)) if lon is not None else 0
            gz.write(f"{r_idx}\t{mcc_i}\t{mnc_i}\t{cid}\t{area}\t{lat_e4}\t{lon_e4}\t{is_surv}\n")

    print(
        f"  -> Hub ref_fcc_cell_sites_az: {len(fcc_sites):,} sites + ref_cells_az: {len(packed_cells):,} cells | "
        f"APK az_cells.tsv.gz: {len(packed_cells):,} cells ({out_cells.stat().st_size:,} B)"
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--raw-dir",
        default=r"C:\Users\Kylec\.gemini\antigravity\brain\8e41e183-a902-4216-9b55-1a522665c0a1\scratch\raw_datasets",
    )
    ap.add_argument(
        "--hub-sqlite",
        default=r"C:\Users\Kylec\.gemini\antigravity\brain\8e41e183-a902-4216-9b55-1a522665c0a1\scratch\kokoho_reference.sqlite3",
    )
    ap.add_argument(
        "--assets-dir",
        default=r"c:\projects\Kokoho\app\src\main\assets",
    )
    ap.add_argument(
        "--phone-cells",
        default=r"C:\Users\Kylec\.gemini\antigravity\brain\8e41e183-a902-4216-9b55-1a522665c0a1\phone_pull_1115\files\cell_towers.json",
    )
    args = ap.parse_args()

    raw_dir = Path(args.raw_dir)
    hub_db = Path(args.hub_sqlite)
    assets_dir = Path(args.assets_dir)
    if hub_db.exists():
        hub_db.unlink()

    conn = sqlite3.connect(str(hub_db))
    conn.execute("PRAGMA journal_mode = WAL")
    conn.execute("PRAGMA synchronous = NORMAL")

    build_aircraft_tables(raw_dir, conn, assets_dir, assets_dir / "aircraft" / "law_enforcement.tsv")
    build_airfield_tables(raw_dir, conn, assets_dir)
    build_fcc_and_nifog_tables(raw_dir, conn, assets_dir)
    build_ieee_and_ble_tables(raw_dir, conn, assets_dir)
    build_cellular_tables(raw_dir, Path(args.phone_cells), conn, assets_dir)

    conn.execute("ANALYZE")
    conn.execute("PRAGMA journal_mode = DELETE")
    conn.close()
    print(f"\nSUCCESS! Hub SQLite DB built: {hub_db} ({hub_db.stat().st_size:,} B)")


if __name__ == "__main__":
    main()
