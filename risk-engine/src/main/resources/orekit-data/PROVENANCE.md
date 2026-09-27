# Leap second table, provenance

`tai-utc.dat` is the TAI minus UTC table published by the US Naval Observatory, saved byte for byte. Orekit reads it to build its UTC time scale; it is the only physical data the risk engine loads.

| Field | Value |
| --- | --- |
| Source URL | https://maia.usno.navy.mil/ser7/tai-utc.dat |
| Captured (UTC) | 2026-09-27T21:03:41Z (server `Date` header) |
| Last-Modified | Thu, 18 Jun 2026 17:26:25 GMT |
| Size | 3321 bytes |
| SHA256 | `3524e1ae34d67e858873a89e59983bbc5bd100221da898e796c1b36036a310c3` |
| Last entry | 2017 JAN 1, TAI minus UTC = 37.0 s |

The file must be replaced when a new leap second is announced; a date after an unlisted leap second would be off by one second.

To check against the source:

```
curl -sS https://maia.usno.navy.mil/ser7/tai-utc.dat | sha256sum
```
