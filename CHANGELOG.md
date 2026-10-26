# Changelog

All notable changes to Tides are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- iOS CI build: XcodeGen now generates a shared `iosApp` scheme with an Xcode 14-compatible project format, the job runs on `macos-15`, and build errors are surfaced as workflow annotations.

### Added
- This changelog, linked from the README.

### Changed
- CI uses `actions/checkout@v5` and `actions/setup-java@v5`.

## [1.0.0] - 2026-10-26

First release of Tides for Android and iOS, developed by XRMX Software Solutions.

### Added
- On-device tide prediction using harmonic analysis: astronomical arguments, nodal corrections and 35 tidal constituents.
- Calibration against reference sources, cached for offline use:
  - NOAA published harmonic constants (United States, no key).
  - Environment Agency tide gauges, fitted to about 4 weeks of observed levels (England, no key).
  - UK Admiralty UK Tidal API, fitted to 7 days of official high and low waters, which are shown as "Official" (UK & Ireland, free key).
  - WorldTides for custom locations worldwide (paid key).
- Least-squares harmonic fitter with outlier rejection and inference of constituents a short record cannot resolve (S2, N2, K2, P1, Q1).
- Day view: current height, rising/falling, next tide countdown, 24-hour chart with night shading and a "now" marker, and a high/low water table.
- Week view: 7-day chart and daily high/low waters; tap a day to open it.
- Sunrise, sunset, moon phase and a spring/neap tide hint.
- Station search, region filter (UK & Ireland, United States, Worldwide), nearest stations by GPS, favourites and custom locations.
- Export the day or week as a PNG to share or save, with the safety disclaimer included in the image.
- Tide alert notifications 30 minutes, 1 hour or 2 hours before high and/or low water.
- Safety disclaimer on first launch, as an in-app banner and in Settings, recommending the local lifeboat station, lifeguards or coastguard.
- Metres/feet units, plus light and dark themes.
- Headless screenshot renderer, tide engine unit tests and a GitHub Actions workflow building Android and iOS.

[Unreleased]: https://github.com/swooningfish/app-tides/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/swooningfish/app-tides/releases/tag/v1.0.0
