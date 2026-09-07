# LosslessCut Android — Chromebook Plus

Port Android (Kotlin) di **[LosslessCut](https://github.com/mifi/lossless-cut)** di
[mifi](https://github.com/mifi/) (licenza **GPL-2.0**, come l'originale),
ottimizzato per **Chromebook Plus** con operazioni lossless via
`MediaExtractor` → `MediaMuxer` HW (zero re-encode) e preview **ExoPlayer (Media3)**.

> Originale: `mifi/lossless-cut` — "the swiss army knife of lossless video/audio editing".
> Questo repo è un port indipendente per Android/ChromeOS, non affiliato all'autore originale.

## Funzioni portate 1:1

- **Taglio lossless** keep/remove (complemento), snap ai keyframe, input manuale tempi
- **Split** in un file per segmento, **merge/concat** multi-file, **reorder** segmenti
- **Extract** tracce video/audio/sottotitoli, **remux** contenitore (mp4/webm)
- **Snapshot** JPEG/PNG full-res, **export frame** ogni N secondi con timestamp nei nomi
- **Velocità** (scala PTS, no re-encode), **loop** X volte, **timelapse** (solo keyframe)
- **Rotazione** metadati (orientation hint), **timecode offset**, **metadati** (sidecar JSON)
- **Capitoli**: import/export CSV EDL, CUE, YouTube, progetto JSON; cut-by-chapters
- **Divide timeline** per lunghezza/numero parti; **detect silenzi/neri/scene**; waveform HW
- **Undo/redo**, **label + tag** segmenti, filtro per tag
- **Tracce**: inclusione/esclusione (disposition) in output, dati tecnici tutte le tracce
- **ffmpeg log**: ogni operazione genera il comando equivalente riusabile su PC
- **Shortcut tastiera** Chromebook: Spazio, ←/→ (±5s, Shift=±1 frame), I/O, S, E, Z/Y, N/M keyframe
- **Download HTTP** (es. HLS) + apertura via Share/SAF
- Impostazioni persistenti (formato, qualità snapshot, snap keyframe, keep/remove)

## Tabella parità (onesto)

| Originale | Android | Note |
|---|---|---|
| Lossless cut/keep/remove/reorder/split/merge | ✅ uguale | via MediaMuxer HW |
| Extract/remux/snapshot/frame export | ✅ uguale | remux mp4/webm (TS non scrivibile da MediaMuxer) |
| Chapters CSV/CUE/YouTube/progetto | ✅ uguale | DaVinci/FinalCut XML non supportati |
| Rotation/speed/loop/timelapse | ✅ uguale | speed = scala PTS; crop lossless richiede re-encode → non incluso |
| Black/silence/scene detect, waveform | ✅ uguale | versioni campionate leggere |
| ffmpeg command log, shortcut, multi-file | ✅ uguale | |
| Smart-cut (sperimentale upstream) | ≈ snap-keyframe | equivalente pratico |
| JS expression language, HTTP API/CLI, GPS map, cover-art attach, smart-crop | ❌ | non portabili senza engine JS/ffmpeg/GPS su Android |

## Build

[![Android CI (LosslessCut)](https://github.com/SancioPanza88/LosslessCut-Android/actions/workflows/build.yml/badge.svg)](https://github.com/SancioPanza88/LosslessCut-Android/actions/workflows/build.yml)

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

APK debug: `app/build/outputs/apk/debug/*.apk` (anche come artifact CI).
Requisiti: JDK 17, Android SDK 34, Gradle 8.7 (wrapper incluso).

## Licenza

GPL-2.0-only — vedi [LICENSE](LICENSE). Basato su `mifi/lossless-cut` (GPL-2.0).
