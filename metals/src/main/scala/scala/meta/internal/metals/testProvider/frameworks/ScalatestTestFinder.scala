package scala.meta.internal.metals.testProvider.frameworks

import scala.meta.Lit
import scala.meta.Stat
import scala.meta.Template
import scala.meta.Term
import scala.meta.Tree
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.testProvider.FullyQualifiedName
import scala.meta.internal.metals.testProvider.TestCaseEntry
import scala.meta.internal.metals.testProvider.frameworks.ScalatestStyle._
import scala.meta.internal.metals.testProvider.frameworks.ScalatestTestFinder._
import scala.meta.internal.metals.testProvider.frameworks.TreeUtils
import scala.meta.internal.mtags
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.ClassSignature
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TypeRef
import scala.meta.io.AbsolutePath

/**
 * Scalatest finder searches for tests based solely on trees.
 */
class ScalatestTestFinder(
    trees: Trees,
    symbolIndex: mtags.GlobalSymbolIndex,
    semanticdbs: mtags.Semanticdbs,
) {

  def findTests(
      doc: TextDocument,
      path: AbsolutePath,
      suiteName: FullyQualifiedName,
      symbol: mtags.Symbol,
  ): Seq[TestCaseEntry] = {
    val result = for {
      tree <- trees.get(path)
      style <- inferScalatestStyle(doc, symbol)
    } yield findTestLocations(path, style, tree, suiteName)

    result.getOrElse(Vector.empty)
  }

  /**
   * Infer first matching scalatest style for test class.
   * Implementation searches in direct parents and if it's not successful it performs lazy dfs search.
   *
   * @param doc semanticDB of file in which test class is located
   * @param classSymbol symbol of class for which method should infer style
   */
  private def inferScalatestStyle(
      doc: TextDocument,
      classSymbol: mtags.Symbol,
  ): Option[ScalatestStyle] = {
    val parents = doc.symbols.collect {
        // format: off
        case SymbolInformation(symbolValue, _, _, _, _, sig: ClassSignature, _, _, _, _) if symbolValue == classSymbol.value =>
        // format: on
        sig.parents
          .collect { case TypeRef(_, parentSymbol, _) => parentSymbol }
    }.flatten

    def lazySearch = LazyList.from(parents).collect {
      case parentSymbol if !ScalatestStyle.baseSymbols.contains(parentSymbol) =>
        for {
          definition <- symbolIndex.definition(mtags.Symbol(parentSymbol))
          tree <- trees.get(definition.path)
          doc <- semanticdbs
            .textDocument(definition.path)
            .documentIncludingStale
          style <- inferScalatestStyle(doc, mtags.Symbol(parentSymbol))
        } yield style
    }

    parents
      .collectFirst { case ScalatestStyle(style) =>
        style
      }
      .orElse(lazySearch.find(_.isDefined).flatten)
  }
}

object ScalatestTestFinder {
  def findTestLocations(
      path: AbsolutePath,
      style: ScalatestStyle,
      tree: Tree,
      suiteName: FullyQualifiedName,
  ): Seq[TestCaseEntry] =
    TreeUtils.extractTemplateFrom(tree, suiteName.value) match {
      case Some(template) =>
        style match {
          case AnyFunSuite | AnyPropSpec =>
            findAnyFunSuiteTests(path, template, style)

          case AnyWordSpec | AnyFreeSpec =>
            findAnyWordSpecTests(path, template, style)

          case AnyFlatSpec =>
            findAnyFlatSpecTests(path, template)

          case AnyFunSpec =>
            findAnyFunSpecTests(path, template)
        }
      case None =>
        Nil
    }

  private def findAnyFunSuiteTests(
      path: AbsolutePath,
      template: Template,
      style: ScalatestStyle,
  ): List[TestCaseEntry] = {
    // collect all entries like test("testname") { ... }
    template.stats.collect {
          // format: off
          case Term.Apply(appl @ Term.Apply(Term.Name(funName), Lit.String(testname) :: _), _) 
              if style.leafMethods.contains(funName) =>
          // format: on
        TestCaseEntry(testname, appl.pos.toLsp.toLocation(path.toURI))
    }
  }

  /**
   * AnyWordSpec can contain multiple "descriptions" like "A Set" when { "empty" should { "real test" in { ... } } }
   */
  private def findAnyWordSpecTests(
      path: AbsolutePath,
      template: Template,
      style: ScalatestStyle,
  ): List[TestCaseEntry] = {

    def loop(
        stats: List[Stat],
        namePrefix: List[String],
        acc: List[TestCaseEntry],
    ): List[TestCaseEntry] =
      stats.flatMap {
        // format: off
        // gather intermediate name parts
        case Term.ApplyInfix(Lit.String(lhs), Term.Name(infixOp), _, List(Term.Block(stats))) 
          if style.intermediateMethods.contains(infixOp) =>
        // format: on
          val newNamePrefix =
            if (infixOp != "-") namePrefix :+ lhs :+ infixOp
            else namePrefix :+ lhs
          loop(stats, newNamePrefix, acc)

        // format: off
        // gather leaf name part and collect test entry
        case Term.ApplyInfix(lhs: Lit.String, Term.Name(infixOp), _, _) 
          if style.leafMethods.contains(infixOp) => 
        // format: on
          val testname = namePrefix.appended(lhs.value).mkString(" ")
          TestCaseEntry(testname, lhs.pos.toLsp.toLocation(path.toURI)) :: acc

        case _ =>
          acc
      }

    loop(template.stats, Nil, Nil)
  }

