# Last Asylum

Un vrai jeu mobile inspiré de la pub « Last Asylum: Plague ». Il n'y a aucune pub et il tourne à 60/120 fps. Il est écrit en Kotlin Multiplatform et Compose Multiplatform pour Android, iOS et desktop.

Tu joues le docteur peste. Tu ramasses les malades dans la cour et tu les portes jusqu'aux chaudrons de potion ou aux lits. Tu récupères l'or qu'ils laissent et tu le dépenses sur les dalles pour ouvrir des chambres, embaucher des infirmières et des brancardiers, construire des tours d'archers et renforcer la palissade. Des vagues de rats arrivent régulièrement et, toutes les trois vagues, une bête vient défoncer la grille.

## Lancer le jeu

Il faut le JDK 17 ou plus récent et, pour Android, le SDK Android (`local.properties` ou `ANDROID_HOME`).

```bash
# Android : APK installé directement sur le téléphone branché (adb)
./gradlew :composeApp:installDebug
# ou un APK release optimisé (signé avec la clé debug)
./gradlew :composeApp:assembleRelease
adb install -r composeApp/build/outputs/apk/release/composeApp-release.apk

# Desktop (fenêtre format téléphone, ZQSD / WASD / flèches, F = compteur FPS)
./gradlew :composeApp:run

# iOS
brew install xcodegen
cd iosApp && xcodegen && open iosApp.xcodeproj
```

**Contrôles :** pose le doigt n'importe où et glisse, le joystick apparaît sous le doigt. Une tape sur l'avatar en haut à gauche affiche les FPS. La partie est sauvegardée toute seule.

## Fluidité

- Le jeu est dessiné sur un seul `Canvas`. La boucle est cadencée par `withFrameNanos` (vsync), et un état lu seulement dans la phase de dessin évite toute recomposition à chaque image.
- Le moteur (`engine/`) est du Kotlin pur. Il utilise des pools d'objets et n'alloue rien pendant une image, avec des sous-pas de 1/60 s.
- Les personnages et le décor sont des sprites précalculés en 3D, rangés dans deux atlas de 2048 px. Une image coûte donc quelques centaines de `drawImage`.
- Sur Android, le jeu demande le mode d'affichage le plus rapide (90/120 Hz). Sur iOS, `CADisableMinimumFrameDurationOnPhone` débloque le 120 Hz ProMotion.

## Les modèles 3D

Les sprites viennent de `tools/sprites/render_sprites.py`, un script Blender (le module Python `bpy`, rendu Cycles). Il reprend les packs CC0 de KayKit, les recolore et les habille : chapeau, masque à bec, lunettes, tablier, etc. Ensuite il les rend en 8 directions avec leurs animations et range le tout dans les atlas. Il génère aussi `render/SpriteData.kt` et `ui/AtlasPages.kt`.

```bash
python3 -m venv .venv && .venv/bin/pip install bpy pillow numpy
.venv/bin/python tools/sprites/render_sprites.py .          # ~5 min sur CPU
.venv/bin/python tools/sprites/render_sprites.py . --quick  # aperçu rapide
```

## Test sans écran

`verify/` est un petit projet JVM. Il compile le moteur et le vrai renderer, puis fait jouer un bot pendant 11 minutes de jeu. Il vérifie que l'économie progresse et que la sauvegarde marche, et il écrit des captures d'écran avec Java2D.

```bash
cd verify && gradle run --args="build/shots 11"
```

## Crédits

Les modèles 3D d'origine sont de [KayKit](https://kaylousberg.com/game-assets) par Kay Lousberg, sous licence CC0 : Character Pack Adventures, Dungeon Remastered, Halloween Bits et Furniture Bits.
