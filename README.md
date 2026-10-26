# Quick Share

Quick Share AirDrop support for Mosey.

`QuickShareChannelService` runs in system_server and wraps the original
native Mosey AIDL v2 service (`com.google.android.moseyservice.IMoseyService/default`).
It lets Mosey hop between AWDL channels 149 and 44 and adapts the channel to the
regulatory band. It attaches only when the native service registers and only
for supported Mosey versions.

## Integration

- `frameworks/base`: `:evolution-quick-share-server-srcs` in `services.core`,
  service start in `SystemServer`, and Mosey/GMS entries in `PixelDeviceRepository`.
- `vendor/gms`: MoseyApp, moseylib and their permissions (`TARGET_INCLUDE_MOSEY`).
- `device/lineage/sepolicy`: lets system_server register and proxy `mosey_service`.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
