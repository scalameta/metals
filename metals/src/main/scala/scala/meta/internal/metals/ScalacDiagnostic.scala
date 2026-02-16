package scala.meta.internal.metals

import scala.meta.internal.metals.MetalsEnrichments._

import ch.epfl.scala.bsp4j
import org.eclipse.{lsp4j => l}

object ScalacDiagnostic {

  object LegacyScalaAction {
    def unapply(d: l.Diagnostic): Option[l.TextEdit] = d.asTextEdit
  }

  object ScalaDiagnostic {
    def unapply(
        d: l.Diagnostic
    ): Option[Either[l.TextEdit, bsp4j.ScalaDiagnostic]] =
      d.asScalaDiagnostic
  }

  object NotAMember {
    private val regex = """(?s)value (.+) is not a member of.*""".r
    def unapply(d: l.Diagnostic): Option[String] =
      d.getMessageAsString.trim() match {
        case regex(name) => Some(name)
        case _ => None
      }
  }

  object SymbolNotFound {
    private val regex =
      """(n|N)ot found: (value|type|object)?\s?(\w+)(\s|\S)*""".r
    def unapply(d: l.Diagnostic): Option[String] =
      d.getMessageAsString.trim() match {
        case regex(_, _, name, _) => Some(name)
        case _ => None
      }
  }

  object TypeMismatch {
    private val regexStart = """type mismatch;""".r
    private val regexMiddle = """(F|f)ound\s*: (.*)""".r
    private val regexEnd = """(R|r)equired: (.*)""".r

    def unapply(d: l.Diagnostic): Option[(String, l.Diagnostic)] = {
      d.getMessageAsString.split("\n").map(_.trim()) match {
        /* Scala 3:
         * Found:    ("" : String)
         * Required: Int
         */
        case Array(regexMiddle(_, toType), regexEnd(_, _)) =>
          Some((toType.trim(), d))
        /* Scala 2:
         * type mismatch;
         * found   : Int(122)
         * required: String
         */
        case Array(regexStart(), regexMiddle(_, toType), regexEnd(_, _)) =>
          Some((toType.trim(), d))
        case _ =>
          None
      }
    }
  }

  object MissingImplementation {
    // https://github.com/scala/scala/blob/fd69ef805d4ba217f3495c106f9c698094682ae8/src/compiler/scala/tools/nsc/typechecker/RefChecks.scala#L547
    private val regex = """(?s).*needs to be abstract.*""".r
    def unapply(d: l.Diagnostic): Option[String] =
      d.getMessageAsString.trim() match {
        case regex() => Some(d.getMessageAsString)
        case _ => None
      }
  }
  object ObjectCreationImpossible {
    // https://github.com/scala/scala/blob/4c0f49c7de6ba48f2b0ae59e64ea94fabd82b4a7/src/compiler/scala/tools/nsc/typechecker/RefChecks.scala#L566
    private val regex = """(?s).*object creation impossible.*""".r
    def unapply(d: l.Diagnostic): Option[String] =
      d.getMessageAsString.trim() match {
        case regex() => Some(d.getMessageAsString)
        case _ => None
      }
  }

  object UnusedImport {
    private val regex = """(?i)Unused import""".r
    def unapply(d: l.Diagnostic): Option[String] =
      d.getMessageAsString.trim() match {
        case regex() => Some(d.getMessageAsString)
        case _ => None
      }
  }

  object DeclarationOfGivenInstanceNotAllowed {
    private val regex =
      """Declaration of given instance given_.+ not allowed here.*""".r
    def unapply(d: l.Diagnostic): Option[String] =
      d.getMessageAsString.trim() match {
        case regex() => Some(d.getMessageAsString)
        case _ => None
      }
  }

  object ObjectNotAMemberOfPackage {
    private val regex = """(?s)object (.+) is not a member of package.*""".r
    def unapply(d: l.Diagnostic): Option[String] =
      d.getMessageAsString.trim() match {
        case regex(name) => Some(name)
        case _ => None
      }
  }
}
