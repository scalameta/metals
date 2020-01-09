package scala.meta.internal.process

import java.nio.file.Path
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.Time
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
      val message =
        s"command failed with exit code $exit: ${reproduceArgs.mkString(" ")}"
      scribe.error(message)
      sys.error(message)
    } else {
      scribe.info(s"time: ran '$shortName' in $exportTimer")
    }
  }
}
