package de.voize.mauikmp.ksp.processor.csharp

import com.google.devtools.ksp.symbol.KSName

fun String.toCSharpMemberName(): String {
    if (this in cSharpKeywords) {
        return "@$this"
    }
    return this
}

fun KSName.toCSharpMemberName(): String = asString().toCSharpMemberName()
