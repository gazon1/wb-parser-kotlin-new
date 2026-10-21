package ru.wbparser.app.catalog

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.mock.env.MockEnvironment
import org.testcontainers.containers.PostgreSQLContainer
import ru.wbparser.app.config.CatalogProperties
import ru.wbparser.app.config.Datasource
import ru.wbparser.app.config.DemoSeed
import ru.wbparser.app.config.DemoSeedRefusedException
import ru.wbparser.app.config.DemoSeedRunner
import ru.wbparser.testing.PostgresFixture

/**
 * The demo seed's five barriers.
 *
 * ## Why this suite exists
 *
 * The seed writes into `scraped_items` — the table the crawler writes into. A mistake
 * there puts synthetic rows into a working database, where they are indistinguishable
 * from real ones and survive until somebody notices.
 *
 * The barriers are the answer to that, and a barrier nobody tests is a comment. The gate
 * that renders the production templates proves that the seed is not switched on there;
 * this suite proves that each barrier *refuses*, one at a time, against a real database.
 *
 * ## What each test asserts
 *
 * Two things, always: the runner refuses (or does not run), and the database still holds
 * no synthetic rows. The second half matters more than the first — a runner that refuses
 * after writing would pass a test that only looked at the exception.
 *
 * The last test is the positive control: with every barrier satisfied, the seed does run.
 * A suite of guards that never lets the seed through would pass even if the seed were
 * broken.
 */
class DemoSeedGuardTest :
    FunSpec({
        val container =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("wbparser")
                .withUsername("postgres")
                .withPassword("postgres")
        val postgres = PostgresFixture()

        /**
         * The runner under test, with the settings the case needs.
         */
        fun runner(
            enabled: Boolean = true,
            profile: String = "demo",
            expectedRole: String = SEED_ROLE,
        ): DemoSeedRunner {
            val environment =
                MockEnvironment().apply {
                    setActiveProfiles(profile)
                }
            val properties =
                CatalogProperties(
                    datasource =
                        Datasource(
                            url = container.jdbcUrl,
                            username = "wb_catalog_ro",
                            password = "catalog-test-password",
                        ),
                    demoSeed =
                        DemoSeed(
                            enabled = enabled,
                            dbUser = SEED_ROLE,
                            dbPassword = "seed-test-password",
                            expectedRole = expectedRole,
                            dbId = DATABASE_ID,
                        ),
                )
            return DemoSeedRunner(properties, environment, postgres.ds)
        }

        /**
         * Put the database back into the state a fresh one is in.
         *
         * The seed leaves two things behind that are not rows: the marker table and the
         * seed role. Both have to go, or the next case starts from a database that the
         * previous case marked.
         */
        fun resetDatabase() {
            postgres.ds.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("TRUNCATE crawl_targets, scraped_items CASCADE")
                    statement.execute("DROP TABLE IF EXISTS public.catalog_demo_marker")
                }
                connection.createStatement().use { statement ->
                    // The privileges the seed role was given on the tables and the schema
                    // have to go first: a role that still holds them cannot be dropped.
                    statement.execute(
                        """
                        DO $$
                        BEGIN
                            IF EXISTS (SELECT FROM pg_roles WHERE rolname = '$SEED_ROLE') THEN
                                EXECUTE 'DROP OWNED BY $SEED_ROLE';
                            END IF;
                        END
                        $$
                        """.trimIndent(),
                    )
                    statement.execute("DROP ROLE IF EXISTS $SEED_ROLE")
                }
            }
        }

        fun writeMarker(id: String) {
            postgres.ds.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS public.catalog_demo_marker " +
                            "(id text PRIMARY KEY, created_at TIMESTAMPTZ NOT NULL DEFAULT now())",
                    )
                    statement.execute("DELETE FROM public.catalog_demo_marker")
                    statement.execute("INSERT INTO public.catalog_demo_marker (id) VALUES ('$id')")
                }
            }
        }

        fun seedOneTarget() {
            postgres.ds.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO crawl_targets (id, name, start_url, max_depth, is_active,
                                                   root_category_name, created_at, updated_at)
                        VALUES (gen_random_uuid(), 'Рабочий каталог', 'https://example.invalid/c', 1, true,
                                'Рабочий каталог', now(), now())
                        """.trimIndent(),
                    )
                }
            }
        }

        fun itemCount(): Int =
            postgres.ds.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM scraped_items").use { resultSet ->
                        resultSet.next()
                        resultSet.getInt(1)
                    }
                }
            }

        fun targetCount(): Int =
            postgres.ds.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM crawl_targets").use { resultSet ->
                        resultSet.next()
                        resultSet.getInt(1)
                    }
                }
            }
        beforeSpec {
            container.start()
            postgres.start(container)
        }
        afterSpec { container.stop() }

        beforeTest { resetDatabase() }

        test("the seed does not run while the flag is off") {
            // Barrier 1: the flag. It is off by default, and the runner returns without
            // doing anything rather than refusing — there is nothing to refuse.
            runner(enabled = false).run(DefaultApplicationArguments())

            itemCount() shouldBe 0
        }

        test("the seed refuses to run without the demo profile") {
            // Barrier 2: the profile. Checked at run time, so that it is a fact about the
            // running process and not about a configuration file.
            val failure =
                shouldThrow<DemoSeedRefusedException> {
                    runner(profile = "public").run(DefaultApplicationArguments())
                }

            failure.message shouldContain "profile"
            itemCount() shouldBe 0
        }

        test("the seed refuses to run against a database that holds rows") {
            // Barrier 5: emptiness. A database that already holds rows is a working
            // database, and there is no reason to create anything in one.
            seedOneTarget()

            val failure =
                shouldThrow<DemoSeedRefusedException> {
                    runner().run(DefaultApplicationArguments())
                }

            failure.message shouldContain "already holds"
            itemCount() shouldBe 0
        }

        test("the seed refuses to run against a database marked as another one") {
            // Barrier 4: the marker. The database says which one it is, and this
            // configuration does not describe it.
            writeMarker("some-other-environment")

            val failure =
                shouldThrow<DemoSeedRefusedException> {
                    runner().run(DefaultApplicationArguments())
                }

            failure.message shouldContain "marked as"
            itemCount() shouldBe 0
        }

        test("the seed refuses to run when the connection resolves to another role") {
            // Barrier 3: the exact role. The seed connects with its own credentials, and
            // the role they resolve to has to be exactly the expected one.
            val failure =
                shouldThrow<DemoSeedRefusedException> {
                    runner(expectedRole = "some_other_role").run(DefaultApplicationArguments())
                }

            failure.message shouldContain "resolved to role"
            itemCount() shouldBe 0
        }

        test("the seed runs when every barrier is satisfied") {
            // The positive control. Without it, a suite that refuses everything would
            // pass — and a seed that never runs is as broken as a seed that always runs.
            runner().run(DefaultApplicationArguments())

            itemCount() shouldBe 1209
            targetCount() shouldBe 4
        }
    })

private const val SEED_ROLE = "wb_demo_seed"

private const val DATABASE_ID = "demo-seed-guard-test"
