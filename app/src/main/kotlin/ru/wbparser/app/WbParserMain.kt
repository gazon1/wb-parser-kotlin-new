package ru.wbparser.app

import org.springframework.boot.SpringApplication
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import kotlin.system.exitProcess

/**
 * Application entry point.
 *
 * `SpringApplication.run` starts the application and returns the live context.
 *
 * **Server mode** (default, and the `worker` profile): the embedded Tomcat connector runs
 * on non-daemon threads, so the JVM stays alive after `main` returns and the process runs
 * until it is signalled — `SIGTERM` from `docker stop` or systemd, `Ctrl-C` locally. Spring
 * Boot's shutdown hook then closes the context, which closes the Hikari pool and the Ktor
 * client, and the JVM exits. Nothing in this file terminates a running server: calling
 * `exitProcess` here, as this entry point used to, killed the JVM ~70 ms after "Started",
 * before Tomcat ever served a request.
 *
 * **One-shot mode** (`--spring.profiles.active=once`): `spring.main.web-application-type`
 * is `none`, so there is no web server and therefore no non-daemon thread holding the JVM
 * open — `main` returning would leave the process hanging instead of exiting. That mode
 * exits explicitly, and `SpringApplication.exit` is what turns [OneShotCrawl]'s computed
 * code into the process exit code.
 */
fun main(args: Array<String>) {
    // The array-taking overload avoids the `*args` spread, which copies the array.
    val context = SpringApplication.run(arrayOf(WbParser::class.java), args)

    if ((context as? ServletWebServerApplicationContext)?.webServer == null) {
        exitProcess(SpringApplication.exit(context))
    }
}
