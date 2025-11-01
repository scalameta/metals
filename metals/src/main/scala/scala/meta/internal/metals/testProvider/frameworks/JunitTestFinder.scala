package scala.meta.internal.metals.testProvider.frameworks

class JunitTestFinder extends AnnotationTestFinder {
  override def expectedAnnotationSymbol: String =
    JunitTestFinder.junitAnnotationSymbol
}

object JunitTestFinder {
  val junitAnnotationSymbol = "org/junit/Test#"
  val junitBaseClassSymbol = "junit/framework/TestCase#"
}
