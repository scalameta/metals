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
          1 -> List(),
          2 -> List(),
          3 -> List(),
          4 -> List(
            "scala.math.Ordering#compare",
            "java.util.Comparator#compare",
          ),
          5 -> List(),
          6 -> List(),
        ),
        V.scala212,
      )
    }

    test("library overloads scala 2.12") {
      checkHierarchy(
        libraryOverloads,
        Map(
          1 -> List(),
          2 -> List(),
        ),
        V.scala212,
        rawSymbols = true,
      )
    }

    test("library member in a dependency source scala 2.12") {
      checkDependencySource(
        V.scala212,
        List(),
      )
    }
  }

}
