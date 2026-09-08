package ru.wbparser.infra.presets

import ru.wbparser.domain.model.Preset
import ru.wbparser.domain.model.PresetFlag
import ru.wbparser.domain.model.SortDirection
import ru.wbparser.domain.model.toWbSortParam

/**
 * Preset catalog sort configurations.
 */
val presetCatalog = mapOf(
    "price_asc" to Preset(
        id = 1,
        name = "Сначала дешевле",
        sortField = "price",
        sortDirection = SortDirection.ASC,
        flags = emptySet(),
    ),
    "price_desc" to Preset(
        id = 2,
        name = "Сначала дороже",
        sortField = "price",
        sortDirection = SortDirection.DESC,
        flags = emptySet(),
    ),
    "popular" to Preset(
        id = 3,
        name = "По популярности",
        sortField = "popularity",
        sortDirection = SortDirection.DESC,
        flags = setOf(PresetFlag.Popular),
    ),
    "top_deals" to Preset(
        id = 4,
        name = "Топ скидки",
        sortField = "cashback",
        sortDirection = SortDirection.DESC,
        flags = setOf(PresetFlag.TopDeals),
    ),
    "new" to Preset(
        id = 5,
        name = "Новинки",
        sortField = "date",
        sortDirection = SortDirection.DESC,
        flags = setOf(PresetFlag.New),
    ),
)

/**
 * Returns the preset for the given id, or null if not found.
 */
fun preset(id: String): Preset? = presetCatalog[id]

/**
 * Returns all available presets.
 */
fun allPresets(): List<Preset> = presetCatalog.values.toList()

/**
 * Returns the top deals preset.
 */
fun topDealsPreset(): Preset? = presetCatalog["top_deals"]
