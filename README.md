# Lead Dialer

Android-App, die eine Lead-Liste automatisch nacheinander anruft.

## Ablauf

1. CSV oder Excel (.xlsx) importieren (Menü oben rechts). Die Telefonspalte wird über die Überschrift oder am Inhalt erkannt, optional `Name`, `Vorname`/`Nachname`, `Firma`, `Notiz`. Trennzeichen `;` oder `,`, Excel-Export funktioniert. Jeder Import wird eine eigene **Liste**; oben wählst du, welche Liste angezeigt und angerufen wird.
2. **Wählen starten**: Du wählst "Automatisch weiterwählen" oder "Nach jedem Anruf pausieren". Automatisch ruft sofort den ersten Lead an.
3. **Automatisch weiterwählen**: Nach dem Auflegen wird sofort der nächste Lead angerufen. Während des Gesprächs kannst du in die App wechseln, ein Ergebnis antippen (sonst wird das Standard-Ergebnis gespeichert, Standard: Mailbox) und "Nach diesem Anruf stoppen" wählen. **Nach jedem Anruf pausieren**: Ergebnis antippen, dann den nächsten Anruf selbst starten.
4. **CSV exportieren** speichert alle Leads mit Status, Versuchen und Notizen.

Reihenfolge: Leads am Griff ☰ ziehen (oder lange drücken), die Reihenfolge ist auch die Anruf-Reihenfolge. Zur Seite wischen löscht einen Lead (mit Rückgängig).

Wer wieder angerufen wird: `Neu`, `Rückruf` sowie `Nicht erreicht`/`Mailbox` bis zur eingestellten Max-Anzahl Versuche (Standard 3). Ist niemand mehr offen, steht auf dem Knopf **Noch eine Runde**: wahlweise nur die nicht Erreichten oder alle Leads der Liste nochmal.

## Build

Jeder Push auf `main` baut per GitHub Actions eine signierte APK und legt sie unter **Releases** ab.

## Geplante Anrufe

Reiter **Geplante Anrufe** oben im Hauptbildschirm. Anrufe zu festen Zeiten (einmalig oder an Wochentagen). Zur Uhrzeit erscheint ein Vollbild "Anruf an … in 10 s" (auch über der Bildschirmsperre) und wählt dann automatisch; Abbrechen oder sofort anrufen ist möglich.

- Eintragen direkt in der App oder über eine verknüpfte Datei (CSV oder Excel, z. B. in Google Drive) mit den Spalten `Name`, `Telefon`, `Tage` (z. B. `Mo, Mi`, `Mo-Fr`, `täglich`, leer = einmalig), `Uhrzeit` (z. B. `09:00`), `Datum` (für einmalige Anrufe, z. B. `24.10.2026`; in der App leer = heute). Die Datei wird bei jedem Öffnen der App und bei jedem geplanten Anruf neu gelesen.
- Einmalig erlauben: Anrufe, Benachrichtigungen, Vollbild-Benachrichtigungen, "Über anderen Apps einblenden", Akku-Optimierung aus. Der Bildschirm "Geplante Anrufe" zeigt, was fehlt.
- War das Handy zur Uhrzeit aus, wird der Anruf nicht nachgeholt, sondern als verpasst angezeigt.
