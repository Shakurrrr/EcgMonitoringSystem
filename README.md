# ECG Monitoring System

A production‑ready Android app for **single‑lead ECG acquisition, visualization, and export**, built with **Kotlin** + **Jetpack Compose**. EMS renders a clinically familiar ECG strip (25 mm/s paper speed, configurable mm/mV gain), supports **BLE streaming** from hardware, offers a **demo generator** for development, and exports **CSV/PDF/JSON** with derived metrics (HR, PR, QRS, QT) and feature markers (P/R/T).

---

## Table of contents

* [Highlights](#highlights)
* [Architecture](#architecture)
* [Screens & UX](#screens--ux)
* [Data flow](#data-flow)
* [ECG rendering](#ecg-rendering)
* [Derived metrics & markers](#derived-metrics--markers)
* [Exports](#exports)
* [Build & run](#build--run)
* [Hardware integration](#hardware-integration)
* [Configuration](#configuration)
* [Privacy & security](#privacy--security)
* [QA checklist](#qa-checklist)
* [Roadmap](#roadmap)
* [Troubleshooting](#troubleshooting)
* [Branding](#branding)
* [License](#license)

---

## Highlights

* **True ECG look & feel**
  Paper‑style grid at **25 mm/s** with **configurable gain** (default 10–20 mm/mV, range 4–40). DC‑centered rolling window for a stable, midline trace.

* **Demo & device parity**
  A physiologic P–QRS–T signal generator matches the rendering path used for real hardware. Replace the input stream and you get the same strip.

* **BLE lifecycle**
  Connect/Disconnect, Connecting/Connected/Error states surfaced via a sealed `BleUiState`. Easy to plug in a GATT client.

* **Analytics**
  Window‑level **HR (bpm), PR/QRS/QT (ms)** plus **P, R, T** indices. Lightweight classification helper (e.g., *Inconclusive: Low HR*).

* **One‑tap export**

  * **CSV**: header metadata + `sample_index,time_ms,mV`
  * **PDF**: wrapped header with HR/PR/QRS/QT, Classification, Gain, Speed; clinical grid and strip
  * **JSON** (Fitbit‑like): sampling/scaling, counts & mV arrays, metrics, markers, device meta

---

## Architecture

```
com.example.ecgmonitoringsystem/
  ├─ ble/                // BLEUiState, manager (pluggable)
  ├─ data/               // DemoEcgSource (CoroutineScope-driven)
  ├─ domain/model/       // EcgFrame, EcgAnnotator (markers + metrics)
  ├─ export/             // EcgExport (CSV/PDF/JSON)
  ├─ ui/
  │   ├─ widgets/        // EcgCanvas (paper + waveform)
  │   ├─ MainViewModel   // state: demo/ble/gain/recording/trace
  │   └─ MainActivity    // screen shell, SAF share
  └─ ...
```

**State flow:** Source (BLE or Demo) → ViewModel ring buffer (mV) → DC centering → Annotator → `EcgCanvas` + Exporters.

---

## Screens & UX

* **Strip view:** 10‑second window (configurable), grid, stable baseline, optional **P/R/T markers** overlay.
* **Top actions:** Connect BLE / Start–Stop Demo / Start–Stop Record.
* **Controls:** Markers toggle, **Gain − / +** (clamped).
* **Exports:** CSV, PNG (quick share), PDF (full header).
* **Status line:** HR / PR / QRS / QT from the current window.

**Screenshot placeholders** (replace with actual captures):

* `docs/screenshots/strip_demo.png`
* `docs/screenshots/export_pdf.png`
* `docs/screenshots/export_csv.png`

---

## Data flow

1. **Source**

   * **Demo:** `DemoEcgSource(viewModelScope)` yields `EcgFrame(seq, fs, ShortArray, hr, sqi)`
   * **Hardware:** BLE frames with accurate **`fs`** and **counts‑per‑mV**
2. **Normalization:** counts → mV with `countsPerMv`, ring buffer keeps `fs * seconds`
3. **Centering:** DC offset removed per visible window → stable midline
4. **Annotation:** `EcgAnnotator.annotateAndMeasure()` → P/QRS/T indices + HR/PR/QRS/QT
5. **Render/Export:** `EcgCanvas` draws; `EcgExport` writes CSV/PDF/JSON

---

## ECG rendering

* **Speed:** 25 mm/s (horizontal)
* **Gain:** *N* mm/mV → vertical scaling
* **Grid:** 1 mm minor, 5 mm major (classic red tint)
* **Stroke:** crisp polyline (AA)

If hardware outputs pre‑filtered data, keep filters consistent—render path is neutral by default.

---

## Derived metrics & markers

* **Metrics:** HR (bpm), PR, QRS, QT (ms) computed from the visible window.
* **Markers:** arrays of indices for **P**, **R (QRS)**, **T**, exported for analysis.
* **Classification:** `Normal sinus (demo)`, *Inconclusive: Low/High HR*, etc. Replace with your clinical pipeline when ready.

---

## Exports

### CSV

* Header lines (`# ...`) for metadata (quoted where needed so Excel doesn’t split cells)
* Markers on separate commented lines (space‑separated)
* Columns: `sample_index,time_ms,mV`

### PDF

* A4 portrait (~150 dpi)
* Wrapped header (no clipping): **HR/PR/QRS/QT, Gain, Speed, Result** + **local timestamp**
* Clinical grid + strip + footer legend (speed/gain)

### JSON (Fitbit‑style)

* `SamplingFrequencyHz`, `ScalingFactorCountsPermV`, `WaveformSamplesCounts`, `WaveformSamplesmV`, metrics, markers, device meta

---

## Build & run

**Prereqs**

* Android Studio (Giraffe or newer)
* Kotlin 1.9+, Compose BOM aligned with AGP
* minSdk 24 (configurable), target latest stable

**Steps**

1. Open the project in Android Studio
2. Build & Run on a device/emulator
3. In‑app:

   * Tap **Start Demo** to validate rendering and exports
   * Tap **Connect BLE** when your hardware is ready

---

## Hardware integration

Provide the following for **exact** strips:

* Accurate **sampling rate (`fs`)** per frame (e.g., 250/360/500 Hz)
* **Counts → mV** scaling (`countsPerMv`) validated with a **1 mV calibration pulse**
* Continuous frames (`ShortArray`) into the ViewModel ingestion

With those set, the on‑screen strip and exports reflect the true device signal.

---

## Configuration

* **Window length:** default 10 s (`windowSeconds` in VM)
* **Gain range:** 4–40 mm/mV (UI clamps)
* **Markers overlay:** runtime toggle
* **Time zone:** exports use **local time with offset** (ISO‑8601 `…+01:00`)

---

## Privacy & security

* No PII required; optional metadata fields are opt‑in
* Files saved via **Storage Access Framework**, shared by explicit user intent
* Add encryption/KeyStore if releasing to regulated markets

---

## QA checklist

* [ ] Midline centering validated across devices
* [ ] Paper speed: 25 major boxes = 1 s
* [ ] Gain sanity: 1 mV step = 10 mm at 10 mm/mV
* [ ] CSV opens cleanly in Excel/Numbers
* [ ] PDF header fully visible (no clipping)
* [ ] BLE reconnection & error states are surfaced

---

## Roadmap

* Multi‑lead preview (I, II, III)
* Optional filters (HP/LP/Notch) with presets
* Robust rhythm classification
* Session recording + playback screen
* Manual marker editor (P/R/T correction)

---

## Troubleshooting

* **`NoSuchMethodException` on ViewModel** → ensure no‑arg `MainViewModel` or supply a `Factory`
* **Ambiguous imports** (`EcgFrame`, `DemoEcgSource`) → remove duplicates; temp import aliases are acceptable
* **Flatlined / off‑center strip** → verify `countsPerMv`, `fs`, and DC centering
* **Excel splits header** → ensure you’re on the patched `saveCsv()` (quoted lines + space‑separated markers)

---

## Branding

* **App name (launcher):** **EMS**
* **Store title:** **EMS — ECG Monitoring System**
* **Bundle ID suggestion:** `com.asataura.ecgmonitor`

**Short description:** Real‑time single‑lead ECG with clinical strip, HR/PR/QRS/QT, and CSV/PDF/JSON export.

---

## License

*© Asataura Technology Services LTD. All rights reserved.*
(Replace with Apache‑2.0 or a proprietary license as required.)
