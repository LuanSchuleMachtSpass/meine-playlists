# Lite Player

Ein schlanker Android-Player, der Spotify fernsteuert: deine Playlists, Lieblingssongs und Empfehlungen aus deinen eigenen Hördaten – ohne Startseite und ohne Werbung. Inoffiziell, nicht mit Spotify verbunden.

## Was die App macht – und was nicht

- Zeigt ausschließlich Playlists, deren Besitzer du bist (mit Suche).
- Titel antippen → spielt die Playlist ab genau diesem Titel. Dazu „Abspielen“ und „Zufällig“.
- Unten eine Steuerleiste (Zurück / Play-Pause / Weiter).
- Sie nutzt nur die offizielle Spotify Web API. Endpunkte für Empfehlungen, Browse oder die Startseite werden nie abgefragt.

**Wichtig:** Spotify erlaubt Drittanbieter-Apps nicht, Audio selbst zu streamen. Die App ist eine **Fernbedienung**: Abgespielt wird über Spotify Connect, also die offizielle Spotify-App auf dem Handy (die im Hintergrund laufen muss), einen PC, einen Lautsprecher usw.
- Dafür ist **Spotify Premium** nötig. Mit Premium gibt es ohnehin keine Werbung.
- Die App umgeht keine Werbung und verändert die Spotify-App nicht.

## Einrichtung (einmalig, ca. 3 Minuten)

1. Öffne <https://developer.spotify.com/dashboard> und melde dich an.
2. Klicke auf **Create app**:
   - Name/Beschreibung: beliebig
   - **Redirect URI:** `http://127.0.0.1:8888/callback`
   - Bei den APIs **Web API** anhaken
3. Kopiere die **Client ID** (unter Settings).
4. Für Development-Mode-Apps (Stand 2026): Die App gehört deinem Account, und dieser braucht Premium. Willst du weitere Accounts zulassen, trage sie unter **User Management** ein (max. 5).
5. Installiere die APK, öffne sie, füge die Client-ID ein und melde dich an.

## APK bauen

### Variante A: Android Studio
Ordner `MeinePlaylists` öffnen → *Build → Build APK(s)*.

### Variante B: Kommandozeile (JDK 17 + Android SDK + Gradle 8.10)
```
gradle assembleRelease
```
Die APK liegt danach unter `app/build/outputs/apk/release/app-release.apk`. Sie ist mit dem Debug-Key signiert und kann direkt installiert werden.

### Variante C: GitHub Actions
Lade den Ordner in ein (privates) GitHub-Repo hoch. Der Workflow `.github/workflows/build.yml` baut die APK und hängt sie als Artefakt an den Lauf.

## Bekannte Grenzen
- „Kein Gerät gefunden“: Öffne die Spotify-App kurz einmal, damit sie als Connect-Gerät erscheint.
- Lokale Dateien in Playlists werden angezeigt, lassen sich aber nicht abspielen.
- Ohne Premium lässt sich die Liste ansehen, aber nichts abspielen (Fehler „Premium erforderlich“).
