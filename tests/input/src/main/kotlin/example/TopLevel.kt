@file:JvmName("TopLevelUtils")

package example.toplevel

import example.User

const val VERSION: String = "1.0"

var counter = 0

val String.shout: String
    get() = uppercase() + "!"

fun <T> T.describe(): String = toString()

fun Map<String, List<Int>>.total(): Int = values.sumOf { it.sum() }

fun User?.orEmpty(): String = this?.name ?: ""

fun `name with spaces`() = Unit

suspend fun fetch(id: Int): String = "id=$id"

fun overloaded() = 1
fun overloaded(x: Int) = x
fun overloaded(x: String) = x.length
