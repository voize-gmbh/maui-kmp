package de.voize.mauikmp.annotation

/**
 * Annotate a class, function, constructor or property to create a binding for it.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.PROPERTY)
annotation class MauiBinding
