package com.mauikmpexample.kotlin.shared

import de.voize.mauikmp.annotation.MauiBinding

@MauiBinding
fun foo(
    string: String,
): String {
    return "Hello $string"
}
