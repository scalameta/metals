package scala.meta.internal.metals.testProvider.frameworks

import scala.reflect.NameTransformer

class TestNGTestFinder extends AnnotationTestFinder {
  override def expectedAnnotationSymbols: Set[String] = Set(
    "org/testng/annotations/Test#"
  )

  override def encode(annotationSymbol: String, displayName: String): String =
    NameTransformer.encode(displayName)
}
