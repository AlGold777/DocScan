# 0.4.282 APK artifact verification

Source HEAD at build: `c7852beb2b8ef9fb174fcffe6f446cc7384290d8`.

Command:

```sh
./gradlew :app:assembleDebug :app:assembleRelease --no-parallel --max-workers=1
```

Result: BUILD SUCCESSFUL. Artifacts archived to `dist/` as both versioned files and current aliases:

| Variant | File(s) | Bytes | SHA-256 |
|---|---|---:|---|
| Debug | `DocScan-debug-0.4.282.apk`, `DocScan-debug.apk` | 208,494,046 | `c86d1d2f12fee0ac7a0efe1ac5b5aa7a373f63ae4e8cb9d036015b2b98cfc67d` |
| Release | `DocScan-release-0.4.282.apk`, `DocScan-release.apk` | 56,563,002 | `222d861376a2d4ba1b10cf7cb868aacb5d51fa77e93af088e66c9684d8c16517` |

Both aliases are byte-identical to their versioned APKs. `aapt dump badging` reports package `app.docscan.scanner`, version name `0.4.282`, version code `401` for both.

`apksigner verify --verbose --print-certs` accepted both APKs with one signer and v2 signature. Debug uses the Android debug certificate. Release uses the configured product release certificate (SHA-256 certificate digest `af0ccc128ebb99b01280fe64613986c8475c45ab041e3030bf285293785625c7`); no signing secrets were recorded.

Both production APKs contain ABIs `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. Archive entries contain no ONNX model or ONNX Runtime library; scanning DEX strings found no ONNX Runtime classes. The full native instrumentation run passed the existing default-off trace/frame archive assertion. No physical device was used.

Build log: `final-apk-build.log`.
