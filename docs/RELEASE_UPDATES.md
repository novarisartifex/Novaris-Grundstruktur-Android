# Novaris – Android Release und In-App-Updates

## Status
App: 0.2.0 (versionCode 200). Datenmanifest: Version 3.
Die GitHub-Actions-Debug-APK ist kein GitHub-Release. Der Updater prüft ausschließlich den neuesten veröffentlichten GitHub-Release mit dem Asset `novaris-release.apk` und SHA-256-Digest.
Ein Release darf erst nach erfolgreichem signiertem Build veröffentlicht werden.

## Einmalige Vorbereitung (am PC mit Java keytool; Schlüssel niemals ins GitHub-Repository hochladen)

1. Ein dauerhaftes Release-Keystore erzeugen, z. B.:
   `keytool -genkeypair -v -keystore novaris-release.jks -alias novaris -keyalg RSA -keysize 3072 -validity 10000`
2. Sichere, separate Backups von `novaris-release.jks`, Keystore-Passwort, Alias und Schlüsselpasswort anlegen.
3. Keystore als eine einzige Base64-Zeichenfolge kodieren (Linux: `base64 -w 0 novaris-release.jks`; macOS: `base64 < novaris-release.jks | tr -d '\\n'`).
4. GitHub Repository → Settings → Secrets and variables → Actions → New repository secret:
   - `NOVARIS_KEYSTORE_BASE64`: Base64-Zeichenfolge
   - `NOVARIS_STORE_PASSWORD`: Keystore-Passwort
   - `NOVARIS_KEY_ALIAS`: Schlüsselalias
   - `NOVARIS_KEY_PASSWORD`: Schlüsselpasswort
5. Die vier Secret-Namen dürfen dokumentiert werden; die Werte dürfen niemals in Commits, Chat, Screenshots oder Logs erscheinen.

## Signatur-Migration
Android akzeptiert ein In-place-Update nur bei gleicher applicationId und kompatibler Signatur sowie höherem versionCode. Die bisherige Debug-APK ist mit einem anderen, von GitHub Actions erzeugten Debug-Schlüssel signiert. Ein neues Release mit eigenem Release-Schlüssel kann diese Installation nicht überschreiben.

**Vor einer Migration unbedingt die Daten sichern.** Eine Deinstallation entfernt normalerweise die privaten lokalen App-Daten (einschließlich SQLite-Datenbank und Offline-Inhalten). Der aktuelle Stand besitzt keinen verifizierten vollständigen Backup-/Restore-Migrationspfad. Daher keine Deinstallation empfehlen, bevor dieser Pfad umgesetzt und getestet wurde.

Ab der ersten korrekt signierten Release-Installation müssen alle folgenden Releases mit **demselben** Keystore signiert werden. Der Schlüssel darf nicht gewechselt werden.

## Veröffentlichung
1. Build auf main grün prüfen.
2. Sicherstellen, dass `versionName` zum Tag passt (z. B. 0.2.0 → v0.2.0) und `versionCode` bei jedem neuen Release steigt.
3. Git-Tag am zu veröffentlichenden main-Commit erstellen und pushen (v0.2.0).
4. GitHub → Actions → Signed Android Release → Run workflow → tag v0.2.0.
5. Der Workflow prüft Secrets und Tag, baut die signierte Release-APK, verifiziert deren Signatur, erzeugt eine SHA-256-Datei und veröffentlicht ein GitHub Release.
6. Prüfen: `https://github.com/novarisartifex/Novaris-Grundstruktur-Android/releases`.
7. GitHub Releases API muss bei `novaris-release.apk` ein `digest: sha256:...` liefern. Ohne Digest lehnt der Updater das Update ab.

## App-Verhalten
- Button „Daten prüfen“ aktualisiert getrennt die Datenversion.
- Button „App-Update prüfen“ prüft GitHub Releases, vergleicht versionCode, lädt APK in den privaten Cache und verifiziert SHA-256.
- Die Installation wird nur nach positiver Prüfsumme und expliziter Android-Bestätigung angeboten.
- Bei unbekannten Installationsquellen öffnet Android zunächst die Berechtigungsseite; danach erneut auf „App-Update prüfen“ tippen.
- Download, Berechtigungen, Signaturkompatibilität und Datenerhalt müssen auf einem realen Gerät getestet werden.

## Offen
- Vier GitHub Actions Secrets durch Repository-Inhaber setzen.
- Release-Keystore erstellen und sicher archivieren.
- Daten-Backup/-Restore für die erste Debug→Release-Migration entwickeln.
- Aktuellen GitHub-Actions-Build prüfen.
- Signierten Release-Build, Veröffentlichung und Installation tatsächlich verifizieren.
