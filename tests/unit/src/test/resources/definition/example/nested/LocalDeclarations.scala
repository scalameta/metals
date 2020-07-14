package example.nested

trait LocalDeclarations/*LocalDeclarations.scala*/ {
  def foo/*LocalDeclarations.scala*/(): Unit/*Unit.scala*/
}

trait Foo/*LocalDeclarations.scala*/ {
  val y/*LocalDeclarations.scala*/ = 3
}

object LocalDeclarations/*LocalDeclarations.scala*/ {
  def create/*LocalDeclarations.scala*/(): LocalDeclarations/*LocalDeclarations.scala*/ = {
    def bar/*LocalDeclarations.semanticdb*/(): Unit/*Unit.scala*/ = ()

    val x/*LocalDeclarations.semanticdb*/ = new {
      val x/*LocalDeclarations.semanticdb*/ = 2
    }

    val y/*LocalDeclarations.semanticdb*/ = new Foo/*LocalDeclarations.scala*/ {}

    x/*LocalDeclarations.semanticdb*/.x/*<no symbol>*/ +/*Int.scala*/ y/*LocalDeclarations.semanticdb*/.y/*LocalDeclarations.scala*/

    new LocalDeclarations/*LocalDeclarations.scala*/ with Foo/*LocalDeclarations.scala*/ {
      override def foo/*LocalDeclarations.semanticdb*/(): Unit/*Unit.scala*/ = bar/*LocalDeclarations.semanticdb*/()
    }

  }
}
