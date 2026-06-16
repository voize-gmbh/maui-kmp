package com.mauikmpexample.kotlin.shared

import de.voize.mauikmp.annotation.MauiBinding

/**
 * CompanionTest (getInstance() / init(...)) to see what the generator emits for @Throws companion
 * functions — a path the rest of the example does not exercise. Note `init` also
 * collides with the ObjC reserved `init` prefix, so it doubles as a test of that.
 * Delete after inspecting the generated binding/header.
 */
@MauiBinding
class CompanionTest internal constructor(private val value: String) {

    @MauiBinding
    @Throws(Exception::class)
    fun getValue(): String = value

    @MauiBinding
    companion object {
        @MauiBinding
        @Throws(Exception::class)
        fun getInstance(): CompanionTest {
            return CompanionTest("instance")
        }

        @MauiBinding
        @Throws(Exception::class)
        fun init(name: String): CompanionTest {
            if (name.isEmpty()) error("name required")
            return CompanionTest(name)
        }
    }
}
