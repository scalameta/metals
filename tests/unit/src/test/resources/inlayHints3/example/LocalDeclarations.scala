package example.nested

trait LocalDeclarations:
  def foo(): Unit

trait Foo:
  val y/*: Int<<scala/Int#>>*/ = 3

object LocalDeclarations:
  def create(): LocalDeclarations =
    def bar(): Unit = ()

    val x/*: Object<<java/lang/Object#>>*/ = new:
      val x/*: Int<<scala/Int#>>*/ = 2

    val y/*: Foo<<(5:6)>>*/ = new Foo {}

    val yy/*: Int<<scala/Int#>>*/ = y.y

    new LocalDeclarations with Foo:
      override def foo(): Unit = bar()