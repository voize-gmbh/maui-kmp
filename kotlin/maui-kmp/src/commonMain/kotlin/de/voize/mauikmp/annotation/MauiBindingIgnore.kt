package de.voize.mauikmp.annotation

/**
 * This annotation is used to mark classes, functions, constructors or properties that should not be exposed in the generated binding.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.PROPERTY)
annotation class MauiBindingIgnore
