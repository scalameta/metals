package tests.feature

import scala.meta.internal.metals.BuildInfo as V

import tests.BaseSuperHierarchySuite

class SuperHierarchyCrossLspSuite
    extends BaseSuperHierarchySuite("super-method-hierarchy-cross") {

  if (super.isValidScalaVersionForEnv(V.scala212)) {
    test("library members scala 2.12") {
      checkHierarchy(
        libraryMembers,
        Map(
          1 -> List(
            "scala.collection.TraversableLike#map",
            "scala.collection.GenTraversableLike#map",
            "scala.collection.generic.FilterMonadic#map",
          ),
          2 -> List("java.lang.CharSequence#length"),
          3 -> List("scala.collection.TraversableLike#toString"),
          4 -> List(
            "scala.math.Ordering#compare",
            "java.util.Comparator#compare",
          ),
          5 -> List(),
          6 -> List(
            ";java.lang.AbstractStringBuilder#append",
            "java.lang.Appendable#append",
          ),
        ),
        V.scala212,
      )
    }

    test("library overloads scala 2.12") {
      checkHierarchy(
        libraryOverloads,
        Map(
          1 -> List("scala/collection/mutable/BufferLike#remove()."),
          2 -> List("scala/collection/mutable/BufferLike#remove(+1)."),
        ),
        V.scala212,
        rawSymbols = true,
      )
    }

    test("library member in a dependency source scala 2.12") {
      checkDependencySource(
        V.scala212,
        List(
          "scala.collection.generic.TraversableForwarder#toList",
          "scala.collection.TraversableOnce#toList",
          "scala.collection.GenTraversableOnce#toList",
        ),
      )
    }
  }

}
