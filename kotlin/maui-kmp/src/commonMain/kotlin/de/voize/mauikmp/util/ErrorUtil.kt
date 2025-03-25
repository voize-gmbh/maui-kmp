package de.voize.mauikmp.util

fun Throwable.allMessages(): String {
    val cause = cause
    return this::class.simpleName + ": " + message + if (cause != null) {
        "\nCaused by: " + cause.allMessages()
    } else {
        ""
    }
}
