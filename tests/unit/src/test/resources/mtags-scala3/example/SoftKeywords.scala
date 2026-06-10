/*example.SoftKeywords$package.*/package example

// inline keyword
inline def inlineMethod/*example.SoftKeywords$package.inlineMethod().*/(x/*example.SoftKeywords$package.inlineMethod().(x)*/: Int): Int = x + 1

inline val inlineVal/*example.SoftKeywords$package.inlineVal.*/ = 42

// opaque type
opaque type OpaqueInt/*example.SoftKeywords$package.OpaqueInt#*/ = Int

// open class
open class OpenClass/*example.OpenClass#*/

// transparent trait
transparent trait TransparentTrait/*example.TransparentTrait#*/

// infix method
class InfixExample/*example.InfixExample#*/:
  infix def combine/*example.InfixExample#combine().*/(other/*example.InfixExample#combine().(other)*/: InfixExample): InfixExample = this

// derives clause
case class Point/*example.Point#*/(x/*example.Point#x.*/: Int, y/*example.Point#y.*/: Int) derives CanEqual

// as in import rename
import scala.collection.mutable.ListBuffer as MutableList
