package scala.meta.internal.pc

import scala.annotation.nowarn
import scala.reflect.internal.Phase
import scala.reflect.internal.Reporter.ERROR
import scala.reflect.internal.Reporter.INFO
import scala.reflect.internal.Reporter.WARNING
import scala.reflect.internal.util.SourceFile
import scala.tools.nsc.interactive.FreshRunReq
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.interactive.Problem
import scala.tools.nsc.interactive.ShutdownReq
import scala.util.control.ControlThrowable
import scala.util.control.NonFatal

import scala.meta.internal.{semanticdb => s}

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
        logger.warn(
          s"Missing unit for file ${source.file} when retrieving errors. Errors will not be shown in this file. Loaded units are: $unitOfFile"
        )
        Nil
    }
  }

  def semanticdbDiagnosticsOf(unit: RichCompilationUnit): List[s.Diagnostic] = {
    for {
      p <- unit.problems.iterator
      if p.pos.isDefined
    } yield {
      val range = p.pos.toLsp
      s.Diagnostic(
        message = p.msg,
        severity = p.severityLevel match {
          case ERROR.id => s.Diagnostic.Severity.ERROR
          case WARNING.id => s.Diagnostic.Severity.WARNING
          case INFO.id => s.Diagnostic.Severity.INFORMATION
          case _ => s.Diagnostic.Severity.HINT
        },
        range = Some(
          s.Range(
            startLine = range.getStart().getLine(),
            startCharacter = range.getStart().getCharacter(),
            endLine = range.getEnd().getLine(),
            endCharacter = range.getEnd().getCharacter()
          )
        )
      )
    }
  }.toList

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
            if (compiler.metalsConfig.shouldRunRefchecks) {
              refChecks(unit)
            }
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
        case IgnorablePresentationCompilerErrors(e) =>
          logger.debug(
            s"[${unit.source}]: fatal error during background compile:",
            e
          )
        case ex: Throwable =>
          for (r <- waitLoadedTypeResponses(unit.source)) {
            r.raise(ex)
          }
          logger.warn(
            s"[${unit.source}] Presentation compiler crashed while type checking this file:",
            ex
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

  private def refChecks(unit: RichCompilationUnit): Unit = {
    if (!unit.problems.exists(_.severityLevel == ERROR.id)) {
      logger.debug(s"Applying refchecks phase to ${unit.source}")
      val oldBody = unit.body
      try {
        applyPhase(currentTyperRun.phaseNamed("superaccessors"), unit)
        applyPhase(currentTyperRun.refchecksPhase, unit)
      } catch {
        case NonFatal(ex) =>
          // we might get assertion errors because we're not running super-accessors before refchecks
          logger.debug(s"[${unit.source}]: Exception during refchecks", ex)
      } finally {
        filterProblems(unit)

        // refchecks performs tree transformations, for example replacing case class
        // appply calls with `new` calls, if the apply is synthetic. Rather than fixing
        // all the places in the code where we assume trees are not transformed, we restore
        // the original body, since we only care about diagnostics
        unit.body = oldBody
      }
    }
  }

  /**
   * Filter out problems that are expected due to how the presentation compiler works.
   *
   * For example, we don't want to show problems that are caused by macros that have not been expanded.
   *
   * @note This is needed because 2.12.20 doesn't have the `ArrayBuffer.filterInPlace` method.
   */
  private def filterProblems(unit: RichCompilationUnit): Unit = {
    var removed = 0
    for (
      (p, i) <- unit.problems.toList.zipWithIndex
      if p.msg.contains("macro has not been expanded")
    ) {
      unit.problems.remove(i - removed)
      removed += 1
    }
  }

  /**
   * Apply a phase to a compilation unit
   */
  private def applyPhase(phase: Phase, unit: CompilationUnit): Unit = {
    val oldGlobalPhase = this.globalPhase
    try {
      enteringPhase(phase) {
        // this departs from the standard presentation compiler implementation by setting
        // the global phase to the phase we want to apply. This is necessary because the
        // refhecks phase asserts that silencing type errors is allowed only after typer
        // and the condition is checked on `globalPhase`, not `phase`
        this.globalPhase = phase
        phase.asInstanceOf[GlobalPhase] applyPhase unit
      }
    } finally {
      this.globalPhase = oldGlobalPhase
    }
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
