# Hide Invis Tab (Fabric, Minecraft 26.2) - nur Server

Unsichtbare Spieler (Trank der Unsichtbarkeit, `/effect give ... invisibility`) verschwinden aus der
Tab-Liste aller anderen Spieler: Name und Skin-Kopf sind dann nicht mehr zu sehen. Endet die
Unsichtbarkeit, erscheint der Eintrag wieder. Spieler im Zuschauermodus sind ausgenommen.

Nur auf dem Server noetig (mit Fabric API). Laeuft zusammen mit "Hide Invis Msgs", das die Todes-
und Kill-Nachrichten unsichtbarer Spieler versteckt.

Tarnung (optional): Mit einem signierten Skin (Textur-Wert + Signatur, z.B. von mineskin.org) zeigt die
Tab-Liste statt des Spielers "???" mit schwarzem Skin und weissem Fragezeichen (passt auch zu Player-Head-
Chat-Koepfen). Die Werte kommen in `config/hideinvistab.json` (`texture_value`, `texture_signature`,
`fake_name`). Ohne Werte wird der Eintrag wie bisher komplett entfernt. Der Skin liegt als
`schwarz-fragezeichen-skin.png` bei.

Bauen: `gradle build` mit JDK 25, Ergebnis `build/libs/hideinvistab-1.0.0.jar`.
