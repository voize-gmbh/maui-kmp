package com.mauikmpexample.kotlin.shared

import de.voize.mauikmp.annotation.MauiBinding

@MauiBinding
@Throws(Exception::class)
fun foo(
    string: String,
): String {
    return "Hello $string"
}
