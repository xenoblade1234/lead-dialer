# Lead Dialer

Android-App, die eine Lead-Liste automatisch nacheinander anruft.

## Ablauf

1. CSV importieren (Menü oben rechts). Nötig ist eine Spalte `Telefon`, optional `Name`, `Vorname`/`Nachname`, `Firma`, `Notiz`. Trennzeichen `;` oder `,`, Excel-Export funktioniert.
2. **Wählen starten**: Die App zeigt den Lead, zählt kurz runter und ruft an.
3. Nach dem Auflegen zurück in die App: Ergebnis antippen (Nicht erreicht, Mailbox, Rückruf, Termin, Kein Interesse, Falsche Nummer), optional Notiz. Danach startet automatisch der nächste Anruf.
4. **CSV exportieren** speichert alle Leads mit Status, Versuchen und Notizen.

Wer wieder angerufen wird: `Neu`, `Rückruf` sowie `Nicht erreicht`/`Mailbox` bis zur eingestellten Max-Anzahl Versuche (Standard 3).

## Build

Jeder Push auf `main` baut per GitHub Actions eine signierte APK und legt sie unter **Releases** ab.
