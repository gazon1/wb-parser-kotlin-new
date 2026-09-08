package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.value.Cashback
import ru.wbparser.domain.value.Price

class PriceTest : FunSpec({

    test("ZERO is 0 kopecks") {
        Price.ZERO.kopecks shouldBe 0L
    }

    test("kopecks creates price with that value") {
        Price.kopecks(49900L).kopecks shouldBe 49900L
    }

    test("rubles converts correctly") {
        Price.rubles(499.0).kopecks shouldBe 49900L
    }

    test("rubles with fractional part") {
        Price.rubles(99.99).kopecks shouldBe 9999L
    }

    test("negative kopecks throws") {
        runCatching { Price.kopecks(-1L) }.isFailure shouldBe true
    }

    test("inRubles returns double value") {
        Price.kopecks(49900L).inRubles shouldBe 499.0
    }

    test("inRubles with fractional rubles") {
        Price.kopecks(9999L).inRubles shouldBe 99.99
    }

    test("inRubles zero") {
        Price.ZERO.inRubles shouldBe 0.0
    }

    test("cashbackPercent zero price returns 0.0") {
        Price.ZERO.cashbackPercent(Cashback.ZERO) shouldBe 0.0
    }

    test("cashbackPercent calculates correctly") {
        // Price 100 rubles (10000 kopecks), Cashback 5 rubles (500 kopecks) = 5%
        val price = Price.kopecks(10000L)
        val cashback = Cashback.fromRubles(5.0)
        price.cashbackPercent(cashback) shouldBe 5.0
    }

    test("cashbackPercent calculates fractional") {
        // Price 100 rubles, Cashback 5.5% = 5.5 rubles
        val price = Price.kopecks(10000L)
        val cashback = Cashback.percent(5.5, price)
        cashback.inRubles shouldBe 5.5
    }
})
