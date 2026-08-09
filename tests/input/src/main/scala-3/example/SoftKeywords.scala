package example

// inline keyword
inline def inlineMethod(x: Int): Int = x + 1

inline val inlineVal = 42

// opaque type
opaque type OpaqueInt = Int

// open class
open class OpenClass

// transparent trait
transparent trait TransparentTrait

// infix method
class InfixExample:
  infix def combine(other: InfixExample): InfixExample = this

// derives clause
case class Point(x: Int, y: Int) derives CanEqual

// as in import rename
import scala.collection.mutable.ListBuffer as MutableList
