# SWPC 3-Day Forecast issues, provenance

These are five past issues of the NOAA SWPC 3-Day Forecast text product (https://services.swpc.noaa.gov/text/3-day-forecast.txt), kept because [docs/risk/space-weather-scales.md](../../../../../docs/risk/space-weather-scales.md) (source T2, Section 1.2) quotes them as evidence of how SWPC attaches G levels to Kp values in thirds. SWPC overwrites that file in place and does not archive it, so the copies come from the Internet Archive's Wayback Machine.

## How they were captured

Each file was fetched once, on 2026-09-27 between 23:12:03Z and 23:12:38Z, about 8 seconds apart, with:

```
curl -sS -A "SpaceFlux-fixture-capture (https://github.com/MustafaNazeer/SpaceFlux)" -D <name>.headers.txt -o <name> "https://web.archive.org/web/<timestamp>id_/https://services.swpc.noaa.gov/text/3-day-forecast.txt"
```

The `id_` form of the Wayback URL returns the archived response body as it was captured, without the Wayback toolbar or rewritten links. Every request returned HTTP 200 with `content-type: text/plain; charset=UTF-8`. Each body is ASCII text and is saved byte for byte. For every file, the body length equals the `x-archive-orig-content-length` header (SWPC's original `Content-Length`) and the length encoded in the first part of the original `ETag`, so the body is the size SWPC served.

The Wayback response headers are saved next to each body in `<name>.headers.txt`. They are verbatim except `x-nid` and `x-as`, whose values I replaced with `REDACTED` because they describe the network the capture was made from. Headers prefixed `x-archive-orig-` are SWPC's original response headers as recorded by the archive; `memento-datetime` is when the archive captured the file; `date` is when I fetched it.

## Files

| File | Wayback URL | Issue line in the body | SWPC Last-Modified (`x-archive-orig-last-modified`) | Archived (`memento-datetime`) | Fetched (`date`) | Size (bytes) | SHA256 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `3-day-forecast-20240416212305.txt` | https://web.archive.org/web/20240416212305id_/https://services.swpc.noaa.gov/text/3-day-forecast.txt | `:Issued: 2024 Apr 16 1230 UTC` | Tue, 16 Apr 2024 12:30:15 GMT | Tue, 16 Apr 2024 21:23:05 GMT | 2026-09-27T23:12:03Z | 1843 | `105888e7af1d68af3c9ee81fbcaee3d9e4d49efaa82ba8dc1e819e9d9c020b73` |
| `3-day-forecast-20240511011540.txt` | https://web.archive.org/web/20240511011540id_/https://services.swpc.noaa.gov/text/3-day-forecast.txt | `:Issued: 2024 May 11 0030 UTC` | Sat, 11 May 2024 00:30:10 GMT | Sat, 11 May 2024 01:15:40 GMT | 2026-09-27T23:12:12Z | 2140 | `748d8a4c19c0ad06c9e77fb6dad687fb6595c38bade6856c5b7263735395e6f4` |
| `3-day-forecast-20240514123452.txt` | https://web.archive.org/web/20240514123452id_/https://services.swpc.noaa.gov/text/3-day-forecast.txt | `:Issued: 2024 May 14 1230 UTC` | Tue, 14 May 2024 12:30:13 GMT | Tue, 14 May 2024 12:34:52 GMT | 2026-09-27T23:12:21Z | 2074 | `2d2a84e78d06fcd2383d104d0cdfdab3ea4294c2ba60023580ff80a6f3ea525a` |
| `3-day-forecast-20240520152328.txt` | https://web.archive.org/web/20240520152328id_/https://services.swpc.noaa.gov/text/3-day-forecast.txt | `:Issued: 2024 May 20 1230 UTC` | Mon, 20 May 2024 12:30:15 GMT | Mon, 20 May 2024 15:23:28 GMT | 2026-09-27T23:12:29Z | 2033 | `255e9191cb190cd6bfdf7a9c0809ed9954be50d6a80b0dc8acf316ad0ab0a075` |
| `3-day-forecast-20240919213019.txt` | https://web.archive.org/web/20240919213019id_/https://services.swpc.noaa.gov/text/3-day-forecast.txt | `:Issued: 2024 Sep 19 1240 UTC` | Thu, 19 Sep 2024 12:40:07 GMT | Thu, 19 Sep 2024 21:30:19 GMT | 2026-09-27T23:12:38Z | 1816 | `02d3a8b56643fff228b8299b64c9bd2af445939ded0515e02bbec8cbcdd8540f` |

Original URL for all five: https://services.swpc.noaa.gov/text/3-day-forecast.txt

Header file hashes (after the redaction above):

| File | SHA256 |
| --- | --- |
| `3-day-forecast-20240416212305.txt.headers.txt` | `b1d6a4ea17654414279edcce25cb23a5b6050a89ed11cb9b3c51e973923e1235` |
| `3-day-forecast-20240511011540.txt.headers.txt` | `8c33acb5a91d5f9aa1a0867f8df21b86e55bbe82721fd52e475a0a0f7b6b7dd4` |
| `3-day-forecast-20240514123452.txt.headers.txt` | `3cd5f6b78d8aac10cf307199774f1b4ea5e4d93b172f1a2c260311dcd70a0093` |
| `3-day-forecast-20240520152328.txt.headers.txt` | `cac3a716d890596fcb2ac72ce6e344053017642cd929168a10c14f213bc26744` |
| `3-day-forecast-20240919213019.txt.headers.txt` | `96b602020534604c5b114a981150e90fd1d665d33561e4546e8c48394151a0cd` |

Verify from this directory:

```
sha256sum 3-day-forecast-*
```

## Lines the scales note relies on

Quoted verbatim with their line numbers in each file. The summary sentences wrap onto a second line in the files.

`3-day-forecast-20240416212305.txt`:

```
9:The greatest expected 3 hr Kp for Apr 16-Apr 18 2024 is 4.67 (NOAA Scale
10:G1).
16:03-06UT       3.33         2.67         4.67 (G1)
```

`3-day-forecast-20240514123452.txt`:

```
9:The greatest expected 3 hr Kp for May 14-May 16 2024 is 5.67 (NOAA Scale
10:G2).
20:15-18UT       4.67 (G1)    2.33         2.67     
21:18-21UT       5.67 (G2)    3.00         3.00     
22:21-00UT       4.33         3.00         2.33     
```

`3-day-forecast-20240520152328.txt`:

```
9:The greatest expected 3 hr Kp for May 20-May 22 2024 is 6.67 (NOAA Scale
10:G3).
18:09-12UT       1.67         2.67         6.67 (G3)
```

`3-day-forecast-20240511011540.txt`:

```
15:00-03UT       8.00 (G4)    4.67 (G1)    3.33     
16:03-06UT       7.67 (G4)    5.67 (G2)    3.33     
17:06-09UT       7.00 (G3)    4.67 (G1)    3.67     
22:21-00UT       4.33         3.67         3.67     
```

`3-day-forecast-20240919213019.txt`:

```
7:The greatest observed 3 hr Kp over the past 24 hours was 4.67
8:(G1-Minor).
```

## Terms

The content is an SWPC product. SWPC is part of the National Weather Service, and every SWPC page links the NWS disclaimer at https://www.weather.gov/disclaimer, which says (read 2026-09-27): "The information on National Weather Service (NWS) Web pages are in the public domain, unless specifically noted otherwise, and may be used without charge for any lawful purpose so long as you do not: 1) claim it is your own (e.g., by claiming copyright for NWS information -- see below), 2) use it in a manner that implies an endorsement or affiliation with NOAA/NWS, or 3) modify its content and then present it as official government material." The bodies here are unmodified. The copies were retrieved through the Internet Archive, which served them as captured. I have not reviewed the Internet Archive's own terms of use for this retrieval.
