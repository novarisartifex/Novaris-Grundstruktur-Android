# Novaris Grundstruktur — Android-Migration

## Verifizierte Quelle
- Google Drive: `Novaris_Grundstruktur_v1_3_Standalone.html`
- Typ: `text/html`, 113.504.062 Bytes.
- Ein einzelnes HTML-Dokument mit CSS, JavaScript und eingebetteten Bilddaten.
- Originaltitel: `Novaris · World, Character & Scene Compendium`.
- Enthält Personen, Asterions Bezirke und Szenen; Navigation und Filter werden durch eingebettetes JavaScript gesteuert.
- Kein nachgewiesener Einsatz von `fetch()`, `localStorage` oder `indexedDB` in dieser Version.

## Architekturentscheidung
Die bestehende HTML-Oberfläche soll offline in einer Android-WebView laufen. Das ist ein nativer Android-APK-Container mit unveränderter HTML-Oberfläche, keine vollständige Neuentwicklung in Android-Views.

**Blocker:** Die Quelldatei überschreitet GitHubs 100-MB-Dateigrenze. Ein gzip-Paket hat rund 85,9 MB und könnte als binäres Release-Asset eingebunden werden, liegt aber noch nicht im GitHub-Repository. Die Quellbilder müssen ohne Qualitätsverlust übernommen werden.

Die APK darf nicht als funktional vollständig bezeichnet werden, solange die Originaldatei nicht in den Build gelangt.

## Update-Sicherheit
- App-Code und HTML/JS müssen von reinen Daten-/Medienpaketen getrennt werden.
- Remote-Pakete nur über HTTPS, mit SHA-256, Version, Größenlimit, Formatprüfung, atomarem Austausch, Backup und Rollback.
- Keine willkürlichen Remote-Skripte über den Datenkanal laden.
- Eine konsistente App-Signatur ist für installierbare Upgrades notwendig; Debug-Signatur ist nur für Test-APKs geeignet.

## Nächster Schritt
Originaldatei als zugängliches, unverändertes Build-Asset bereitstellen (bevorzugt privates GitHub-Release-Asset oder aufgeteiltes Git-LFS-/Asset-Paket), danach WebView-Projekt und GitHub Actions für einen echten APK-Build erstellen.
