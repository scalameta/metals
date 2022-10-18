package scala.meta.internal.metals

import scala.meta.internal.metals.MetalsEnrichments._

import org.eclipse.{lsp4j => l}

object ScalacDiagnostic {

  object ScalaAction {
    def unapply(d: l.Diagnostic): Option[l.TextEdit] = d.asTextEdit
  }

  object NotAMember {
    private val regex = """(?s)value (.+) is not a member of.*""".r
    def unapply(d: l.Diagnostic): Option[String] =
      d.getMessage() match {
        case regex(name) => Some(name)
        case _ => None
      }
  }

  object SymbolNotFound {
    private val regex = """(n|N)ot found: (value|type)?\s?(\w+)""".r
    def unapply(d: l.Diagnostic): Option[String] =
      d.getMessage() match {
        case regex(_, _, name) => Some(name)
        case _ => None
      }
  }

  object TypeMismatch {
    private val regexStart = """type mismatch;""".r
    private val regexMiddle = """(F|f)ound\s*: (.*)""".r
    private val regexEnd = """(R|r)equired: (.*)""".r

    def unapply(d: l.Diagnostic): Option[(String, l.Diagnostic)] = {
      d.getMessage().split("\n").map(_.trim()) match {
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
      d.getMessage() match {
        case regex() => Some(d.getMessage())
        case _ => None
      }
  }
  object ObjectCreationImpossible {
    // https://github.com/scala/scala/blob/4c0f49c7de6ba48f2b0ae59e64ea94fabd82b4a7/src/compiler/scala/tools/nsc/typechecker/RefChecks.scala#L566
    private val regex = """(?s).*object creation impossible.*""".r
    def unapply(d: l.Diagnostic): Option[String] =
      d.getMessage() match {
        case regex() => Some(d.getMessage())
        case _ => None
      }
  }

  object UnusedImport {
    private val regex = """(?i)Unused import""".r
    def unapply(d: l.Diagnostic): Option[String] =
      d.getMessage() match {
        case regex() => Some(d.getMessage())
        case _ => None
      }
  }

  object DeclarationOfGivenInstanceNotAllowed {
    private val regex =
      """Declaration of given instance given_.+ not allowed here.*""".r
    def unapply(d: l.Diagnostic): Option[String] =
      d.getMessage() match {
        case regex() => Some(d.getMessage())
        case _ => None
      }
  }
}
