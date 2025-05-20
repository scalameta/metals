package tests

import scala.collection.mutable
import scala.util.control.NonFatal

/**
 * Entry point to save/update expect files for snapshot tests.
 *
 * == Usage ==
 *
 * Run all expect suites:
 * {{{
 * coursier launch sbt -- --client "unit/Test/runMain tests.SaveExpect"
 * }}}
 *
 * Run a specific suite (filter by suite name, case-insensitive):
 * {{{
 * coursier launch sbt -- --client "unit/Test/runMain tests.SaveExpect JavacMtagsExpectSuite"
 * }}}
 *
 * You can also filter by the directory name (suiteName for DirectoryExpectSuite):
 * {{{
 * coursier launch sbt -- --client "unit/Test/runMain tests.SaveExpect mtags-protobuf-v2"
 * }}}
 *
 * == How to Add a New Expect Test Suite ==
 *
 * 1. Create a new test class extending `DirectoryExpectSuite`:
 *    {{{
 *    class MyNewExpectSuite extends DirectoryExpectSuite("my-expect-dir") {
 *      override lazy val input: InputProperties = InputProperties.fromDirectory(
 *        AbsolutePath(BuildInfo.testResourceDirectory).resolve("my-input-dir")
 *      )
 *      def testCases(): List[ExpectTestCase] = for {
 *        file <- input.allFiles
 *        if file.file.toNIO.toString.endsWith(".myext")
 *      } yield ExpectTestCase(file, () => computeOutput(file))
 *    }
 *    }}}
 *
 * 2. Create input files in `tests/unit/src/test/resources/my-input-dir/`
 *
 * 3. Add the suite loader to the list below:
 *    {{{
 *    () => new MyNewExpectSuite,
 *    }}}
 *
 * 4. Run `SaveExpect` to generate expect files in `tests/unit/src/test/resources/my-expect-dir/`
 *
 * 5. Commit both input and expect files
 */
object SaveExpect {
  def main(args: Array[String]): Unit = {
    val filter = args.headOption.map(_.toLowerCase).getOrElse("")
    val errorSuites = mutable.ListBuffer.empty[String]
    for {
      loader <- List[() => BaseExpectSuite](
        () => new JavacMtagsExpectSuite(assertCompat = false),
        () => new ProtobufMtagsExpectSuite,
        () => new ProtobufMtagsV2ExpectSuite,
        () => new DefinitionScala2Suite,
        () => new DefinitionScala3Suite,
        () => new SemanticdbScala2Suite,
        () => new SemanticdbScala3Suite,
        () => new MtagsScala2Suite,
        () => new MtagsScala3Suite,
        () => new ToplevelsScala2Suite,
        () => new ToplevelsScala3Suite,
        () => new ToplevelWithInnerScala3Suite,
        () => new ToplevelWithInnerScala2Suite,
        () => new DocumentSymbolScala2Suite,
        () => new DocumentSymbolScala3Suite,
        () => new FoldingRangeScala2Suite,
        () => new FoldingRangeScala3Suite,
        () => new WorkspaceSymbolExpectSuite,
        () => new SemanticTokensExpectSuite,
        () => new inlayHints.InlayHintsExpectSuite,
      )
      suite = loader()
      // Uncomment to debug if a class is too slow to load.  This happens if a
      // class does heavy work inside the `testCases()` method.
      // _ = pprint.log(suite.suiteName)
      if filter.isEmpty || suite.suiteName.toLowerCase == filter
    } {
      val header = suite.suiteName.length + 2
      println("=" * header)
      println("= " + suite.suiteName)
      println("=" * header)
      try {
        suite.saveExpect()
        suite.afterAll()
      } catch {
        case NonFatal(e) =>
          errorSuites += suite.suiteName
          scribe.error(suite.suiteName, e)
      }
    }
    if (errorSuites.nonEmpty) {
      scribe.error("Tests failed: " + errorSuites.mkString(", "))
      System.exit(1)
    }
  }
}
