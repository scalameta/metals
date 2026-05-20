package scala.meta.internal.metals.doctor

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

case class WrongJavaReleaseVersion(current: String, needed: String)
    extends JavaProblem {
  override def message: String =
    s"Build tool currently uses $current Java version, but the build definition requires $needed Java version due to `-release` flag."
}
