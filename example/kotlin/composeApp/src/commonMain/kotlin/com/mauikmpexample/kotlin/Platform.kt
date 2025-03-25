package com.mauikmpexample.kotlin

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform