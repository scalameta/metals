package scala.meta.internal.metals

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import dev.dirs.ProjectDirectories

object MetalsProjectDirectories {

  def from(qualifier: String, organization: String, application: String)(
      implicit ec: ExecutionContext
  ): Option[ProjectDirectories] =
    wrap { () =>
      ProjectDirectories.from(qualifier, organization, application)
    }

  def fromPath(path: String)(implicit
      ec: ExecutionContext
  ): Option[ProjectDirectories] =
    wrap { () =>
      ProjectDirectories.fromPath(path)
    }

  private def wrap(
      f: () => ProjectDirectories
  )(implicit ec: ExecutionContext): Option[ProjectDirectories] = {
    Try {
      val dirs = Future { f() }
      Await.result(dirs, 10.seconds)

    } match {
      case Failure(exception) =>
        scribe.error("Failed to get project directories", exception)
        None
      case Success(value) => Some(value)
    }
  }
}
