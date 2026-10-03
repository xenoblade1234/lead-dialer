# Lead Dialer

Android-App, die eine Lead-Liste automatisch nacheinander anruft.

## Ablauf

1. CSV importieren (Menü oben rechts). Nötig ist eine Spalte `Telefon`, optional `Name`, `Vorname`/`Nachname`, `Firma`, `Notiz`. Trennzeichen `;` oder `,`, Excel-Export funktioniert. Jeder Import wird eine eigene **Liste**; oben wählst du, welche Liste angezeigt und angerufen wird.
2. **Wählen starten**: Du wählst "Automatisch weiterwählen" oder "Nach jedem Anruf pausieren". Die App zeigt den Lead, zählt kurz runter und ruft an.
3. Nach dem Auflegen startet ein Timer (Standard 3 s, pausierbar). Ergebnis antippen ist optional (Nicht erreicht, Mailbox, Rückruf, Termin, Kein Interesse, Falsche Nummer); ohne Auswahl wird das Standard-Ergebnis aus den Einstellungen gespeichert (Standard: Mailbox). Danach wird sofort die nächste Nummer gewählt. Beim Tippen einer Notiz pausiert der Timer.
4. **CSV exportieren** speichert alle Leads mit Status, Versuchen und Notizen.

Wer wieder angerufen wird: `Neu`, `Rückruf` sowie `Nicht erreicht`/`Mailbox` bis zur eingestellten Max-Anzahl Versuche (Standard 3).

## Build

Jeder Push auf `main` baut per GitHub Actions eine signierte APK und legt sie unter **Releases** ab.
