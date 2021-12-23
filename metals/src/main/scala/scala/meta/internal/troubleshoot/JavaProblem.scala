package scala.meta.internal.troubleshoot

import scala.meta.internal.metals.BuildInfo

/**
 * Class describing different issues that a build target can have that might influence
 * features available to a user. For example without the semanticdb option
 * "find references" feature will not work. Those problems will be reported to a user
 * with an explanation on how to fix it.
 */
sealed abstract class JavaProblem {

  /**
   * Comprehensive message to be presented to the user.
   */
  def message: String
  protected val hint = "run 'Import build' to enable code navigation."
}

case class JavaSemanticDBDisabled(
    bloopVersion: String,
    unsupportedBloopVersion: Boolean
) extends JavaProblem {
  override def message: String = {
    if (unsupportedBloopVersion) {
      s"""|The installed Bloop server version is $bloopVersion while Metals requires at least Bloop version ${BuildInfo.bloopVersion},
          |To fix this problem please update your Bloop server.""".stripMargin
    } else {
      "Semanticdb is required for code navigation to work correctly in your project," +
        " however the Java semanticdb plugin doesn't seem to be enabled. " +
        "Please enable the Java semanticdb plugin for this project in order for code navigation to work correctly"
    }
  }
}

case class MissingJavaSourceRoot(sourcerootOption: String) extends JavaProblem {
  override def message: String =
    s"Add the compiler option $sourcerootOption to ensure code navigation works."
}

case class MissingJavaTargetRoot(targetrootOption: String) extends JavaProblem {
  override def message: String =
    s"Add the compiler option $targetrootOption to ensure code navigation works."
}
