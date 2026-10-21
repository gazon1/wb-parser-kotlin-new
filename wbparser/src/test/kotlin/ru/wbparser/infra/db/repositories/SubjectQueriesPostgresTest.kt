package ru.wbparser.infra.db.repositories

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.wbparser.testing.PostgresTestDatabase
import ru.wbparser.testing.execute

/**
 * The subject dictionary and the targets that cannot be crawled without a token.
 *
 * Both counts answer a question an operator asks when the data looks wrong:
 * "is the dictionary there?" and "how many targets would produce the popular list
 * instead of their category?".
 */
class SubjectQueriesPostgresTest :
    FunSpec({

        val schema =
            listOf(
                """
                CREATE TABLE IF NOT EXISTS wb_subjects (
                    id bigint PRIMARY KEY,
                    name text NOT NULL
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS crawl_targets (
                    id uuid PRIMARY KEY,
                    name varchar(255) NOT NULL,
                    start_url text NOT NULL,
                    wb_search_query text,
                    is_active boolean NOT NULL DEFAULT true
                )
                """.trimIndent(),
            )

        test("the dictionary is counted, and replaced rather than merged") {
            PostgresTestDatabase.withSchema(schema, tables = listOf("wb_subjects", "crawl_targets")) { dataSource ->
                dataSource.upsertSubjects(mapOf(1L to "Карта памяти", 2L to "Фотоаппарат"))

                dataSource.countSubjects() shouldBe 2L

                // WB retires subjects; a merge would keep them forever.
                dataSource.upsertSubjects(mapOf(3L to "Наушники"))

                dataSource.countSubjects() shouldBe 1L
            }
        }

        test("the names are read back by id") {
            PostgresTestDatabase.withSchema(schema, tables = listOf("wb_subjects", "crawl_targets")) { dataSource ->
                dataSource.upsertSubjects(mapOf(9467L to "Карты памяти"))

                dataSource.fetchSubjectNames() shouldBe mapOf(9467L to "Карты памяти")
            }
        }

        test("targets without a search token are counted") {
            PostgresTestDatabase.withSchema(schema, tables = listOf("wb_subjects", "crawl_targets")) { dataSource ->
                dataSource.execute(
                    "INSERT INTO crawl_targets (id, name, start_url, wb_search_query) VALUES " +
                        "('11111111-1111-1111-1111-111111111111', 'a', 'https://x', 'menu_v3_9467 карты памяти'), " +
                        "('22222222-2222-2222-2222-222222222222', 'b', 'https://y', NULL), " +
                        "('33333333-3333-3333-3333-333333333333', 'c', 'https://z', '')",
                )

                dataSource.countTargetsWithoutSearchToken() shouldBe 2L
            }
        }
    })
