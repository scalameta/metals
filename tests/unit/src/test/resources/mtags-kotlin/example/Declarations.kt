   package example
//         ^^^^^^^ reference example/

   import java.io.Closeable
   import kotlin.collections.List as KList

   class User(val name: String, var age: Int = 0, nickname: String? = null)
//       ^^^^ definition example/User# CLASS(final)
//       ^^^^ definition example/User#`<init>`(). CONSTRUCTOR
//                ^^^^ definition example/User#name. METHOD(val)
//                ^^^^ definition example/User#getName(). METHOD
//                                  ^^^ definition example/User#age. METHOD(var)
//                                  ^^^ definition example/User#getAge(). METHOD
//                                  ^^^ definition example/User#setAge(). METHOD

   open class Base(val id: Long)
//            ^^^^ definition example/Base# CLASS
//            ^^^^ definition example/Base#`<init>`(). CONSTRUCTOR
//                     ^^ definition example/Base#id. METHOD(val)
//                     ^^ definition example/Base#getId(). METHOD

   abstract class Shape : Comparable<Shape>, Closeable {
//                ^^^^^ definition example/Shape# CLASS(abstract)
//                ^^^^^ definition example/Shape#`<init>`(). CONSTRUCTOR
       abstract fun area(): Double
//                  ^^^^ definition example/Shape#area(). METHOD
       override fun compareTo(other: Shape): Int = area().compareTo(other.area())
//                  ^^^^^^^^^ definition example/Shape#compareTo(). METHOD
       override fun close() {}
//                  ^^^^^ definition example/Shape#close(). METHOD
   }

   sealed class Result<out T> {
//              ^^^^^^ definition example/Result# CLASS(sealed)
//              ^^^^^^ definition example/Result#`<init>`(). CONSTRUCTOR
       data class Ok<T>(val value: T) : Result<T>()
//                ^^ definition example/Result#Ok# CLASS(final)
//                ^^ definition example/Result#Ok#`<init>`(). CONSTRUCTOR
//                          ^^^^^ definition example/Result#Ok#value. METHOD(val)
//                          ^^^^^ definition example/Result#Ok#getValue(). METHOD
       data class Error(val message: String) : Result<Nothing>()
//                ^^^^^ definition example/Result#Error# CLASS(final)
//                ^^^^^ definition example/Result#Error#`<init>`(). CONSTRUCTOR
//                          ^^^^^^^ definition example/Result#Error#message. METHOD(val)
//                          ^^^^^^^ definition example/Result#Error#getMessage(). METHOD
       data object Loading : Result<Nothing>()
//                 ^^^^^^^ definition example/Result#Loading# OBJECT(final)
//                 ^^^^^^^ definition example/Result#Loading#`<init>`(). CONSTRUCTOR
   }

   interface Greeter {
//           ^^^^^^^ definition example/Greeter# INTERFACE
       val greeting: String
//         ^^^^^^^^ definition example/Greeter#greeting. METHOD(val)
//         ^^^^^^^^ definition example/Greeter#getGreeting(). METHOD
       fun greet(name: String): String = "$greeting, $name"
//         ^^^^^ definition example/Greeter#greet(). METHOD
   }

   fun interface Callback {
//               ^^^^^^^^ definition example/Callback# INTERFACE
       fun call(value: Int)
//         ^^^^ definition example/Callback#call(). METHOD
   }

   enum class Color(val rgb: Int) {
//            ^^^^^ definition example/Color# CLASS(final, enum)
//            ^^^^^ definition example/Color#`<init>`(). CONSTRUCTOR
//                      ^^^ definition example/Color#rgb. METHOD(val)
//                      ^^^ definition example/Color#getRgb(). METHOD
       RED(0xff0000),
//     ^^^ definition example/Color#RED. FIELD(enum)
       GREEN(0x00ff00) {
//     ^^^^^ definition example/Color#GREEN. FIELD(enum)
           override fun label(): String = "green"
       },
       BLUE(0x0000ff);
//     ^^^^ definition example/Color#BLUE. FIELD(enum)

       open fun label(): String = name.lowercase()
//              ^^^^^ definition example/Color#label(). METHOD
   }

   annotation class Marker(val value: String = "")
//                  ^^^^^^ definition example/Marker# INTERFACE
//                             ^^^^^ definition example/Marker#value. METHOD(val)
//                             ^^^^^ definition example/Marker#value(). METHOD

   @JvmInline
   value class Meters(val value: Double)
//             ^^^^^^ definition example/Meters# CLASS(final)
//             ^^^^^^ definition example/Meters#`<init>`(). CONSTRUCTOR
//                        ^^^^^ definition example/Meters#value. METHOD(val)
//                        ^^^^^ definition example/Meters#getValue(). METHOD

   class Box<T : Comparable<T>>(private val items: MutableList<T>) where T : Any {
//       ^^^ definition example/Box# CLASS(final)
//       ^^^ definition example/Box#`<init>`(). CONSTRUCTOR
//                                          ^^^^^ definition example/Box#items. METHOD(val)
//                                          ^^^^^ definition example/Box#getItems(). METHOD
       fun <R> map(transform: (T) -> R): KList<R> = items.map(transform)
//             ^^^ definition example/Box#map(). METHOD
   }

   object Registry {
//        ^^^^^^^^ definition example/Registry# OBJECT(final)
//        ^^^^^^^^ definition example/Registry#`<init>`(). CONSTRUCTOR
       @JvmField val names: MutableList<String> = mutableListOf()
//                   ^^^^^ definition example/Registry#names. METHOD(val)
//                   ^^^^^ definition example/Registry#getNames(). METHOD
       fun register(name: String) {
//         ^^^^^^^^ definition example/Registry#register(). METHOD
           names.add(name)
       }
   }

   typealias Names = KList<String>//           ^^^^^ definition example/Names# TYPE
