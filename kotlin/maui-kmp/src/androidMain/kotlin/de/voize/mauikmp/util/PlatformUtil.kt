package de.voize.mauikmp.util

import java.util.*

internal actual fun uuid(): String {
    return UUID.randomUUID().toString()
}

