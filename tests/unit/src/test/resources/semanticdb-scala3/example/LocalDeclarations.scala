package example.nested

trait LocalDeclarations/*example.nested.LocalDeclarations#*/:
  def foo/*example.nested.LocalDeclarations#foo().*/(): Unit/*scala.Unit#*/

trait Foo/*example.nested.Foo#*/:
  val y/*example.nested.Foo#y.*/ = 3

object LocalDeclarations/*example.nested.LocalDeclarations.*/:
  def create/*example.nested.LocalDeclarations.create().*/(): LocalDeclarations/*example.nested.LocalDeclarations#*/ =
    def bar/*local0*/(): Unit/*scala.Unit#*/ = ()

    val x/*local4*/ = /*local2*/new:
      val x/*local1*/ = 2

    val y/*local7*/ = /*local5*/new Foo/*example.nested.Foo#*/ {}

    y/*local7*/.y/*example.nested.Foo#y.*/

    /*local9*/new LocalDeclarations/*example.nested.LocalDeclarations#*/ with Foo/*example.nested.Foo#*/:
      override def foo/*local8*/(): Unit/*scala.Unit#*/ = bar/*local0*/()
