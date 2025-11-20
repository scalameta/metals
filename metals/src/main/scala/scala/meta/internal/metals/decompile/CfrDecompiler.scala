package scala.meta.internal.metals.decompile

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util
import java.util.Collections

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import org.benf.cfr.reader.api.CfrDriver
import org.benf.cfr.reader.api.OutputSinkFactory
import org.benf.cfr.reader.api.SinkReturns.ExceptionMessage

class CfrDecompiler extends DecompileBytecode {

  class StringCollectorSink extends OutputSinkFactory {
    import OutputSinkFactory.{SinkType, SinkClass, Sink}

    private val stringWriter = new StringWriter()
    private val decompiledCode = new PrintWriter(stringWriter)
    private val errors = new StringBuilder()

    override def getSupportedSinks(
        sinkType: SinkType,
        availableSinks: util.Collection[SinkClass],
    ): util.List[SinkClass] =
      sinkType match {
        case SinkType.JAVA => Collections.singletonList(SinkClass.STRING)
        case SinkType.EXCEPTION =>
          Collections.singletonList(SinkClass.EXCEPTION_MESSAGE)
        case _ => null
      }

    override def getSink[T](sinkType: SinkType, sinkClass: SinkClass): Sink[T] =
      sinkType match {
        case SinkType.JAVA if sinkClass == SinkClass.STRING =>
          (content: T) => decompiledCode.print(content.toString)

        case SinkType.EXCEPTION if sinkClass == SinkClass.EXCEPTION_MESSAGE =>
          val sink: Sink[ExceptionMessage] = { (content: ExceptionMessage) =>
            errors.append(content.getThrownException.toString())
          }
          sink.asInstanceOf[Sink[T]]

        case _ => ignored => ()
      }

    def decompiledOutput: String = stringWriter.toString
    def errorOutput: String = errors.toString
  }

  override def decompilePath(
      path: AbsolutePath,
      extraClassPath: List[AbsolutePath],
  ): Future[Either[String, String]] = {
    if (path.isJarFileSystem) {
      val clsName = path.toString
        .replace('/', '.')
        .stripPrefix(".")
        .stripSuffix(".class")

      decompile(clsName, path.jarPath.get :: extraClassPath)

    } else {
      decompile(path.toString, extraClassPath)
    }
  }

  override def decompile(
      clsName: String,
      extraClassPath: List[AbsolutePath],
  ): Future[Either[String, String]] = {
    Future {
      val sinkFactory = new StringCollectorSink()

      val options = new util.HashMap[String, String]()
      options.put("outputdir", "none")
      options.put(
        "extraclasspath",
        extraClassPath.map(_.toString).mkString(File.pathSeparator),
      )
      options.put("elidescala", "true")
      options.put("sugarenums", "true")
      options.put("analyseas", "CLASS")

      val driver = new CfrDriver.Builder()
        .withOptions(options)
        .withOutputSink(sinkFactory)
        .build()

      driver.analyse(Collections.singletonList(clsName))

      if (sinkFactory.errorOutput.nonEmpty) {
        Left(sinkFactory.errorOutput)
      } else
        Right(sinkFactory.decompiledOutput)
    }
  }
}
