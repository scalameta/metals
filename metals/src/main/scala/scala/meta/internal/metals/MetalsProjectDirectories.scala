package scala.meta.internal.metals

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import dev.dirs.ProjectDirectories
import dev.dirs.jni.WindowsJni

object MetalsProjectDirectories {

  def isNotBroken(path: String): Boolean = {
    !path.contains("null") && !path.contains("PowerShell")
  }

  def from(
      qualifier: String,
      organization: String,
      application: String,
      silent: Boolean,
  )(implicit
      ec: ExecutionContext
  ): Option[ProjectDirectories] =
    wrap(silent) { () =>
      ProjectDirectories.from(
        qualifier,
        organization,
        application,
        WindowsJni.getJdkAwareSupplier(),
      )
    }

  def fromPath(path: String, silent: Boolean)(implicit
      ec: ExecutionContext
  ): Option[ProjectDirectories] =
    wrap(silent) { () =>
      ProjectDirectories.fromPath(path, WindowsJni.getJdkAwareSupplier())
    }

  private def wrap(silent: Boolean)(
      f: () => ProjectDirectories
  )(implicit ec: ExecutionContext): Option[ProjectDirectories] = {
    Try {
      val dirs = Future { f() }
      Await.result(dirs, 5.seconds)
    } match {
      case Failure(exception) if !silent =>
        scribe.error("Failed to get project directories", exception)
        None
      case Failure(exception) => None
      case Success(value) => Some(value)
    }
  }
}
