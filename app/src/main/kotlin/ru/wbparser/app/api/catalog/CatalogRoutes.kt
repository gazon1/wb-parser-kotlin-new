package ru.wbparser.app.api.catalog

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.wbparser.domain.model.Preset
import ru.wbparser.infra.catalog.CatalogQueries
import ru.wbparser.infra.catalog.getCategories
import ru.wbparser.infra.catalog.getCategoryById
import ru.wbparser.infra.catalog.getItemsByCategory
import ru.wbparser.infra.catalog.getProductById
import ru.wbparser.infra.catalog.getTopDeals
import ru.wbparser.infra.catalog.newCatalogQueries
import ru.wbparser.infra.catalog.search
import ru.wbparser.infra.presets.allPresets
import ru.wbparser.infra.presets.preset
import javax.sql.DataSource

/**
 * Public catalog endpoints — powers the Next.js frontend.
 */
@RestController
@RequestMapping("/api/catalog")
class CatalogRoutes(private val ds: DataSource) {

    private val catalog: CatalogQueries get() = newCatalogQueries(ds)

    @GetMapping("/categories")
    fun getCategories() = catalog.getCategories()

    @GetMapping("/category/{id}")
    fun getCategory(@PathVariable id: Long) = catalog.getCategoryById(id)

    @GetMapping("/category/{id}/items")
    fun getCategoryItems(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") pageSize: Int,
        @RequestParam(required = false) presetId: String?,
    ): Any {
        val presetObj: Preset? = presetId?.let { preset(it) }
        return catalog.getItemsByCategory(id, page, pageSize, presetObj)
    }

    @GetMapping("/product/{id}")
    fun getProduct(@PathVariable id: Long) = catalog.getProductById(id)

    @GetMapping("/search")
    fun search(
        @RequestParam q: String,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") pageSize: Int,
    ) = catalog.search(q, page, pageSize)

    @GetMapping("/top-deals")
    fun getTopDeals(@RequestParam(defaultValue = "50") limit: Int) = catalog.getTopDeals(limit)

    @GetMapping("/presets")
    fun getPresets() = allPresets()
}
