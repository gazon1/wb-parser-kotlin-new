package ru.wbparser.infra.scheduler

/**
 * Freshness policy configuration.
 *
 * Only [defaultFreshnessMinutes] is read — by the claim that takes the targets for a
 * run. The previous `minIntervalMinutes` had no reader and was removed rather than
 * left to look configurable.
 *
 * ## Where the rule lives
 *
 * The rule itself — which targets are due — is in the claim's SQL, in
 * `claimDueTargets`. It used to be in [ru.wbparser.infra.scheduler.targetsDue], in
 * Kotlin, and the crawler fetched every target and decided in memory. Two
 * statements of one rule is two chances to disagree, and the claim is a statement
 * about the database: a target that was read recently, or that is waiting after a
 * failure, or that is leased by somebody else, is not claimed.
 */
data class FreshnessPolicy(
    val defaultFreshnessMinutes: Long = 240,
)
