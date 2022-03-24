package scala.meta.internal.metals.testProvider.frameworks

import scala.annotation.tailrec
import scala.collection.immutable
import scala.collection.mutable

import scala.meta.Defn
import scala.meta.Lit
import scala.meta.Pkg
import scala.meta.Term
import scala.meta.Tree
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.testProvider.FullyQualifiedName
import scala.meta.internal.metals.testProvider.TestCaseEntry
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

class MunitTestFinder(trees: Trees) {

  def findTests(
      doc: TextDocument,
      path: AbsolutePath,
      suiteName: FullyQualifiedName
  ): Vector[TestCaseEntry] = {
    val uri = path.toURI
    val testcases = new mutable.ArrayBuffer[TestCaseEntry]()
    val occurences = doc.occurrences
      .filter(occ =>
        occ.symbol.startsWith("munit/") && occ.symbol.contains("test")
      )
      .toVector

    /**
     * Class definition is valid when package + class name is equal to one we are looking for
     */
    def isValid(cls: Defn.Class, currentPackage: String): Boolean =
      s"$currentPackage.${cls.name.value}" == suiteName.value

    def loop(tree: Tree, currentPackage: Vector[String]): Unit = {
      val pkgString = currentPackage.mkString(".")
      tree match {
        case cls: Defn.Class if isValid(cls, pkgString) =>
          /**
           * In munit, it's very popular to define helper method for tests
           * which prevents from code duplication. These method often looks like:
           * def check(name: String, ...) = {
           *   test(name) {
           *     <test logic>
           *   }
           * }
           * Finding these potential test methods will allow to show them to the user.
           */
          val potentialTests = cls.templ.children.collect {
            case dfn: Defn.Def if hasTestCall(dfn, occurences) => dfn.name.value
          }.toSet

          def extractFunctionName(
              appl0: Term.Apply
          ): Option[(Term.Name, String)] =
            appl0.fun match {
              case helperName: Term.Name
                  if potentialTests.contains(helperName.value) =>
                appl0.args
                  .collectFirst { case Lit.String(value) => value }
                  .map(testName => (helperName, testName))
              case appl: Term.Apply => extractFunctionName(appl)
              case _ => None
            }

          // let's collect all tests candidates
          cls.templ.children.collect {
            // test("test1") {}
            case Term.Apply(
                  Term.Apply(
                    test @ Term.Name("test"),
                    List(Lit.String(testname))
                  ),
                  _
                ) =>
              val location = test.pos.toLSP.toLocation(uri)
              val entry = TestCaseEntry(testname, location)
              testcases.addOne(entry)

            // test("test2".ignore) {}
            case Term.Apply(
                  Term.Apply(
                    test @ Term.Name("test"),
                    List(Term.Select(Lit.String(testname), _: Term.Name))
                  ),
                  _
                ) =>
              val location = test.pos.toLSP.toLocation(uri)
              val entry = TestCaseEntry(testname, location)
              testcases.addOne(entry)

            // test("test2".tag(new Tag("aezkami"))) {}
            case Term.Apply(
                  Term.Apply(
                    test @ Term.Name("test"),
                    List(
                      Term.Apply(
                        Term.Select(Lit.String(testname), _: Term.Name),
                        _
                      )
                    )
                  ),
                  _
                ) =>
              val location = test.pos.toLSP.toLocation(uri)
              val entry = TestCaseEntry(testname, location)
              testcases.addOne(entry)

            // helper_function("testname", ...) where helper_function was previously found as a potential test function
            case appl: Term.Apply =>
              val nameOpt = extractFunctionName(appl)
              nameOpt.foreach { case (helperFunction, testName) =>
                val location = helperFunction.pos.toLSP.toLocation(uri)
                val entry = TestCaseEntry(testName, location)
                testcases.addOne(entry)
              }

          }

        case Pkg(ref, children) =>
          val pkg = extractPackageName(ref)
          children.foreach(loop(_, currentPackage ++ pkg))

        case _ =>
          tree.children.foreach(loop(_, currentPackage))
      }
    }

    trees
      .get(path)
      .map { tree =>
        loop(tree, Vector.empty)
        testcases.toVector

      }
      .getOrElse(Vector.empty)
  }

  def hasTestCall(
      tree: Tree,
      occurences: Vector[SymbolOccurrence]
  ): Boolean = {

    @tailrec
    def loop(acc: List[Tree]): Boolean = acc match {
      case head :: tail =>
        head match {
          case term @ Term.Name("test") =>
            val range = term.pos.toSemanticdb
            val isValid = occurences
              .find(occ => occ.range.exists(_.isEqual(range)))
              .isDefined
            if (isValid) isValid
            else loop(tail)
          case _ => loop(head.children ::: tail)
        }
      case immutable.Nil => false
    }
    loop(tree.children)
  }

  /**
   * Extract package name from given Term
   *
   * package a => Term.Name(a)
   * package a.b.c => Term.Select(Term.Select(a, b), c) (Term.Name are omitted)
   */
  @tailrec
  private def extractPackageName(
      term: Term,
      acc: List[String] = Nil
  ): Vector[String] =
    term match {
      case Term.Name(value) => (value :: acc).toVector
      case Term.Select(qual, Term.Name(value)) =>
        extractPackageName(qual, value :: acc)
      case _ => Vector.empty
    }

}

object MunitTestFinder {}
