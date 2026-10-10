package ru.wbparser.app

import org.springframework.boot.SpringApplication
import kotlin.system.exitProcess

/**
 * Application entry point.
 *
 * `SpringApplication.run` returns the started context, which stays open for a server run.
 * `SpringApplication.exit` closes it and collects the exit code from any
 * `org.springframework.boot.ExitCodeGenerator` bean — in one-shot mode that is
 * `OneShotCrawl`. For the normal server run there is no such bean and the code is 0.
 *
 * The process is terminated only here, never from a bean: exiting from inside the context
 * skips the shutdown hooks that close the connection pool and the HTTP client.
 */
fun main(args: Array<String>) {
    // The array-taking overload avoids the `*args` spread, which copies the array.
    val context = SpringApplication.run(arrayOf(WbParser::class.java), args)
    exitProcess(SpringApplication.exit(context))
}
