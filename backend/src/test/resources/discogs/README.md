# Discogs test fixtures

| File | Source | Covers |
|---|---|---|
| `release-1.json` | Real: `GET https://api.discogs.com/releases/1` (The Persuader – Stockholm, 2×12") | Multi-disc vinyl, sides A–D, single-letter positions |
| `release-249504.json` | Real: `GET https://api.discogs.com/releases/249504` (Rick Astley – Never Gonna Give You Up, 7") | Single, sides A and B |
| `edge-cases-vinyl.json` | Hand-written in Discogs' format | Headings, index tracks with sub-tracks, compilations ("Various"), artist joins and `(2)` suffixes, short tracks, missing and hour-long durations, unassigned positions |
| `cd-two-discs.json` | Hand-written in Discogs' format | Non-vinyl, `1-1` style disc positions |

Release data © Discogs contributors, used here only as test input.
