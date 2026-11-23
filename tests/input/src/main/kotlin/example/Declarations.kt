package example

import java.io.Closeable
import kotlin.collections.List as KList

data class User(val name: String, var age: Int = 0, nickname: String? = null)

open class Base(val id: Long)

abstract class Shape : Comparable<Shape>, Closeable {
    abstract fun area(): Double
    override fun compareTo(other: Shape): Int = area().compareTo(other.area())
    override fun close() {}
}

sealed class Result<out T> {
    data class Ok<T>(val value: T) : Result<T>()
    data class Error(val message: String) : Result<Nothing>()
    data object Loading : Result<Nothing>()
}

interface Greeter {
    val greeting: String
    fun greet(name: String): String = "$greeting, $name"
}

fun interface Callback {
    fun call(value: Int)
}

enum class Color(val rgb: Int) {
    RED(0xff0000),
    GREEN(0x00ff00) {
        override fun label(): String = "green"
    },
    BLUE(0x0000ff);

    open fun label(): String = name.lowercase()
}

annotation class Marker(val value: String = "")

@JvmInline
value class Meters(val value: Double)

class Box<T : Comparable<T>>(private val items: MutableList<T>) where T : Any {
    fun <R> map(transform: (T) -> R): KList<R> = items.map(transform)
}

object Registry {
    @JvmField val names: MutableList<String> = mutableListOf()
    fun register(name: String) {
        names.add(name)
    }
}

typealias Names = KList<String>
