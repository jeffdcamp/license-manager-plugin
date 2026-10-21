# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.1.0] - 2026-10-21

### Changed

- Gradle 9.8.2
- Updated dependencies (Jackson 2.22.4, OkHttp 5.5.0, kotlinx.serialization 1.12.0, plugin-publish 2.2.1,
  versions 0.66.0)

### Security

- Jackson 2.22.4 pulls in jackson-databind 2.22.4, which fixes GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54,
  GHSA-q4xh-88c3-wmh7, GHSA-gx83-3vf8-gh7j and GHSA-wjgm-6hv5-3cvf

## [2.0.0] - 2026-08-08

### Added

- Support for the Gradle configuration cache. `createLicenseReports` no longer touches `Task.project` at
  execution time, so it works with `--configuration-cache` (required as of Gradle 10).
- `createLicenseReports` now declares its outputs, so it can be up-to-date instead of re-running every build.

### Changed

- Dependencies are now collected from the project's `runtimeClasspath` configuration (or
  `<variantName>RuntimeClasspath` when `variantName` is set) instead of forcing `implementation` to be
  resolvable. This fixes empty reports on Gradle 9, where forcing `canBeResolved` on `implementation` fails.
- `licenseManager` extension values are now lazy Gradle properties. The existing `foo = value` DSL syntax is
  unchanged, but reading these values from a build script now requires `.get()`.
- Removed the unused work-in-progress transitive dependency filter.
- Gradle 9.7.0
- Updated dependencies (plugin-publish 2.1.1, Jackson 2.22.1, JUnit 6.1.3, kotlinx.serialization 1.11.0,
  OkHttp 5.4.0)

## [1.6.0] - 2025-08-25

### Changed

- Gradle 9.0.0
- Fixed issue with invalidLicensesUrl not working if invalidLicenses was missing
- Improved message if failed to download invalidLicensesUrl

## [1.5.0] - 2025-04-18

### Changed

- Gradle 8.13

## [1.4.0] - 2024-03-16 

### Added

- Added Support for Gradle Publishing repo 

### Changed

- Updated dependency versions

Version 1.3.0 *(2023-06)*
-------------------------
* Group Licenses by https url then by name if no url exists
* Added support for shared Invalid Licenses list via invalidLicensesUrl
* Wrap list items in a <ul></ul> tag
* Separate Summary from Report in generation. This prevents accidental bundling of summary in app.

Version 1.2.1 *(2023-05)*
-------------------------
* Minor fixes

Version 1.2.0 *(2023-05)*
-------------------------
* Added support for adding custom license text (customLicenses)
* Added license-summary.html (show a grouping of licenses and what libraries are using it)
* Added ability to fail task if an invalid license is being used (invalidLicenses )
* Updated dependencies (Gradle 8.1.1)

Version 1.1.0 *(2021-11)*
-------------------------
* Prevent crash of plugin if a non-pom file is provided from project.configurations.resolvedConfiguration (skip file)
* Gradle 7.2
* Updated other dependencies

Version 1.0.0 *(2021-08)*
-------------------------
* Release 1.0.0

Version 1.0.0-alpha04 *(2021-05)*
-------------------------
* Removed outputDir (use outputDirs instead)

Version 1.0.0-alpha03 *(2021-05)*
-------------------------
* Improved support for clean build

Version 1.0.0-alpha02 *(2021-05)*
-------------------------
* Cleanup HTML
* Added outputDirs option

Version 1.0.0-alpha01 *(2021-05)*
-------------------------
* Added Html and Csv Reports

Version 0.0.2 *(2021-05)*
-------------------------
* Initial Release
