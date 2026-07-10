package scala.meta.internal.metals.testProvider.frameworks

import scala.reflect.NameTransformer

class JunitTestFinder extends AnnotationTestFinder {
  override def expectedAnnotationSymbols: Set[String] =
    Set(
      JunitTestFinder.junitAnnotationSymbol,
      JunitTestFinder.junit5AnnotationSymbol,
    )

  override def encode(annotationSymbol: String, displayName: String): String =
    if (annotationSymbol == JunitTestFinder.junit5AnnotationSymbol)
      NameTransformer.encode(displayName) + "()"
    else
      NameTransformer.encode(displayName)
}

object JunitTestFinder {
  val junitAnnotationSymbol = "org/junit/Test#"
  val junit5AnnotationSymbol = "org/junit/jupiter/api/Test#"
  val junitBaseClassSymbol = "junit/framework/TestCase#"
}
