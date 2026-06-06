package scala.meta.internal.metals.testProvider.frameworks

class TestNGTestFinder extends AnnotationTestFinder {
  override def expectedAnnotationSymbols: Set[String] = Set(
    "org/testng/annotations/Test#"
  )
}
