package example.nested

trait LocalDeclarations/*example.nested.LocalDeclarations#*/ {
  def foo/*example.nested.LocalDeclarations#foo().*/(): Unit/*scala.Unit#*/
}

trait Foo/*example.nested.Foo#*/ {
  val y/*example.nested.Foo#y.*/ = 3
}

object LocalDeclarations/*example.nested.LocalDeclarations.*/ {
  def create/*example.nested.LocalDeclarations.create().*/(): LocalDeclarations/*example.nested.LocalDeclarations#*/ = {
    def bar/*local0*/(): Unit/*scala.Unit#*/ = ()

    val x/*local1*/ = new /*local3*/{
      val x/*local2*/ = 2
    }

    val y/*local4*/ = new /*local5*/Foo/*example.nested.Foo#*/ /*java.lang.Object#`<init>`().*/{}

    x/*local1*/.x +/*scala.Int#`+`(+4).*/ y/*local4*/.y/*example.nested.Foo#y.*/

    new /*local6*/LocalDeclarations/*example.nested.LocalDeclarations#*/ /*java.lang.Object#`<init>`().*/with Foo/*example.nested.Foo#*/ {
      override def foo/*local7*/(): Unit/*scala.Unit#*/ = bar/*local0*/()
    }

  }
}
