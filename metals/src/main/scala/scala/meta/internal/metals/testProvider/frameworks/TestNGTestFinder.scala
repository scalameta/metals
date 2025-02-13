package scala.meta.internal.metals.testProvider.frameworks

class TestNGTestFinder extends AnnotationTestFinder {
  override def expectedAnnotationSymbol: String = "org/testng/annotations/Test#"
}
