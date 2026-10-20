# DocScan 0.4.280 validation evidence

Status: debug/release APK builds PASS; forced core and app unit tests PASS; Android test Kotlin compilation PASS; overlay instrumentation INCONCLUSIVE (runner stalled before reporting any test result; stopped by Ctrl-C after bounded wait; no production stacktrace or ANR observed).

Test source was built in isolated validation copy `/tmp/docscan-dbq-0.4.280-validation/source`, based on `f333cf07cee76035d17ffa4b05835023e537e641` plus the selected 0.4.280 production patch and test-only additions. It is not a clean-HEAD equivalence claim for the original dirty tree.

## Commands and results

- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --rerun-tasks` — exit 0. JUnit XML totals: 28 suites, 243 tests, 0 failures, 0 errors, 0 skipped (core: 184 tests/19 suites; app unit: 59 tests/9 suites). AndroidTest Kotlin compile succeeded.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:assembleDebug :app:assembleRelease --no-daemon --stacktrace` — exit 0, 90 actionable tasks (49 executed, 41 up-to-date), build in 33s. Logs are in `apk-build.stdout.log` and `apk-build.stderr.log`.
- Overlay instrumented attempt on `emulator-5554` (AVD `DocScanArbitration_API_36_1`, Android 16/API 36, `sdk_gphone64_arm64`), filter `app.docscan.android.DocumentOverlayViewInstrumentedTest`: after APK installation the activity became top-resumed, but runner remained at 0/3 tests without emitting a test result. The invocation was stopped with Ctrl-C (exit 130); this is INCONCLUSIVE, not PASS or a product failure. Earlier retries failed before test methods due to AndroidTest helper Activity process/export configuration; details and logs are adjacent. No physical device was used.
- The baseline 0.4.279 native crop fixture run on the same AVD executed 3 tests, 0 failures/errors/skips. This does not count as a 0.4.280 native pass.

## APK checks

Package `app.docscan.scanner`; versionName `0.4.280`; versionCode `399`; release contains ABI `arm64-v8a`; debug contains `arm64-v8a, armeabi-v7a, x86, x86_64`. `apksigner verify --verbose` reports valid APK v2 signatures for both. Debug signer DN is `C=US, O=Android, CN=Android Debug`, certificate SHA-256 `05264cc16207662c151bd405fd645e4f1be11afe99f6fc2554f8672efc705488`. Release signer DN is `CN=DocScan Release, OU=Product`, certificate SHA-256 `af0ccc128ebb99b01280fe64613986c8475c45ab041e3030bf285293785625c7`. No signing secrets are included.

Archived to `/Users/restart/Documents/GitHub/DocScan/dist/`:

- `DocScan-debug-0.4.280.apk` SHA-256 `fab07e05768a9cf480ddfa7c0886c02555194c7061adc1628257e8eb6f1e638c`; alias `DocScan-debug.apk` has the same digest.
- `DocScan-release-0.4.280.apk` SHA-256 `f24feede330084caac57997c58289c91309f644aad2f388564ef70cee697a9ac`; alias `DocScan-release.apk` has the same digest.

## Source fingerprints

The source file SHA-256 manifest is `source-manifest.txt`. The principal production files were also verified byte-identical between the main 0.4.280 patch and isolated validation copy before tests/builds. No production files were edited by this validation agent. Unit and APK artifacts were generated in the isolated copy; archived APKs were copied to `dist/`. No commit was created.
