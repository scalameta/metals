/*example(Package):25*/package example

// inline keyword
/*example.inlineMethod(Method):4*/inline def inlineMethod(x: Int): Int = x + 1

/*example.inlineVal(Constant):6*/inline val inlineVal = 42

// opaque type
/*example.OpaqueInt(TypeParameter):9*/opaque type OpaqueInt = Int

// open class
/*example.OpenClass(Class):12*/open class OpenClass

// transparent trait
/*example.TransparentTrait(Interface):15*/transparent trait TransparentTrait

// infix method
/*example.InfixExample(Class):19*/class InfixExample:
  /*example.InfixExample#combine(Method):19*/infix def combine(other: InfixExample): InfixExample = this

// derives clause
/*example.Point(Class):22*/case class Point(/*example.Point#x(Variable):22*/x: Int, /*example.Point#y(Variable):22*/y: Int) derives CanEqual

// as in import rename
import scala.collection.mutable.ListBuffer as MutableList
