# Mknov fixtures

Public metadata/index captured on 2026-10-17 from the anonymous official endpoints listed in docs/novels/MKNOV_NATIVE_SOURCE_20261017.md. No credentials, accounts, cookies or chapter-image files.

- catalog-10.json, latest.json, search.json, genre.json: recorded public catalogue metadata.
- details-96.json, details-37.json: recorded identity fields; story descriptions replaced with authored fixture text.
- index-96.json.gz: recorded 3218-entry index, deterministic gzip (mtime=0). Stable server IDs, not invented chapters.
- chapter-96-first.json: recorded public chapter identity/access fields, with the entire story body replaced by authored test text.

MknovLiveAcceptanceTest is separately opt-in and makes four real requests for one work. Fixture success does not establish Android rendering or device performance.
