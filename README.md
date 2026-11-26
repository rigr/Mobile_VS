# Logic VS Bridge 0.2.0 – echter Browser-zu-Android-USB-MIDI-Test

Dies ist ein Android-Studio-Projekt für das Samsung Galaxy A7 (2018). Es enthält jetzt die vollständige hochgeladene Logic VS Web v6.5 HTML-Oberfläche und eine Web-MIDI-kompatible Brücke: Die MIDI-Ausgänge und -Eingänge der HTML-Seite werden über HTTP an Androids `MidiManager` weitergereicht. Der Browser auf Tablet/Laptop benötigt keine eigene Web-MIDI-Unterstützung und keine MIDI-Hardware.

## Was tatsächlich implementiert ist
- Android `MidiManager` erkennt MIDI-Geräte.
- Browser-MIDI-API-Fassade (`navigator.requestMIDIAccess`) für die bestehende v6.5-Seite.
- MIDI-Note-On/Off, Program Change, CC, MIDI Clock, MMC/MTC und SysEx werden als rohe MIDI-Bytes an den gewählten USB-MIDI-Ausgang durchgereicht. Ob der VS-1680 jeden konkreten Befehl unterstützt, muss separat am Gerät geprüft werden.
- MIDI-Eingangsdaten vom USB-Interface werden an die HTML-Seite verteilt.
- Geräte mit mehreren Ports werden als einzelne MIDI-Ports angezeigt.
- HTTP-Server Port 8080; keine SIM und kein Internet erforderlich, gleiches WLAN genügt.
- Foreground-Service und partieller Wake-Lock für Betrieb bei ausgeschaltetem Display.

## Testaufbau
1. ZIP entpacken und `LogicVSAndroid` in Android Studio öffnen.
2. Gradle synchronisieren (Android SDK 35; JDK 17).
3. Auf dem Galaxy A7 USB-Debugging aktivieren und App starten.
4. USB-MIDI-Interface über Micro-USB-OTG anschließen; Android-USB/MIDI-Berechtigung bestätigen.
5. In der App das Interface auswählen. In der Liste bedeutet `App→Gerät` der Ausgang, den Logic VS Web zum Senden nutzt.
6. A7 und Tablet/Laptop in dasselbe WLAN bringen; QR-Code scannen oder angezeigte IP-Adresse öffnen.
7. In Logic VS Web oben bei MIDI-Out das erkannte `... MIDI Out 1` auswählen. Für VS-Steuerung denselben passenden Ausgang zusätzlich bei „Roland VS“ wählen.
8. Erst mit einem MIDI-Synth oder MIDI-Monitor testen. Danach VS-1680-Kommandos vorsichtig prüfen.

## MIDI-Test
Oben neben MIDI-Out gibt es jetzt `Testton`: sendet eine C4-Note (Note-On und Note-Off) über den gewählten Ausgang. Zusätzlich kann eine MIDI-Spur ausgewählt und Play gestartet oder über die Tastaturtasten `A W S E D F T G Y H U J K` gespielt werden. Wähle zuerst den externen MIDI-Out. Interne Web-Sounds bleiben Standard, solange MIDI-Out auf „Intern“ steht.

## Wichtige technische Hinweise
- Android MIDI Port-Semantik: `MidiDevice.openInputPort()` ist der Port, in den die App sendet (zum Gerät); `openOutputPort()` liefert Daten vom Gerät an die App.
- Der Bridge-Server läuft aktuell unverschlüsselt und ohne Pairing. Nur in einem vertrauenswürdigen privaten WLAN verwenden.
- Echtzeit-Scheduling wird im Browser über `setTimeout` auf die Zeitstempel abgebildet; das ist nicht sample-genau. Für Transport, Clock und SysEx reicht es zum Funktionstest, aber Timing-Jitter ist möglich.
- USB-MIDI-Interface-Kompatibilität, Port-Auswahl und tatsächlicher VS-1680-Betrieb sind noch nicht auf dem Galaxy A7 getestet.
- Android 8.0 auf dem A7 ist innerhalb des Mindest-SDK 26. Die App ist auf SDK 35 targetiert; sollte das alte Gerät beim Installieren/Foreground-Service Probleme machen, passe ich Target-SDK und Service-Typ nach dem ersten Build an.
- Ein USB-Hub lädt das Telefon nur dann gleichzeitig, wenn er explizit OTG-Charging-Passthrough für Micro-USB unterstützt.

## Endpunkte
- `GET /api/status` – Status und erkannte Geräte/Portzahlen
- `POST /api/midi/select` – Gerät/Port wählen (optional; der Web-MIDI-Facade sendet Port-ID direkt)
- `POST /api/midi/send` – `{ "id": <deviceId>, "port": <inputPortIndex>, "hex": "90 3C 64" }`
- `GET /api/midi/poll` – empfangene MIDI-Bytes
