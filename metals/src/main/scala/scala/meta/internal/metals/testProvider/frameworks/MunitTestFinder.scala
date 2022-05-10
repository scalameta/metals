package scala.meta.internal.metals.testProvider.frameworks

import scala.annotation.tailrec
import scala.collection.immutable
import scala.collection.mutable

import scala.meta.Defn
import scala.meta.Lit
import scala.meta.Pkg
import scala.meta.Template
import scala.meta.Term
import scala.meta.Tree
import scala.meta.Type
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.testProvider.FullyQualifiedName
import scala.meta.internal.metals.testProvider.TestCaseEntry
import scala.meta.internal.mtags
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.ClassSignature
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TypeRef
import scala.meta.io.AbsolutePath

class MunitTestFinder(
    trees: Trees,
    symbolIndex: GlobalSymbolIndex,
    semanticdbs: Semanticdbs
) {

  // depending on the munit version test method symbol varies.
  private val baseParentClasses = Set("munit/BaseFunSuite#", "munit/FunSuite#")
  private val testMethodSymbols = baseParentClasses.map(_ + "test")

  /**
   * Find test cases for the given suite.
   * Search includes helper methods which may be defined in parent classes.
   * @param doc semanticDB which contains
   * @param symbol semanticDB symbol of the suite
   * @return Vector of test case entries
   */
  def findTests(
      doc: TextDocument,
      path: AbsolutePath,
      suiteName: FullyQualifiedName,
      symbol: mtags.Symbol
  ): Vector[TestCaseEntry] = {
    val uri = path.toURI
    val parentMethods = extractTestMethodsFromParents(doc, symbol.value)
    // direct 'test()' occurences
    val occurences = filterOccurences(doc)

    trees
      .get(path)
      .flatMap(tree => extractTemplateFrom(tree, suiteName.value))
      .map { template =>
        val potentialTests =
          extractPotentialTestMethods(template, occurences) ++ parentMethods

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
        val testcases = new mutable.ArrayBuffer[TestCaseEntry]()
        template.children.foreach {
          // test("testname".only|ignore|tag) {}
          case appl: Term.Apply if hasTestCall(appl, occurences) =>
            getTestCallWithTestName(appl).foreach { case (test, testname) =>
              val location = test.pos.toLSP.toLocation(uri)
              val entry = TestCaseEntry(testname.value, location)
              testcases.addOne(entry)
            }

          // helper_function("testname", ...) where helper_function was previously found as a potential test function
          case appl: Term.Apply =>
            val nameOpt = extractFunctionName(appl)
            nameOpt.foreach { case (helperFunction, testName) =>
              val location = helperFunction.pos.toLSP.toLocation(uri)
              val entry = TestCaseEntry(testName, location)
              testcases.addOne(entry)
            }

          case _ => ()
        }
        testcases.toVector
      }
      .getOrElse(Vector.empty)
  }

  /**
   * Extract helper methods from ALL class parents. In order to do that we need
   * to get both Tree and semanticDB of parent class.
   * It works recursively, for A where (-> means inherits from) A -> B -> C,
   * both B and C (recursively called when computing B) will be examined.
   * @param doc semanticDB of class represented by classSymbol
   * @param classSymbol symbol of the given class
   * @return all potential helper methods from all class parents
   */
  private def extractTestMethodsFromParents(
      doc: TextDocument,
      classSymbol: String
  ): Set[String] = {
    // format: off
    // semanticDB contains information about DIRECT parent classes of suite
    val parents = doc.symbols
      .collectFirst {
        case SymbolInformation(symbolValue, _, _, _, _, sig: ClassSignature, _, _, _, _) if symbolValue == classSymbol =>
          sig.parents.collect { 
            case TypeRef(_, parentSymbol, _) if !baseParentClasses.contains(parentSymbol) => parentSymbol
          }.toVector
      }
      .getOrElse(Vector.empty)
    // format: on

    // call recursively extractTestMethodsFromParents
    def fromSingleParent(parentSymbol: String) = {
      val methods = for {
        definition <- symbolIndex.definition(mtags.Symbol(parentSymbol))
        tree <- trees.get(definition.path)
        doc <- semanticdbs.textDocument(definition.path).documentIncludingStale
        parentClassName = parentSymbol
          .stripPrefix("_empty_/")
          .stripSuffix("#")
          .replace("/", ".")
        template <- extractTemplateFrom(tree, parentClassName)
      } yield {
        val occurences = filterOccurences(doc)
        val current = extractPotentialTestMethods(template, occurences)
        val fromParents = extractTestMethodsFromParents(doc, parentSymbol)
        current ++ fromParents
      }
      methods.getOrElse(Set.empty)
    }

    parents.flatMap(fromSingleParent).toSet
  }

  /**
   * Leave only occurences which are connected to the munit test method.
   * Depending on the munit version, test method is defined in different classes.
   */
  private def filterOccurences(doc: TextDocument): Vector[SymbolOccurrence] =
    doc.occurrences
      .filter(occ =>
        testMethodSymbols.exists(testSymbol =>
          occ.symbol.startsWith(testSymbol)
        )
      )
      .toVector

  /**
   * Class definition is valid when package + class name is equal to one we are looking for
   */
  private def isValid(
      name: Type.Name,
      currentPackage: Vector[String],
      searched: String
  ): Boolean = {
    val fullyQualifiedName = currentPackage.appended(name.value).mkString(".")
    fullyQualifiedName == searched
  }

  /**
   * Extract class/trait template from the given Tree.
   * @param tree Tree which may contain Template
   * @param fullyQualifiedName fully qualified class name of class/trait
   * @return Template of a given class if present
   */
  private def extractTemplateFrom(
      tree: Tree,
      fullyQualifiedName: String
  ): Option[Template] = {

    /**
     * Search loop with short circuiting when first matching result is obtained.
     */
    def loop(
        t: Tree,
        currentPackage: Vector[String]
    ): Option[Template] = {
      t match {
        case cls: Defn.Class
            if isValid(cls.name, currentPackage, fullyQualifiedName) =>
          Some(cls.templ)
        case trt: Defn.Trait
            if isValid(trt.name, currentPackage, fullyQualifiedName) =>
          Some(trt.templ)
        // short-circuit to not go deeper into unuseful defns
        case _: Defn => None
        case Pkg(ref, children) =>
          val pkg = extractPackageName(ref)
          val newPackage = currentPackage ++ pkg
          LazyList
            .from(children)
            .map(loop(_, newPackage))
            .find(_.isDefined)
            .flatten
        case _ =>
          LazyList
            .from(t.children)
            .map(loop(_, currentPackage))
            .find(_.isDefined)
            .flatten
      }
    }

    loop(tree, Vector.empty)
  }

  /**
   * Find test call (Term.Name("test")) and test name (Lit.String(...)) in a given tree.
   *
   * e.g.
   * test("test-1") {...} is equal to the following tree and interesting part
   * is marked by caret symbols
   * Apply( Apply( Name("test", Lit.String("test-1") ) ), Block(...))
   *               ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
   *
   * However, for curried functions applies are more nested:
   * test("test-2")(1) {...}
   * Apply( Apply( Apply(Name("test", Lit.String("test-2")), ... ), Block(...) )
   *                     ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
   *
   * Last case are calls like test("test3".ignore) {} when searched tree has no longer shape of
   * Apply(Name, Lit.String) but rather Apply(Name, Select("test3", Name("ignore")))
   * Apply( Apply(Name("test"), Select("test3", Name("ignore"))), Block(...) )
   *              ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
   */
  private def getTestCallWithTestName(
      tree: Tree
  ): Option[(Term.Name, Lit.String)] = {

    @tailrec
    def extractLiteralName(acc: List[Tree]): Option[Lit.String] = acc match {
      case head :: tail =>
        head match {
          case lit: Lit.String => Some(lit)
          case _ => extractLiteralName(head.children ::: tail ::: acc)
        }
      case immutable.Nil => None
    }

    @tailrec
    // find proper Apply in possibly multiple nested Applies
    def loop(acc: List[Tree]): Option[(Term.Name, Lit.String)] = acc match {
      case head :: tail =>
        head match {
          case Term.Apply(term @ Term.Name("test"), args) =>
            extractLiteralName(args) match {
              case Some(lit) =>
                Some((term, lit))
              case None => loop(tail)
            }
          case _ => loop(head.children ::: tail ::: acc)
        }
      case immutable.Nil => None
    }

    loop(tree.children)
  }

  /**
   * In munit, it's very popular to define helper method for tests
   * which prevents from code duplication. These method often looks like:
   * def check(name: String, ...) = {
   *   test(name) {
   *     <test logic>
   *   }
   * }
   * Extract potential test methods from a given tree, provided that test call
   * is verified using semanticdb.
   */
  private def extractPotentialTestMethods(
      clsTemplate: Template,
      occurences: Vector[SymbolOccurrence]
  ): Set[String] = clsTemplate.children.collect {
    case dfn: Defn.Def if hasTestCall(dfn, occurences) => dfn.name.value
  }.toSet

  /**
   * Check if the given tree contains a test call.
   * Test call is valid when there is test symbol with given range in semanticDB.
   */
  private def hasTestCall(
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
              .exists(occ => occ.range.exists(_.isEqual(range)))
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
