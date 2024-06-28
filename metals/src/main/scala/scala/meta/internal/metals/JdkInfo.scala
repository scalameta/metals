package scala.meta.internal.metals

import scala.concurrent.ExecutionContext

object JavaInfo {
  def getInfo(
      userJavaHome: Option[String]
  )(implicit ec: ExecutionContext): Option[JavaInfo] =
    for {
      home <- JdkSources.defaultJavaHome(userJavaHome).headOption
      version <- JdkVersion.maybeJdkVersionFromJavaHome(Some(home))
    } yield JavaInfo(home.toString, version)
}

case class JavaInfo(home: String, version: JdkVersion) {
  def print: String = s"${version.full} located at $home"
}
