package ru.wbparser.domain.value

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either

@JvmInline
value class ProductId(
    val value: Long,
) {
    init {
        require(value > 0) { "ProductId must be positive: $value" }
    }

    companion object {
        fun from(dtoId: Long): Either<InvalidId, ProductId> =
            either {
                catch({
                    ProductId(dtoId)
                }) { e ->
                    raise(InvalidId("Invalid product id: $dtoId — ${e.message}"))
                }
            }
    }
}

@JvmInline
value class InvalidId(
    val message: String,
)
