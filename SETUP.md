# SchoolLive Android – Első indítás Android Studióban

## 1. Projekt megnyitása

1. Csomagold ki a `SchoolLive-android.zip` fájlt
2. Android Studio → **File → Open**
3. Válaszd a kicsomagolt `SchoolLive-android/` mappát
4. Kattints **OK** – Gradle sync automatikusan elindul

> **Fontos:** Ha a `gradle-wrapper.jar` hiányzik (a bináris fájlt nem csomagoljuk),
> Android Studio automatikusan letölti az első szinkronizáláskor.
> Ha mégis hibát kapsz: **File → Invalidate Caches → Invalidate and Restart**

## 2. GitHub URL beállítása az OTA-hoz

Az `OtaManager.kt` fájlban cseréld ki a placeholder-t:

```kotlin
// OtaManager.kt, 13. sor:
private const val GITHUB_API =
    "https://api.github.com/repos/[felhasználónév]/SchoolLive-android/releases/latest"
//                              ^^^^^^^^^^^^^^^^^ ← ide a GitHub felhasználóneved
```

## 3. Backend endpoint regisztrálása

Kövesd a `schoollive-backend-patch/INTEGRATION.md` utasításait:
- Prisma schema `platform` mező hozzáadása
- `devices.routes.android.ts` beillesztése

## 4. Debug build futtatása

### Emulátorra:
1. **AVD Manager** → Create Virtual Device → Pixel 4 → API 21 minimum
2. Run gomb (▶) → válaszd az emulátort

### Fizikai eszközre:
1. Eszközön: Beállítások → Fejlesztői lehetőségek → USB hibakeresés: BE
2. USB kábel dugása
3. Run gomb (▶) → válaszd az eszközt

### ADB parancssorból:
```bash
./gradlew installDebug
adb shell am start -n hu.schoollive.player/.MainActivity
```

## 5. Első használat az eszközön

1. Az app elindul a **Provisioning** képernyőn
2. Megjelenik az **AL-XXXX** azonosító
3. Szerver URL beírása: `https://api.schoollive.hu`
4. "Csatlakozás" gomb → az eszköz `pending` státuszba kerül
5. A webes admin felületen aktiváld az AL-XXXX azonosítójú eszközt
6. Az app automatikusan továbblép a lejátszó képernyőre

## 6. Release APK készítése

```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release-unsigned.apk
```

A GitHub Actions CI automatikusan buildelel és publikál minden `v*` tagre:
```bash
git tag v1.0.1
git push origin v1.0.1
```

## Hibaelhárítás

| Hiba | Megoldás |
|---|---|
| `Duplicate class kotlin.*` | `./gradlew clean` majd újra build |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | `adb uninstall hu.schoollive.player` |
| WS nem csatlakozik | Ellenőrizd, hogy a szerver URL HTTPS (nem HTTP) |
| Snapcast nem szól | Ellenőrizd a snap port-ot: `GET /devices/android/snap-port` |
| `BCrypt` class not found | Ellenőrizd, hogy a `jbcrypt:0.4` dependency szinkronizálva van |
