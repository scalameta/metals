package example

// inline keyword
inline/*<no symbol>*/ def inlineMethod/*SoftKeywords.scala*/(x/*SoftKeywords.scala*/: Int/*Int.scala*/): Int/*Int.scala*/ = x/*SoftKeywords.scala*/ +/*Int.scala*/ 1

inline/*<no symbol>*/ val inlineVal/*SoftKeywords.scala*/ = 42

// opaque type
opaque/*<no symbol>*/ type OpaqueInt/*SoftKeywords.scala*/ = Int/*Int.scala*/

// open class
open/*<no symbol>*/ class OpenClass/*SoftKeywords.scala*/

// transparent trait
transparent/*<no symbol>*/ trait TransparentTrait/*SoftKeywords.scala*/

// infix method
class InfixExample/*SoftKeywords.scala*/:
  infix/*<no symbol>*/ def combine/*SoftKeywords.scala*/(other/*SoftKeywords.scala*/: InfixExample/*SoftKeywords.scala*/): InfixExample/*SoftKeywords.scala*/ = this

// derives clause
case class Point/*SoftKeywords.scala*/(x/*SoftKeywords.scala*/: Int/*Int.scala*/, y/*SoftKeywords.scala*/: Int/*Int.scala*/) derives/*<no symbol>*/ CanEqual/*<no symbol>*/

// as in import rename
import scala.collection.mutable.ListBuffer/*ListBuffer.scala*/ as/*<no symbol>*/ MutableList/*<no symbol>*/
