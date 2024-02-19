package example.nested

trait LocalDeclarations {
  def foo(): Unit
}

trait Foo {
  val y/*: Int<<scala/Int#>>*/ = 3
}

object LocalDeclarations {
  def create(): LocalDeclarations = {
    def bar(): Unit = ()

    val x/*: AnyRef<<scala/AnyRef#>>{val x: Int<<scala/Int#>>}*/ = new {
      val x/*: Int<<scala/Int#>>*/ = 2
    }

    val y/*: Foo<<(6:6)>>*/ = new Foo {}

    x.x + y.y

    new LocalDeclarations with Foo {
      override def foo(): Unit = bar()
    }

  }
}