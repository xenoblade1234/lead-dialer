# Lead Dialer

Android-App, die eine Lead-Liste automatisch nacheinander anruft.

## Ablauf

1. CSV oder Excel (.xlsx) importieren (Menü oben rechts). Die Telefonspalte wird über die Überschrift oder am Inhalt erkannt, optional `Name`, `Vorname`/`Nachname`, `Firma`, `Notiz`. Trennzeichen `;` oder `,`, Excel-Export funktioniert. Jeder Import wird eine eigene **Liste**; oben wählst du, welche Liste angezeigt und angerufen wird.
2. **Wählen starten**: Du wählst "Automatisch weiterwählen" oder "Nach jedem Anruf pausieren". Automatisch ruft sofort den ersten Lead an.
3. **Automatisch weiterwählen**: Nach dem Auflegen wird sofort der nächste Lead angerufen. Während des Gesprächs kannst du in die App wechseln, ein Ergebnis antippen (sonst wird das Standard-Ergebnis gespeichert, Standard: Mailbox) und "Nach diesem Anruf stoppen" wählen. **Nach jedem Anruf pausieren**: Ergebnis antippen, dann den nächsten Anruf selbst starten.
4. **CSV exportieren** speichert alle Leads mit Status, Versuchen und Notizen.

Wer wieder angerufen wird: `Neu`, `Rückruf` sowie `Nicht erreicht`/`Mailbox` bis zur eingestellten Max-Anzahl Versuche (Standard 3).

## Build

Jeder Push auf `main` baut per GitHub Actions eine signierte APK und legt sie unter **Releases** ab.
