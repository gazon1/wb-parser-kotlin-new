package ru.wbparser.app.config

import java.sql.Connection

/**
 * The state that every part of the demo seed shares.
 *
 * The seed is three decisions — the environment it runs in, the rights it needs
 * and the data it applies — and all three need the same three things: the
 * connection they run on, the role that connection works as, and the settings
 * the seed was started with.
 *
 * ## Why a holder, and not more parameters
 *
 * A list of parameters is a list of parameters that has to be copied into every
 * collaborator, and the copy is where the arguments get swapped: a role that
 * should have been the expected one silently becomes the created one, and the
 * barrier that compares them stops comparing anything. One holder, built in one
 * place, keeps the two roles apart — the role being created and the role the
 * connection has to resolve to are different roles, and they are different
 * *fields* of the same holder rather than the same argument in two places.
 */
data class DemoSeedContext(
    /** The connection the step runs on. */
    val connection: Connection,
    /**
     * The role this connection works as.
     *
     * For the writer's connection it is the role the seed creates and grants to.
     * For the seed's own connection it is the role that connection has to
     * resolve to — the one the barrier compares against.
     */
    val role: String,
    /** The settings the seed was started with: the flags. */
    val settings: DemoSeed,
) {
    companion object {
        /**
         * The writer's connection: the role the seed creates and grants to there.
         */
        fun writer(
            connection: Connection,
            settings: DemoSeed,
        ): DemoSeedContext = DemoSeedContext(connection = connection, role = settings.dbUser, settings = settings)

        /**
         * The seed's own connection: the role it has to resolve to.
         */
        fun seed(
            connection: Connection,
            settings: DemoSeed,
        ): DemoSeedContext = DemoSeedContext(connection = connection, role = settings.expectedRole, settings = settings)
    }
}
