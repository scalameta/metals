package scala.meta.internal.process

import java.nio.file.Path
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.Time
import scala.meta.internal.pantsbuild.MessageOnlyException
import scala.concurrent.ExecutionContext
import scala.meta.pc.CancelToken
import scala.sys.process._

object SystemProcess {
  def run(
      shortName: String,
      args: List[String],
      reproduceArgs: List[String],
      cwd: Path,
      token: CancelToken
  )(implicit ec: ExecutionContext): Unit = {
    val exportTimer = new Timer(Time.system)
    scribe.info(args.mkString("process: ", " ", ""))
    val exit = Process(args, cwd = Some(cwd.toFile())).!
    if (exit != 0) {
      val message = s"$shortName command failed with exit code $exit, " +
        s"to reproduce run the command below:\n\t${reproduceArgs.mkString(" ")}"
      throw MessageOnlyException(message)
    } else {
      scribe.info(s"time: ran '$shortName' in $exportTimer")
    }
  }
}
