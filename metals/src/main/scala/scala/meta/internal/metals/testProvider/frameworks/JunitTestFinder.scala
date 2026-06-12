package scala.meta.internal.metals.testProvider.frameworks

class JunitTestFinder extends AnnotationTestFinder {
  override def expectedAnnotationSymbols: Set[String] =
    Set(
      JunitTestFinder.junitAnnotationSymbol,
      JunitTestFinder.junit5AnnotationSymbol,
    )
}

object JunitTestFinder {
  val junitAnnotationSymbol = "org/junit/Test#"
  val junit5AnnotationSymbol = "org/junit/jupiter/api/Test#"
  val junitBaseClassSymbol = "junit/framework/TestCase#"
}
