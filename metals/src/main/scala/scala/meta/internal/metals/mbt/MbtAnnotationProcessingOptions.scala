package scala.meta.internal.metals.mbt

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

import scala.collection.mutable
import scala.util.Try

import scala.meta.internal.jdk.CollectionConverters._

case class MbtAnnotationProcessingOptions(
    processorPath: Seq[Path],
    processors: Seq[String],
    javacOptions: Seq[String],
    disabled: Boolean,
) {
  def isEnabled: Boolean =
    !disabled && processorPath.nonEmpty && processors.nonEmpty
}

object MbtAnnotationProcessingOptions {
  val empty: MbtAnnotationProcessingOptions =
    MbtAnnotationProcessingOptions(Nil, Nil, Nil, disabled = true)

  def fromTarget(target: MbtTarget): MbtAnnotationProcessingOptions = {
    val parsed = parseJavacOptions(target.javacOptions)
    val processorModules = target.dependencyModules
      .filterNot(_.getAnnotationProcessors.isEmpty)
    val moduleProcessorPaths = processorModules.flatMap(_.jarPath)
    val moduleProcessors =
      processorModules.flatMap(_.getAnnotationProcessors.asScala)

    MbtAnnotationProcessingOptions(
      processorPath = (parsed.processorPath ++ moduleProcessorPaths).distinct,
      processors = (parsed.processors ++ moduleProcessors).distinct,
      javacOptions = target.javacOptions,
      disabled = parsed.disabled,
    )
  }

  def fromBuild(build: MbtBuild): MbtAnnotationProcessingOptions = {
    val javacOptions =
      build.mbtTargets.flatMap(_.javacOptions).distinct
    val parsed = parseJavacOptions(javacOptions)
    val processorModules = build.getDependencyModules.asScala.toSeq
      .filterNot(_.getAnnotationProcessors.isEmpty)
    val moduleProcessorPaths =
      processorModules.flatMap(_.jarPath)
    val moduleProcessors =
      processorModules.flatMap(_.getAnnotationProcessors.asScala)

    MbtAnnotationProcessingOptions(
      processorPath = (parsed.processorPath ++ moduleProcessorPaths).distinct,
      processors = (parsed.processors ++ moduleProcessors).distinct,
      javacOptions = javacOptions,
      disabled = parsed.disabled,
    )
  }

  private case class ParsedJavacOptions(
      processorPath: Seq[Path],
      processors: Seq[String],
      disabled: Boolean,
  )

  private def parseJavacOptions(
      options: Seq[String]
  ): ParsedJavacOptions = {
    val processorPath = mutable.ListBuffer.empty[Path]
    val processors = mutable.ListBuffer.empty[String]
    var disabled = false
    var remaining = options

    while (remaining.nonEmpty) {
      remaining match {
        case "-proc:none" +: tail =>
          disabled = true
          remaining = tail
        case ("-processorpath" | "--processor-path") +: value +: tail =>
          addProcessorPath(processorPath, value)
          remaining = tail
        case "-processor" +: value +: tail =>
          addProcessors(processors, value)
          remaining = tail
        case option +: tail if option.startsWith("-processorpath=") =>
          addProcessorPath(processorPath, option.stripPrefix("-processorpath="))
          remaining = tail
        case option +: tail if option.startsWith("--processor-path=") =>
          addProcessorPath(
            processorPath,
            option.stripPrefix("--processor-path="),
          )
          remaining = tail
        case option +: tail if option.startsWith("-processorpath:") =>
          addProcessorPath(processorPath, option.stripPrefix("-processorpath:"))
          remaining = tail
        case option +: tail if option.startsWith("-processor=") =>
          addProcessors(processors, option.stripPrefix("-processor="))
          remaining = tail
        case _ +: tail =>
          remaining = tail
      }
    }

    ParsedJavacOptions(
      processorPath.result().distinct,
      processors.result().distinct,
      disabled,
    )
  }

  private def addProcessorPath(
      builder: mutable.ListBuffer[Path],
      value: String,
  ): Unit = {
    value
      .split(File.pathSeparator)
      .filter(_.nonEmpty)
      .flatMap(toPath)
      .foreach(builder += _)
  }

  private def addProcessors(
      builder: mutable.ListBuffer[String],
      value: String,
  ): Unit = {
    value
      .split(",")
      .map(_.trim)
      .filter(_.nonEmpty)
      .foreach(builder += _)
  }

  private def toPath(value: String): Option[Path] =
    if (value.startsWith("file:"))
      Try(Paths.get(MbtDependencyModule.parseUri(value))).toOption
    else
      Try(Paths.get(value)).toOption
}
