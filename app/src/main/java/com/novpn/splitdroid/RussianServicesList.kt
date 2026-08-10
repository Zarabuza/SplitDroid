package com.novpn.splitdroid

object RussianServicesList {
    val services: List<RussianService> = listOf(
        RussianService("Сбербанк", listOf("sberbank.ru", "sber.ru", "sberbank.com", "online.sberbank.ru")),
        RussianService("Т-Банк", listOf("tbank.ru", "tinkoff.ru")),
        RussianService("Госуслуги", listOf("gosuslugi.ru", "esia.gosuslugi.ru", "gosuslugi.com")),
        RussianService("ВТБ", listOf("vtb.ru", "vtb24.ru")),
        RussianService("Альфа-Банк", listOf("alfabank.ru", "alfa-bank.ru")),
        RussianService("Ozon", listOf("ozon.ru")),
        RussianService("Wildberries", listOf("wildberries.ru", "wb.ru")),
        RussianService("Яндекс", listOf("yandex.ru", "ya.ru", "yandex.com", "yandex.net")),
        RussianService("Авито", listOf("avito.ru")),
        RussianService("МТС", listOf("mts.ru")),
        RussianService("Билайн", listOf("beeline.ru")),
        RussianService("Мегафон", listOf("megafon.ru")),
        RussianService("Почта России", listOf("pochta.ru")),
        RussianService("РЖД", listOf("rzd.ru")),
        RussianService("СберМаркет", listOf("sbermarket.ru", "kuper.ru")),
        RussianService("Delivery Club", listOf("delivery-club.ru")),
        RussianService("Яндекс Еда", listOf("eda.yandex.ru")),
        RussianService("Ситилинк", listOf("citilink.ru")),
        RussianService("DNS", listOf("dns-shop.ru")),
        RussianService("М.Видео", listOf("mvideo.ru")),
    )

    val allDomains: List<String> = services
        .flatMap { it.domains }
        .map { it.lowercase() }
        .distinct()

    private val domainSet: Set<String> = allDomains.toSet()

    fun matchesRussianDomain(host: String): Boolean {
        val normalized = host.trim().lowercase().trimEnd('.')
        if (normalized.isEmpty()) return false
        if (normalized in domainSet) return true
        return domainSet.any { domain ->
            normalized == domain || normalized.endsWith(".$domain")
        }
    }
}
