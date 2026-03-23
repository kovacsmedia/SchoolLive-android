# SchoolLive Android Player

Natív Android Snapcast kliens, a SchoolLive iskolai kommunikációs rendszer részese.  
Régi (API 21+, Android 5.0+) tableteket von be lejátszóként – **második élet az intézményi e-hulladéknak**.

## Funkciók

- 🔊 **Natív Snapcast v2 TCP kliens** – szinkronizált audio lejátszás AudioTrack-en
- 🔔 **SyncEngine WebSocket** – PREPARE/PLAY/STOP/MESSAGE valós idejű fogadás
- 📺 **Fullscreen sötét UI** – óra, következő csengetés, TTS overlay, rádió overlay
- 📋 **Provisioning flow** – AL-XXXX azonosítóval aktiválható az admin felületen
- 🔋 **Foreground Service** – Doze mode ellen védett, folyamatos háttér lejátszás
- 📡 **Beacon** – 30 másodpercenként jelenti az állapotot a szervernek
- 🔄 **OTA frissítés** – GitHub Releases API-ról automatikus APK frissítés

## Minimális követelmények

| | |
|---|---|
| Android verzió | 5.0 Lollipop (API 21) |
| RAM | 512 MB (ajánlott: 1 GB) |
| Hálózat | WiFi (2.4 GHz elegendő) |
| Képernyő | Bármilyen méret, landscape mód |

## Fejlesztés

### Előfeltételek
- Android Studio Hedgehog (2023.1.1) vagy újabb
- JDK 17
- Android SDK 34

### Build
```bash
./gradlew assembleDebug
```

### Telepítés fizikai eszközre
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Tömeges telepítés (több eszköz egyszerre)
```bash
for device in $(adb devices | grep -v List | awk '{print $1}'); do
    adb -s $device install schoollive-player.apk
done
```

## Architektúra

```
MainActivity          – fullscreen UI, óra, overlay-ek
ProvisioningActivity  – első indítás, szerver URL, AL-XXXX aktiválás
PlayerService         – foreground service, beacon loop, bell refresh
  ├── SnapcastClient  – natív TCP Snapcast v2, AudioTrack ring buffer
  └── SyncClient      – OkHttp WebSocket, PREPARE/PLAY/STOP/MESSAGE
ApiClient             – Retrofit HTTP, provisioning/snap-port/bells
BellManager           – helyi csengetési rend, SharedPreferences cache
DeviceIdUtil          – ANDROID_ID → SHA256 hardwareId, AL-XXXX shortId
```

## A rendszer többi komponensével való kapcsolat

Az Android player a backend szempontjából ugyanolyan eszköz, mint az ESP32 vagy a Windows/Linux player:
- Azonos beacon/poll/ACK protokoll
- Azonos SyncEngine WebSocket események
- Ugyanazon Snapcast TCP stream

A backend semmilyen Android-specifikus kezelést nem igényel.

## Licenc

MIT License – lásd [LICENSE](LICENSE)
