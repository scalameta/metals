package scala.meta.internal.process

import java.nio.file.Path
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.Time
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.pc.CancelToken
import com.zaxxer.nuprocess.NuProcessBuilder
import com.zaxxer.nuprocess.NuProcess
import scala.meta.internal.ansi.LineListener

object SystemProcess {
  def run(
      shortName: String,
      args: List[String],
      reproduceArgs: List[String],
      cwd: Path,
      token: CancelToken,
      stdout: LineListener
  )(implicit ec: ExecutionContext): Unit = {
    val exportTimer = new Timer(Time.system)
    var process = Option.empty[NuProcess]
    val handler = new ProcessHandler(stdout, LineListener.info)
    val pb = new NuProcessBuilder(handler, args.asJava)
    pb.setCwd(cwd)
    scribe.info(args.mkString("process: ", " ", ""))
    val runningProcess = pb.start()
    token.onCancel().asScala.foreach { cancel =>
      if (cancel) {
        ProcessHandler.destroyProcess(runningProcess)
      }
    }
    val exit = handler.completeProcess.future.asJava.get()
    token.checkCanceled()
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
