# Third-party notices

Kamerad itself is released under the MIT License (see `LICENSE`). It builds on:

- **karoo-ext-template** (https://github.com/hammerheadnav/karoo-ext-template, Apache-2.0):
  the project scaffolding (Gradle setup, manifest and extension skeleton) was started from this template.
  License text: `licenses/Apache-2.0-karoo-ext-template.txt`.
- **karoo-ext** (https://github.com/hammerheadnav/karoo-ext, Apache-2.0): the Karoo extension library,
  included unmodified in `libs/maven`. License text: `libs/maven/io/hammerhead/karoo-ext/LICENSE`.
- **Open GoPro** (https://github.com/gopro/OpenGoPro): GoPro's documentation and example code for the camera's
  Bluetooth protocol guided Kamerad's own implementation (`gopro/`). It is written for Kamerad and includes no
  Open GoPro source files; see that project for its license terms.
- Android, Jetpack Compose, Jetpack Glance, Kotlin and kotlinx.coroutines, all Apache-2.0, are downloaded
  by Gradle at build time and are not part of this repository.

"GoPro" and "HERO" are trademarks of GoPro, Inc. Kamerad is not affiliated with or endorsed by GoPro or Hammerhead.
