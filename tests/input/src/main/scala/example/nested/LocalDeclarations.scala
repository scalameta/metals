package example.nested

trait LocalDeclarations {
  def foo(): Unit
}

trait Foo {}

object LocalDeclarations {
  def create(): LocalDeclarations = {
    def bar(): Unit = ()

    val x = new {
      val x = 2
    }

    val y = new Foo {}

    new LocalDeclarations with Foo {
      override def foo(): Unit = bar()
    }

  }
}
