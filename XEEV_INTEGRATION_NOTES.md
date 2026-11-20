# XEEV integration notes

This first integration pass adds XEEV app-code pairing and imports the verified live-TV records into Lumora's existing IPTV catalogue.

## User flow

1. On Lumora's first-run chooser, select **XEEV APP CODE**.
2. Lumora registers a stable device ID and displays the XEEV app code.
3. Enter the code in XEEV Playlist Manager and assign the playlist.
4. Lumora checks pairing every 20 seconds for up to 10 minutes.
5. When XEEV returns a valid 32-character playlist ID, Lumora saves the provider, downloads the custom playlist, and imports live channels automatically.

## Supported records

- `#CAT`: category ID and category name.
- `#CHAN`: verified live-channel layout. The playback URL is taken directly from field 7 and is not reconstructed.
- Unknown record types are ignored.

## Deliberate limitations

Movie and series record layouts have not yet been verified in the XEEV protocol test harness, so this integration does not guess them. This first pass imports Live TV only. XMLTV/EPG download and catch-up/archive support are also not part of this pass.

## Validation

A parser unit test was added for the verified category/channel fields and preservation of the supplied playback URL. XML/resource checks and `git diff --check` passed in the editing workspace. A Gradle/Kotlin build could not be run there because the Gradle wrapper distribution was not cached and the workspace could not reach `services.gradle.org`.
