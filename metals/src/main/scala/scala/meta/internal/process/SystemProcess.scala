package scala.meta.internal.process

import java.nio.file.Path

import scala.sys.process._

import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer
import scala.meta.pc.CancelToken

object SystemProcess {
  def run(
      shortName: String,
      args: List[String],
      reproduceArgs: List[String],
      cwd: Path,
      token: CancelToken
  ): Unit = {
    val exportTimer = new Timer(Time.system)
    scribe.info(args.mkString("process: ", " ", ""))
    val exit = Process(args, cwd = Some(cwd.toFile())).!
    if (exit != 0) {
      val message = s"$shortName command failed with exit code $exit, " +
        s"to reproduce run the command below:\n\t${reproduceArgs.mkString(" ")}"
      throw new MessageOnlyException(message)
    } else {
      scribe.info(s"time: ran '$shortName' in $exportTimer")
    }
  }
}
