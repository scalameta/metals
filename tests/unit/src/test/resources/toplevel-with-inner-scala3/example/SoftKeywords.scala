package example

// inline keyword
inline def/*example.SoftKeywords$package.*/ inlineMethod(x: Int): Int = x + 1

inline val inlineVal = 42

// opaque type
opaque type OpaqueInt = Int

// open class
open class OpenClass/*example.OpenClass#*/

// transparent trait
transparent trait TransparentTrait/*example.TransparentTrait#*/

// infix method
class InfixExample/*example.InfixExample#*/:
  infix def combine(other: InfixExample): InfixExample = this

// derives clause
case class Point/*example.Point#*/(x: Int, y: Int) derives CanEqual

// as in import rename
import scala.collection.mutable.ListBuffer as MutableList
