package ru.wbparser.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.domain.model.PagedResult

class PagedResultTest :
    FunSpec({

        test("hasNextPage when page < totalPages") {
            // page=1, totalPages=3 → hasNext=true, hasPrevious=false
            val result = PagedResult.paged(listOf("a", "b"), page = 1, pageSize = 10, totalItems = 25)
            result.hasNextPage shouldBe true
            result.hasPreviousPage shouldBe false
        }

        test("hasPreviousPage when page > 1") {
            // page=3, totalPages=3 → hasNext=false, hasPrevious=true
            val result = PagedResult.paged(listOf("a", "b"), page = 3, pageSize = 10, totalItems = 30)
            result.hasNextPage shouldBe false // 3 < 3 is false
            result.hasPreviousPage shouldBe true // 3 > 1 is true
        }

        test("no next page on last page") {
            // page=3, totalPages=3 → hasNext=false, hasPrevious=true
            val result = PagedResult.paged(listOf("a", "b"), page = 3, pageSize = 10, totalItems = 25)
            result.hasNextPage shouldBe false
            result.hasPreviousPage shouldBe true
        }

        test("no previous on first page") {
            // page=1, totalPages=1 → hasNext=false, hasPrevious=false
            val result = PagedResult.paged(listOf("a"), page = 1, pageSize = 10, totalItems = 10)
            result.hasNextPage shouldBe false
            result.hasPreviousPage shouldBe false
        }

        test("single page has no next and no previous") {
            // page=1, totalPages=1 → hasNext=false, hasPrevious=false
            val result = PagedResult.paged(listOf("a", "b"), page = 1, pageSize = 10, totalItems = 2)
            result.hasNextPage shouldBe false
            result.hasPreviousPage shouldBe false
        }

        test("totalPages calculated correctly") {
            val result = PagedResult.paged(listOf("a"), page = 1, pageSize = 10, totalItems = 25)
            result.totalPages shouldBe 3
        }

        test("totalPages rounds up") {
            val result = PagedResult.paged(listOf("a"), page = 1, pageSize = 10, totalItems = 21)
            result.totalPages shouldBe 3
        }

        test("totalPages zero when pageSize is zero") {
            val result = PagedResult.paged(listOf("a"), page = 1, pageSize = 0, totalItems = 25)
            result.totalPages shouldBe 0
        }

        test("totalPages one when items less than pageSize") {
            val result = PagedResult.paged(listOf("a"), page = 1, pageSize = 10, totalItems = 3)
            result.totalPages shouldBe 1
        }
    })
