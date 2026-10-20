# Share Scan

- Every change to the app adds an entry at the top of `app/src/main/assets/changelog.json` (`Changelog.kt`): `"version": ""`, today's date (IST), and one Hindi line per change a user would notice. First give the previous top entry the version of the release built from it (the latest GitHub release). The About sheet lists these entries, and the release workflow publishes the top one as the GitHub release notes.
