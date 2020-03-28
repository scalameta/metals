package scala.meta.internal.metals.debug
import org.eclipse.lsp4j.debug.Breakpoint
import scala.collection.concurrent.TrieMap

final class DebuggeeBreakpoints {
  private val breakpoints = TrieMap.empty[Int, Breakpoint]

  def register(breakpoint: Breakpoint): Unit = {
    breakpoints.put(breakpoint.getId, breakpoint)
  }

  def all: Iterable[Breakpoint] = {
    breakpoints.values
  }

  def byStackFrame(frame: StackFrame): Option[Breakpoint] = {
    breakpoints.values.find(matching(frame))
  }

  private def matching(frame: StackFrame)(breakpoint: Breakpoint): Boolean = {
    val info = frame.info
    if (info.getSource == null) false
    else if (breakpoint.getSource == null) false
    else if (breakpoint.getSource.getPath != info.getSource.getPath) false
    else if (breakpoint.getLine != info.getLine) false
    else true
  }
}