  /**
   * AnyFlatSpec can start with optional name prefix which will be substituted for all subsequent "it" calls.
   */
  private def findAnyFlatSpecTests(
      path: AbsolutePath,
      template: Template,
  ): List[TestCaseEntry] = {
    val (result, _) = template.stats.foldLeft(
      (List.empty[TestCaseEntry], Option.empty[String])
    ) { case ((acc, namePrefix), stat) =>
      stat match {
        // format: off
        // "An empty Set" should "have size 0" in { ... }
        case Term.ApplyInfix(appl @ Term.ApplyInfix(Lit.String(newPrefix), Term.Name(infixOp), _, List(Lit.String(right))), _: Term.Name, _, _) => 
        // format: on
          val testname = s"$newPrefix $infixOp $right"
          val test =
            TestCaseEntry(testname, appl.pos.toLsp.toLocation(path.toURI))
          (test :: acc, Some(newPrefix))

        // format: off
        // it should "have size 0" in { ... } - replace it with encountered name or leave empty
        case Term.ApplyInfix(appl @ Term.ApplyInfix(Term.Name("it"), Term.Name(infixOp), _, List(Lit.String(right))), _: Term.Name, _, _) => 
        // format: on
          val prefix = namePrefix.fold("")(_ + " ")
          val testname = s"$prefix$infixOp $right"
          val test =
            TestCaseEntry(testname, appl.pos.toLsp.toLocation(path.toURI))
          (test :: acc, namePrefix)
        case _ => (acc, namePrefix)
      }
    }
    result
  }

  /**
   * Similar to the AnyWordSpec, but it's not a infix style
   * describe("A Set") { describe("when empty") { it("should have size 0") { ... } } }
   */
  private def findAnyFunSpecTests(
      path: AbsolutePath,
      template: Template,
  ): List[TestCaseEntry] = {

    def loop(
        stats: List[Stat],
        sharedPrefix: List[String],
        acc: List[TestCaseEntry],
    ): List[TestCaseEntry] = {
      lazy val prefix = sharedPrefix.mkString(" ")
      stats.flatMap {
        // format: off
        // gather name part from describe
        case Term.Apply(Term.Apply(Term.Name("describe"), (prefix: Lit.String) :: Nil), (block: Term.Block) :: Nil) => 
        // format: on
          (acc, sharedPrefix :+ prefix.value)
          loop(block.stats, sharedPrefix :+ prefix.value, acc)

        // format: off
        // collect test entry from it
        case Term.Apply(appl @ Term.Apply(Term.Name("it"), (name: Lit.String) :: Nil), _) => 
        // format: on
          val testname = s"$prefix ${name.value}"
          val test =
            TestCaseEntry(testname, appl.pos.toLsp.toLocation(path.toURI))
          test :: acc

        case _ =>
          acc
      }
    }
    loop(template.stats, Nil, Nil)
  }

}

sealed trait ScalatestStyle {
  def symbols: Set[String]
  def intermediateMethods: Set[String] = Set.empty
  def leafMethods: Set[String] = Set.empty
}

object ScalatestStyle {
  private val styles =
    Vector(
      AnyFunSuite,
      AnyPropSpec,
      AnyFlatSpec,
      AnyFunSpec,
      AnyWordSpec,
      AnyFreeSpec,
    )

  val baseSymbols: Set[String] = styles.flatMap(_.symbols).toSet

  def unapply(symbol: String): Option[ScalatestStyle] =
    styles.find(_.symbols.contains(symbol))

  case object AnyFunSuite extends ScalatestStyle {
    val symbols: Set[String] = Set(
      "org/scalatest/funsuite/AnyFunSuite#",
      "org/scalatest/funsuite/AnyFunSuiteLike#",
    )
    override val leafMethods: Set[String] = Set("test")
  }

  case object AnyPropSpec extends ScalatestStyle {
    val symbols: Set[String] = Set(
      "org/scalatest/propspec/AnyPropSpec#",
      "org/scalatest/propspec/AnyPropSpecLike#",
    )
    override val leafMethods: Set[String] = Set("property")
  }

  case object AnyFlatSpec extends ScalatestStyle {
    val symbols: Set[String] = Set(
      "org/scalatest/flatspec/AnyFlatSpec#",
      "org/scalatest/flatspec/AnyFlatSpecLike#",
    )
  }

  case object AnyFunSpec extends ScalatestStyle {
    val symbols: Set[String] = Set(
      "org/scalatest/funspec/AnyFunSpec#",
      "org/scalatest/funspec/AnyFunSpecLike#",
    )
  }

  case object AnyWordSpec extends ScalatestStyle {
    val symbols: Set[String] = Set(
      "org/scalatest/wordspec/AnyWordSpec#",
      "org/scalatest/wordspec/AnyWordSpecLike#",
    )
    override val intermediateMethods: Set[String] =
      Set("when", "should", "must", "can", "which")
    override val leafMethods: Set[String] = Set("in")
  }

  case object AnyFreeSpec extends ScalatestStyle {
    val symbols: Set[String] = Set(
      "org/scalatest/freespec/AnyFreeSpec#",
      "org/scalatest/freespec/AnyFreeSpecLike#",
    )
    override val intermediateMethods: Set[String] = Set("-")
    override val leafMethods: Set[String] = Set("in")
  }

}
