package example.nested

trait LocalDeclarations/*example.nested.LocalDeclarations#*/ {
  def foo/*example.nested.LocalDeclarations#foo().*/(): Unit
}

trait Foo/*example.nested.Foo#*/ {}

object LocalDeclarations/*example.nested.LocalDeclarations.*/ {
  def create/*example.nested.LocalDeclarations.create().*/(): LocalDeclarations = {
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
