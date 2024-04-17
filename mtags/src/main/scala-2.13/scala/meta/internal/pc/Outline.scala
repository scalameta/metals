package scala.meta.internal.pc

import java.{util => ju}

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.pc.OutlineFiles
import scala.meta.pc.VirtualFileParams

trait Outline { this: MetalsGlobal =>
  def runOutline(files: OutlineFiles): Unit = {
    this.settings.Youtline.value = true
    runOutline(files.files)
    if (files.isFirstCompileSubstitute()) {
      // if first compilation substitute we compile all files twice
      // first to emit symbols, second so signatures have information about those symbols
      // this isn't a perfect strategy but much better than single compile
      runOutline(files.files, forceNewUnit = true)
    }
    this.settings.Youtline.value = false
  }

  private def runOutline(
      files: ju.List[VirtualFileParams],
      forceNewUnit: Boolean = false
  ): Unit = {
    files.asScala.foreach { params =>
      val unit = this.addCompilationUnit(
        params.text(),
        params.uri.toString(),
        cursor = None,
        isOutline = true,
        forceNew = forceNewUnit
      )
      this.typeCheck(unit)
      this.richCompilationCache.put(params.uri().toString(), unit)
    }
  }
}
