# Bundled English dictionaries

Unmodified files of SCOWL's Hunspell dictionaries, release 2026.02.25 (SCOWL size 60, accents stripped), downloaded
2026-11-05 from the official SCOWL repository's GitHub release (the downloads wordlist.aspell.net links to):

| File | Source | SHA-256 |
|---|---|---|
| `en_US.aff`, `en_US.dic`, `README_en_US.txt` | https://github.com/en-wl/wordlist/releases/download/rel-2026.02.25/hunspell-en_US-2026.02.25.zip (zip SHA-256 `ac8e73310e951d88c52c2cf2ba54ceaca34f8486a81630ac8a75dc5f931179f9`) | `526e1dc32d1c0e8806dd72809c017b5546f2104b08b9424a4c47311aca526cca` (aff), `ab012ae86ce4b46b60db5b150869288de9c4a87bc747911764e582125b1b961e` (dic), `d63c711523fc9504fb71e1278489c6d7bb8cafcba75158c107cd17844955470f` (README) |
| `en_GB-ise.aff`, `en_GB-ise.dic`, `README_en_GB-ise.txt` | https://github.com/en-wl/wordlist/releases/download/rel-2026.02.25/hunspell-en_GB-ise-2026.02.25.zip (zip SHA-256 `d6fbb91ae7824c52fb02f74d7bc2cd9092f130faec60f42326a59437fa7247a3`) | `e5478627cc7413a2874134b518c9a3dc667a8886414c72ed47eea65185bd3a88` (aff), `4fdb00d235ac2f0634f455f757ba15036261f1557e1b693ac264ee42dbd7c7eb` (dic), `72827bd23b623a253f7928657d68a7b020eea3c7ab8bdfbb1e61323bc8b010c4` (README) |

## Licence

The licence is in each `README_*.txt` (section "COPYRIGHT, SOURCES, and CREDITS"), kept with the files as it asks:

- The word lists: SCOWL's licence, copyright 2000-2026 Kevin Atkinson, permission to use, copy, modify, distribute
  and sell without fee provided the copyright notice and the permission notice appear in all copies and in supporting
  documentation (MIT-like; SCOWL's sources are mostly public domain, 12dicts and ENABLE2K).
- The affix files: Geoff Kuenning's Ispell BSD licence (copyright 1993; clause 4 removed with his permission):
  redistributions keep the notice, the conditions and the disclaimer; modifications are marked (these are unmodified).

Both are permissive. A game shipping this module ships these README files with it (they are in the jar) and repeats
the notices in its credits.
