package scala.meta.internal.metals

import java.util.Collections

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.MetalsEnrichments._

import ch.epfl.scala.bsp4j
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.{lsp4j => l}

object ScalacDiagnostic {

  val gson: Gson =
    new MessageJsonHandler(Collections.emptyMap()).getGson

  sealed trait DiagnosticData
  object DiagnosticData {

    final case class LegacyTextEdit(textEdit: l.TextEdit) extends DiagnosticData

    final case class BspActions(diagnostic: bsp4j.ScalaDiagnostic)
        extends DiagnosticData

    final case class PcActions(actions: Seq[l.CodeAction])
        extends DiagnosticData

    def unapply(d: l.Diagnostic): Option[DiagnosticData] =
      d.getData() match {
        case obj: JsonObject if obj.has("actions") =>
          decode(obj, classOf[bsp4j.ScalaDiagnostic]).map(BspActions(_))
        case obj: JsonObject =>
          decode(obj, classOf[l.TextEdit]).map(LegacyTextEdit(_))
        case arr: JsonArray =>
          Some(
            PcActions(
              arr.asScala.flatMap(decode(_, classOf[l.CodeAction])).toSeq
            )
          )
        case null => None
        case other =>
          scribe.warn(
            s"Unexpected diagnostic data type: ${other.getClass().getName()}, data: $other"
          )
          None
      }

    private def decode[A](json: JsonElement, cls: Class[A]): Option[A] =
      Try(gson.fromJson(json, cls)) match {
        case Success(value) => Option(value)
        case Failure(error) =>
          scribe.error(s"Failed to parse diagnostic data: $json", error)
          None
      }
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
