package scala.meta.internal.metals.testProvider.frameworks

import scala.collection.mutable

import scala.meta.Defn
import scala.meta.Lit
import scala.meta.Term
import scala.meta.Tree
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.testProvider.FullyQualifiedName
import scala.meta.internal.metals.testProvider.TestCaseEntry
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath

class ZioTestFinder(
    trees: Trees
) {

  def findTests(
      path: AbsolutePath,
      suiteName: FullyQualifiedName,
  ): Vector[TestCaseEntry] = {
    path.toURI

    trees
      .get(path)
      .flatMap(tree => TreeUtils.extractTemplateFrom(tree, suiteName.value))
      .map { template =>
        val testCases = new mutable.ArrayBuffer[TestCaseEntry]()
        // Find the "spec" definition which contains all tests
        template.body.children.collect {
          // Match any definition named "spec" and process its body
          case defn: Defn.Def if defn.name.value == "spec" =>
            processSpecTree(defn.body, "", testCases, path.toURI)
          // Match any definition named "tests" and process its body
          case defn: Defn.Def if defn.name.value == "tests" =>
            processSpecTree(defn.body, "", testCases, path.toURI)
        }

        testCases.toVector
      }
      .getOrElse(Vector.empty)
  }

  /**
   * Recursively process the spec tree to find suites and tests.
   *
   * @param tree The tree to process
   * @param prefix The prefix to prepend to test names (from parent suites)
   * @param testCases The buffer to which we add found test cases
   * @param uri The URI of the file containing the tests
   */
  private def processSpecTree(
      tree: Tree,
      prefix: String,
      testCases: mutable.ArrayBuffer[TestCaseEntry],
      uri: java.net.URI,
  ): Unit = {
    tree match {
      // Handle test calls: test("testName") { ... }
      case appl @ Term.Apply(
            Term.Apply(
              Term.Name("test"),
              List(Lit.String(testName)),
            ),
            _,
          ) =>
        val location = appl.pos.toLsp.toLocation(uri)
        testCases.addOne(TestCaseEntry(testName, location))

      // Handle suite calls: suite("suiteName")( ... ) or suiteAll("suiteName") { ... }
      case appl @ Term.Apply(
            Term.Apply(
              Term.Name(name),
              List(Lit.String(suiteName)),
            ),
            suiteContents,
          ) if name == "suite" || name == "suiteAll" =>
        val newPrefix = if (prefix.isEmpty) suiteName else s"$prefix $suiteName"
        val location = appl.pos.toLsp.toLocation(uri)
        testCases.addOne(TestCaseEntry(suiteName, location))
        suiteContents.foreach(content =>
          processSpecTree(content, newPrefix, testCases, uri)
        )

      // Handle multiple tests or suites separated by commas (wrapped in a block)
      case Term.Block(stats) =>
        stats.foreach(stat => processSpecTree(stat, prefix, testCases, uri))

      // Handle method calls on suite expressions (e.g., .provideLayer(), .timeout(), etc.)
      // These wrap the suite but shouldn't stop traversal
      case appl @ Term.Apply(receiver, _) =>
        // First try to process as a normal apply (suite/test with arguments)
        // If it doesn't match those patterns, unwrap method calls and continue
        receiver match {
          case Term.Apply(_, _) | Term.Name(_) =>
            // This looks like it could be suite(...) or test(...), process arguments
            appl.children.foreach(child =>
              processSpecTree(child, prefix, testCases, uri)
            )
          case _ =>
            // This is likely a method call wrapper, process the receiver
            processSpecTree(receiver, prefix, testCases, uri)
        }

      // Other cases - continue traversing
      case _ =>
        tree.children.foreach(child =>
          processSpecTree(child, prefix, testCases, uri)
        )
    }
  }
}

object ZioTestFinder {
  val baseParentClasses: Set[String] = Set(
    "zio/test/DefaultRunnableSpec#",
    "zio/test/RunnableSpec#",
    "zio/test/ZIOSpecDefault#",
  )
}
