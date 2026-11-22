# XEEV integration notes

This integration uses the verified hybrid XEEV/X3M flow for Live TV, Movies and Series.

## User flow

1. On Lumora's first-run chooser, select **XEEV APP CODE**.
2. Lumora registers a stable 32-character device ID and displays the XEEV app code.
3. Enter the code in XEEV Playlist Manager and assign the playlist.
4. Lumora checks pairing every 20 seconds for up to 10 minutes; the visible countdown updates every second.
5. When XEEV returns a valid 32-character line ID, Lumora saves the provider and imports its catalogue.

## Catalogue and playback

- **Live TV:** parse `#CAT` and `#CHAN` from `https://xeev.app/load/apk/{lineid}`. Use playlist field 7 verbatim for playback.
- **Movies:** fetch `get_vod_streams` from `http://x3m.li/player_api.php` with username/password derived from the first/second 16 characters of lineid and the stable `device_id`. Join API `stream_id` to playlist `#CHAN` field 1. Use the matched playlist field 7 for playback; API-generated movie URLs are not used.
- **Series:** fetch `get_series_categories`, `get_series`, and `get_series_info` from the same Player API with `device_id`. Episode playback uses `/series/{username}/{password}/{device_id}/{episode_id}.{extension}`.
- The shared provider model keeps XEEV-specific details behind the existing Lumora catalogue/player abstractions.

## Classification safety

The VOD-to-playlist join is required before treating the remaining `#CHAN` records as Live. If the API VOD list fails, contains duplicate IDs, or does not match every VOD ID to exactly one playlist record, the import fails safely instead of misclassifying movies as Live TV. Live Player API stream IDs are not used for matching because the investigation found no safe ID join and ambiguous name/EPG pairs.

## Pairing details

- Register: `POST https://xeev.app/apk/get/newappid`
- Pairing: `POST https://xeev.app/apk/get/settings/{app_code}`
- Playlist: `GET https://xeev.app/load/apk/{lineid}`
- Player API username/password: first and second 16-character halves of lineid.
- The same stable `device_id` is sent to Player API requests and appears in the tested Series episode URL.

## Validation status

The protocol mapping and playback patterns are based on the supplied XEEV/X3M findings document (21 November 2026). Source changes were inspected and packaged, but a local Gradle compile could not be run because this workspace cannot download the configured Gradle distribution. GitHub Actions remains the build verification step.
