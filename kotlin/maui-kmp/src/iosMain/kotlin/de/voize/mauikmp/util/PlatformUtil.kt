package de.voize.mauikmp.util

import platform.Foundation.NSUUID

internal actual fun uuid(): String {
    return NSUUID().UUIDString().lowercase()
}