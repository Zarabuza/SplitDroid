# SplitDroid — Раздельный туннель

Android-приложение (Kotlin + Jetpack Compose): DNS-туннель + обход VPN для российских приложений.

## Как это работает

Android разрешает **только один** активный `VpnService`.

1. **Выключите коммерческий VPN.** SplitDroid не может работать «поверх» другого VPN.
2. Включите Раздельный туннель.
3. Известные российские приложения (Сбер, Госуслуги/`ru.rostel`, Т‑Банк, ВТБ, Ozon, WB и др.) исключаются через `Builder.addDisallowedApplication` — они идут по обычному Wi‑Fi/LTE **без** `TRANSPORT_VPN`.
4. Для остальных приложений поднимается DNS-only VPN (маршрут только на `10.0.0.1/32`): российские домены резолвятся через защищённый сокет (`protect()`), интернет приложений не blackhole'ится.

## Ограничения

- Нельзя одновременно с другим VPN.
- Некоторые приложения проверяют VPN **системно** (иконка VPN / `tun0`) — тогда обход может не помочь; для пакетов из списка обычно хватает `NOT_VPN` на их active network.

## Требования

- Android Studio Hedgehog+ / JDK 17
- Android SDK 34
- Min SDK 26

## Сборка

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Package

`com.novpn.splitdroid`
