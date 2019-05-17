package scala.meta.internal.eval
import org.eclipse.lsp4j.debug._

object JvmDebugProtocol {
  def exitedEvent(exitCode: Long): ExitedEventArguments = {
    val arguments = new ExitedEventArguments
    arguments.setExitCode(exitCode)
    arguments
  }

  def outputEvent(category: String, message: String): OutputEventArguments = {
    val output = new OutputEventArguments()
    output.setCategory(category)
    output.setOutput(message)
    output
  }

  final case class LaunchParameters(
      cwd: String,
      mainClass: String,
      classpath: Array[String]
  )
}
