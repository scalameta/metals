package scala.meta.internal.pc

import scala.meta.pc.OutlineFiles

trait Outline { this: MetalsGlobal =>
  def runOutline(files: OutlineFiles): Unit = {
    // no outline compilation for 2.11
  }
}
