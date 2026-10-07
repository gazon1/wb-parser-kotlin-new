package ru.wbparser.app

import org.springframework.boot.SpringApplication

/**
 * Application entry point.
 */
fun main(args: Array<String>) {
    // The array-taking overload avoids the `*args` spread, which copies the array.
    SpringApplication.run(arrayOf(WbParser::class.java), args)
}
