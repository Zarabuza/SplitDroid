package com.novpn.splitdroid

/**
 * Known Russian bank / gov / marketplace package names.
 * These apps are excluded from our VpnService via [android.net.VpnService.Builder.addDisallowedApplication]
 * so they use the underlay network (no TRANSPORT_VPN for that app).
 */
object RussianPackages {
    val packages: List<String> = listOf(
        // Banks
        "ru.sberbankmobile",
        "ru.sberbank.sberbankid",
        "ru.sberbank.sberid",
        "com.idamob.tinkoff.android",
        "com.tinkoff.android",
        "ru.vtb24.mobilebanking.android",
        "ru.vtb.mobilebank",
        "ru.alfabank.mobile.android",
        "ru.alfabank.mobile.android.phone",
        "com.ftc.avangardonline",
        "logo.com.mbanking",
        "ru.raiffeisennews",
        "ru.rosbank.android",
        "ru.openbank",
        "ru.otpbank.mobile",
        "ru.gazprombank.android.mobilebank.app",
        "ru.gazprombank.invest",
        "ru.psbank.online",
        "ru.sovcomcard.android",
        "ru.mkb.mobile",
        "ru.unicredit.android",
        "ru.mts.money",
        "ru.yandex.bank",
        "com.yandex.bank",
        "ru.yoomoney.app",
        "ru.yandex.money",
        "com.yandex.money.android",

        // Gov / digital ID
        "ru.gosuslugi",
        "ru.gosuslugi.cabinet",
        "ru.gosuslugi.goskey",
        "ru.rt.mobile.gosuslugi",
        "ru.rostel",
        "ru.mos.socapp",
        "ru.mos.mobile",
        "ru.nalog.taxinspection",
        "ru.fns.lkfl",

        // T-Bank extras (often separate APKs)
        "ru.tinkoff.sme",
        "ru.tinkoff.investing",
        "ru.tinkoff.mvno",
        "ru.tinkoff.tg",
        "ru.tinkoff.mb.wallet",

        // Marketplaces / delivery
        "ru.ozon.app.android",
        "com.wildberries.ru",
        "ru.wildberries.client",
        "ru.yandex.market",
        "ru.avito",
        "com.avito.android",
        "ru.dns.shop.android",
        "ru.citilink.companion",
        "ru.mvideo.app",
        "ru.sbermarket",
        "ru.kuper.android",
        "ru.deliveryclub",
        "ru.foodfox.client",
        "ru.yandex.taxi",
        "ru.yandex.eda",
        "ru.yandex.metro",
        "ru.yandex.translate",
        "ru.yandex.telemost",
        "ru.yandex.music",
        "com.yandex.mobile.drive",
        "com.yandex.searchapp",
        "ru.yandex.direct",

        // Telco / mail / travel
        "ru.mts.mymts",
        "ru.mts.mtstv",
        "ru.beeline.services",
        "ru.megafon.mlk",
        "ru.tele2.mytele2",
        "ru.russianpost.android",
        "ru.rzd.pass",
        "ru.rzd.mobile",

        // Yandex suite (often blocked / geo-sensitive)
        "ru.yandex.searchplugin",
        "com.yandex.browser",
        "ru.yandex.androidkeyboard",
        "ru.yandex.yandexmaps",
        "ru.yandex.mail",
        "com.yandex.mobile.music",
        "ru.yandex.disk",
    ).distinct()
}
