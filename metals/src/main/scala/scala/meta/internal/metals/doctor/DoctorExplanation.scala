package scala.meta.internal.metals.doctor

import scala.meta.internal.metals.HtmlBuilder
import scala.meta.internal.metals.Icons

import ujson.Obj

/**
 * Various explanations to help explain the various sections of a Build Target
 * in the Doctor.
 */
sealed trait DoctorExplanation {

  /**
   * Title of the explanation
   */
  def title: String

  /**
   * Message explaining what support for this means.
   */
  def correctMessage: String

  /**
   * Message explaining what you'll be missing if this is incorrect.
   */
  def incorrectMessage: String

  /**
   * Whether or not the incorrect message needs to be shown along with he
   * correct one.
   *
   * @param allTargetsInfo
   */
  def show(allTargetsInfo: Seq[DoctorTargetInfo]): Boolean

  /**
   * Takes in a builder and returns a builder with the explanations attached.
   *
   * @param html The builder your're working with.
   * @param allTargetsInfo
   */
  def toHtml(
      html: HtmlBuilder,
      allTargetsInfo: Seq[DoctorTargetInfo],
  ): HtmlBuilder =
    explanation(
      html,
      title,
      correctMessage,
      incorrectMessage,
      show(allTargetsInfo),
    )

  /**
   * Take the results and return them in Json
   *
   * @param allTargetsInfo
   * @return
   */
  def toJson(allTargetsInfo: Seq[DoctorTargetInfo]): Obj =
    explanation(
      title,
      correctMessage,
      incorrectMessage,
      show(allTargetsInfo),
    )

  /**
   * Take the values and creates an html explanation for them.
   */
  def explanation(
      html: HtmlBuilder,
      title: String,
      correctMessage: String,
      incorrectMessage: String,
      show: Boolean,
  ): HtmlBuilder = {
    html.element("div")(
      _.element("p")(_.text(title)).element("ul") { ul =>
        ul.element("li")(_.text(correctMessage))
        if (show)
          incorrectMessage.linesIterator.foreach { legend =>
            ul.element("li")(_.text(legend))
          }
      }
    )

  }

  /**
   * Take the values and creates a json explanation.
   */
  def explanation(
      title: String,
      correctMessage: String,
      incorrectMessage: String,
      show: Boolean,
  ): Obj = {
    val explanations =
      if (show)
        List(correctMessage) ++ incorrectMessage.split("\n")
      else
        List(correctMessage)

    ujson.Obj(
      "title" -> title,
      "explanations" -> explanations,
    )
  }
}

object DoctorExplanation {

  case object CompilationStatus extends DoctorExplanation {
    val title = "Compilation status:"
    val correctMessage: String =
      s"${Icons.unicode.check} - code is compiling"
    val incorrectMessage: String =
      s"${Icons.unicode.error} - code isn't compiling, open problems tab to see detailed compilation errors"

    def show(allTargetsInfo: Seq[DoctorTargetInfo]): Boolean =
      allTargetsInfo.exists(_.compilationStatus.isCorrect == false)
  }
  case object Diagnostics extends DoctorExplanation {
    val title = "Diagnostics:"
    val correctMessage: String =
      s"${Icons.unicode.check} - diagnostics correctly being reported by the build server"
    val incorrectMessage: String =
      s"${Icons.unicode.alert} - only syntactic errors being reported"

    def show(allTargetsInfo: Seq[DoctorTargetInfo]): Boolean =
      allTargetsInfo.exists(_.diagnosticsStatus.isCorrect == false)
  }

  case object Interactive extends DoctorExplanation {
    val title = "Interactive features (completions, hover):"
    val correctMessage: String =
      s"${Icons.unicode.check} - supported Scala version"
    val incorrectMessage: String =
      s"${Icons.unicode.error} - interactive features are unsupported for Java and older Scala versions"

    def show(allTargetsInfo: Seq[DoctorTargetInfo]): Boolean =
      allTargetsInfo.exists(_.interactiveStatus.isCorrect == false)
  }

  case object SemanticDB extends DoctorExplanation {
    val title =
      "Semanticdb features (references, renames, go to implementation):"
    val correctMessage: String =
      s"${Icons.unicode.check} - build tool automatically creating needed semanticdb files"
    val incorrectMessage: String =
      s"${Icons.unicode.error} - semanticdb not being produced"

    def show(allTargetsInfo: Seq[DoctorTargetInfo]): Boolean =
      allTargetsInfo.exists(_.indexesStatus.isCorrect == false)
  }

  case object Debugging extends DoctorExplanation {
    val title = "Debugging (run/test, breakpoints, evaluation):"
    val correctMessage: String =
      s"${Icons.unicode.check} - users can run or test their code with debugging capabilities"
    val incorrectMessage: String =
      s"${Icons.unicode.error} - the tool does not support debugging in this target"

    def show(allTargetsInfo: Seq[DoctorTargetInfo]): Boolean =
      allTargetsInfo.exists(_.debuggingStatus.isCorrect == false)
  }

  case object JavaSupport extends DoctorExplanation {
    val title = "Java Support:"
    val correctMessage: String =
      s"${Icons.unicode.check} - working non-interactive features (references, rename etc.)"
    val incorrectMessage: String =
      s"""|${Icons.unicode.error} - missing semanticdb plugin, might not be added automatically by the build server (which is only done when using Bloop)
          |${Icons.unicode.info} - build target doesn't support Java files""".stripMargin

    def show(allTargetsInfo: Seq[DoctorTargetInfo]): Boolean =
      allTargetsInfo.exists(_.javaStatus.isCorrect == false)
  }

}
