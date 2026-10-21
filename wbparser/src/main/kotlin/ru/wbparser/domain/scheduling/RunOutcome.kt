package ru.wbparser.domain.scheduling

/**
 * How one run of one target ended.
 *
 * The vocabulary is the same one the run of a whole crawl uses, so that the two can
 * be read together: a job is `Failed` because some of its targets were `antibot` or
 * `target_candidate`, and the rows say which.
 *
 * @param dbValue what is written to the database. It is spelled out here rather than
 *   derived from the name, so that renaming an enum constant cannot silently change
 *   the data: the constraint in the migration lists the same four values.
 */
enum class RunOutcome(
    val dbValue: String,
) {
    /** The target was read. */
    Ok("ok"),

    /** WB refused the request: the address's fault, not the catalog's. */
    Antibot("antibot"),

    /** WB answered with something that is not the catalog: the catalog may be at fault. */
    TargetCandidate("target_candidate"),

    /** The run broke for a reason that has no class: a timeout, a crash, a parse error. */
    Error("error"),
    ;

    companion object {
        /**
         * The outcome of a result, from the class of its failure.
         *
         * A result without a failure is [Ok]; a failure with a class is that class;
         * a failure without one is [Error].
         */
        fun of(
            failed: Boolean,
            failureClass: FailureClass?,
        ): RunOutcome =
            when {
                !failed -> Ok
                failureClass == FailureClass.Egress -> Antibot
                failureClass == FailureClass.TargetCandidate -> TargetCandidate
                else -> Error
            }
    }
}
