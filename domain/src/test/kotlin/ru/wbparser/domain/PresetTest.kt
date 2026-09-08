package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.model.Preset
import ru.wbparser.domain.model.PresetFlag
import ru.wbparser.domain.model.SortDirection
import ru.wbparser.domain.model.toWbSortParam

class PresetTest : FunSpec({

    test("toWbSortParam ASC") {
        val preset = Preset(id = 1, name = "Price", sortField = "price", sortDirection = SortDirection.ASC)
        preset.toWbSortParam() shouldBe "price=asc"
    }

    test("toWbSortParam DESC") {
        val preset = Preset(id = 2, name = "Popular", sortField = "popularity", sortDirection = SortDirection.DESC)
        preset.toWbSortParam() shouldBe "popularity=desc"
    }

    test("default sortDirection is ASC") {
        val preset = Preset(id = 1, name = "Price", sortField = "price")
        preset.toWbSortParam() shouldBe "price=asc"
    }

    test("PresetFlag values") {
        PresetFlag.TopDeals shouldBe PresetFlag.TopDeals
        PresetFlag.Popular shouldBe PresetFlag.Popular
        PresetFlag.New shouldBe PresetFlag.New
        PresetFlag.Recommendation shouldBe PresetFlag.Recommendation
    }

    test("Preset with multiple flags") {
        val preset = Preset(
            id = 1,
            name = "Custom",
            sortField = "price",
            sortDirection = SortDirection.ASC,
            flags = setOf(PresetFlag.TopDeals, PresetFlag.Popular),
        )
        preset.flags shouldBe setOf(PresetFlag.TopDeals, PresetFlag.Popular)
    }

    test("empty flags") {
        val preset = Preset(id = 1, name = "Default", sortField = "price")
        preset.flags shouldBe emptySet()
    }
})
