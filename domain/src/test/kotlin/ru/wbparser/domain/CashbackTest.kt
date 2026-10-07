package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.value.Cashback
import ru.wbparser.domain.value.Price

class CashbackTest :
    FunSpec({

        test("ZERO is 0 kopecks") {
            Cashback.ZERO.let { cb ->
                cb.inRubles shouldBe 0.0
            }
        }

        test("fromRubles converts correctly") {
            Cashback.fromRubles(5.5).inRubles shouldBe 5.5
        }

        test("fromRubles with fractional kopecks truncates") {
            // 1.111 rubles = 111.1 kopecks → truncated to 111 kopecks = 1.11 rubles
            Cashback.fromRubles(1.111).inRubles shouldBe 1.11
        }

        test("percent calculates from price") {
            val price = Price.rubles(100.0)
            val cb = Cashback.percent(7.5, price)
            cb.inRubles shouldBe 7.5
        }

        test("percent of zero price is zero") {
            val cb = Cashback.percent(50.0, Price.ZERO)
            cb.inRubles shouldBe 0.0
        }
    })
