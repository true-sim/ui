# Bundled emoji font

An unmodified file from the official Noto Emoji repository's latest release, under the SIL Open Font License 1.1
(`OFL-NotoColorEmoji.txt`, the release's `2D/fonts/LICENSE`). Downloaded 2026-11-03.

| File | Font | Source | Digests |
|---|---|---|---|
| `NotoColorEmoji.ttf` | Noto Color Emoji (CBDT/CBLC colour bitmaps, one 109 px strike), Unicode 18.0 | https://github.com/googlefonts/noto-emoji/releases/tag/v2026-09-24-unicode18_0, `2D/fonts/NotoColorEmoji.ttf` at that tag | git blob `e605743d05f261634f0b3f68a439d9b557d8f335` (as published by the tag's tree; verified); SHA-256 `15671215ab769fdc7162a045d56fd7d7e477c51b04e6b3c761d914d8fdd6cc44` (no SHA-256 published) |
| `OFL-NotoColorEmoji.txt` | the licence | same tag, `2D/fonts/LICENSE` | git blob `d952d62c065f3f35fb83a173496e90b21525aef3` (verified); SHA-256 `6a73f9541c2de74158c0e7cf6b0a58ef774f5a780bf191f2d7ec9cc53efe2bf2` |

The release publishes no font assets (only a 3D emoji archive); the font is the file at the release's tag, fetched
with `gh api repos/googlefonts/noto-emoji/contents/2D/fonts/NotoColorEmoji.ttf?ref=v2026-09-24-unicode18_0`.
The CBDT format is chosen over the release's COLRv1 font (`Noto-COLRv1.ttf`): LWJGL 3.4.3's FreeType renders CBDT in
colour (`FT_LOAD_COLOR`, BGRA bitmaps), while COLRv1 would need FreeType's paint API walked and rasterised by us.
