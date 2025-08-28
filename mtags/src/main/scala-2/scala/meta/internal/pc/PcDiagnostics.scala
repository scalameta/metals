package scala.meta.internal.pc

import scala.annotation.nowarn
import scala.reflect.internal.Reporter.ERROR
import scala.reflect.internal.Reporter.INFO
import scala.reflect.internal.Reporter.WARNING
import scala.reflect.internal.util.SourceFile
import scala.tools.nsc.interactive.FreshRunReq
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.interactive.Problem
import scala.tools.nsc.interactive.ShutdownReq
import scala.util.control.ControlThrowable

import org.eclipse.{lsp4j => l}

trait PcDiagnostics {
  compiler: MetalsGlobal =>

  /**
   * Return all problems issued by the presentation compiler for the given file. This method may block
   * waiting for a typechecking run to finish.
   */
  def diagnosticsOf(
      source: SourceFile
  ): Seq[l.Diagnostic] = {
    unitOfFile get source.file match {
      case Some(unit) =>
        // run our own compilation here, on the CompilerJob queue
        // this solves the various race conditions in the compiler type checker compared to using the "right way"
        // but it clogs the PC until the type checker is done
        backgroundCompile()
        unit.problems.toList.flatMap(toLspDiagnostic)

      case None =>
        logger.warning(
          s"Missing unit for file ${source.file} when retrieving errors. Errors will not be shown in this file. Loaded units are: $unitOfFile"
        )
        Nil
    }
  }

  private def toLspDiagnostic(prob: Problem): Option[l.Diagnostic] = {
    if (prob.pos.isDefined) {
      val severity = prob.severityLevel match {
        case ERROR.id => l.DiagnosticSeverity.Error
        case WARNING.id => l.DiagnosticSeverity.Warning
        case INFO.id => l.DiagnosticSeverity.Information
        case _ => l.DiagnosticSeverity.Hint
      }

      Some(
        new l.Diagnostic(
          prob.pos.toLsp,
          prob.msg,
          severity,
          "scala presentation compiler",
          null
        )
      )
    } else None
  }

  /**
   * Compile all loaded source files in the order given by `allSources`.
   *
   * Adapted from iteractive.Global.
   */
  private final def backgroundCompile(): Unit = {
    reporter.reset()

    // remove any files in first that are no longer maintained by presentation compiler (i.e. closed)
    allSources = allSources filter (s => unitOfFile contains (s.file))

    // ensure all loaded units are typechecked
    for (s <- allSources; unit <- getUnit(s)) {
      try {
        ensureUpToDate(unit)
        if (!unit.isUpToDate)
          if (unit.problems.isEmpty || !settings.YpresentationStrict.value) {
            debugLog("type checking: " + unit)
            parseAndEnter(unit)
            unit.status = PartiallyChecked
            currentTyperRun.typeCheck(unit)
            unit.lastBody = unit.body
            unit.status = currentRunId
          } else
            debugLog("%s has syntax errors. Skipped typechecking".format(unit))
        else debugLog("already up to date: " + unit)
        for (r <- waitLoadedTypeResponses(unit.source))
          r set unit.body
        //        serviceParsedEntered()
      } catch {
        case ex: FreshRunReq => throw ex // propagate a new run request
        case ShutdownReq => throw ShutdownReq // propagate a shutdown request
        case ex: ControlThrowable => throw ex
        case ex: Throwable =>
          println(
            "[%s]: exception during background compile: ".format(
              unit.source
            ) + ex
          )
          ex.printStackTrace()
          for (r <- waitLoadedTypeResponses(unit.source)) {
            r.raise(ex)
          }
          //          serviceParsedEntered()

          //          lastException = Some(ex)

          reporter.error(
            unit.body.pos,
            "Presentation compiler crashed while type checking this file: %s"
              .format(ex.toString())
          )
      }
    }

    // move units removable after this run to the "to-be-removed" buffer
    toBeRemoved.synchronized {
      toBeRemovedAfterRun.synchronized {
        toBeRemoved ++= toBeRemovedAfterRun
      }
    }

    // clean out stale waiting responses
    //    cleanAllResponses()

    // wind down
    informIDE("Everything is now up to date")
  }

  private def ensureUpToDate(unit: RichCompilationUnit) =
    if (!unit.isUpToDate && unit.status != JustParsed)
      reset(unit) // reparse previously typechecked units.

  /** Parse unit and create a name index, unless this has already been done before */
  private def parseAndEnter(unit: RichCompilationUnit): Unit =
    if (unit.status == NotLoaded) {
      debugLog("parsing: " + unit)
      runReporting.clearSuppressionsComplete(unit.source)
      currentTyperRun.compileLate(unit)
      if (debugIDE && !reporter.hasErrors) validatePositions(unit.body)
      if (!unit.isJava) syncTopLevelSyms(unit)
      unit.status = JustParsed
    }

  def currentTyperRun: TyperRun = {
    val ctr = classOf[Global].getDeclaredField("currentTyperRun")
    ctr.setAccessible(true)
    ctr.get(this).asInstanceOf[TyperRun]
  }

  /** Reset unit to unloaded state */
  private def reset(unit: RichCompilationUnit): Unit = {
    unit.depends.clear(): @nowarn("cat=deprecation")
    unit.defined.clear(): @nowarn("cat=deprecation")
    unit.synthetics.clear()
    unit.toCheck.clear()
    unit.checkedFeatures = Set()
    unit.targetPos = NoPosition
    unit.contexts.clear()
    unit.problems.clear()
    unit.body = EmptyTree
    unit.status = NotLoaded
    unit.transformed.clear()
  }
}
