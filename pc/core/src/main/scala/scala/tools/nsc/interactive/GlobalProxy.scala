package scala.tools.nsc.interactive

import scala.util.control.NonFatal

trait GlobalProxy { this: Global =>
  def presentationCompilerThread: Thread = this.compileRunner
  def hijackPresentationCompilerThread(): Unit = newRunnerThread()

  var threadId = 0
  private def newRunnerThread(): Thread = {
    threadId += 1
    if (compileRunner.isAlive) {
      try {
        val re = askForResponse(() => throw ShutdownReq)
        re.get
      } catch {
        case NonFatal(e) =>
          pprint.log(e)
      }
    }
    compileRunner = new MetalsGlobalThread(this, "Metals")
    compileRunner.setDaemon(true)
    compileRunner.start()
    compileRunner
  }
}
