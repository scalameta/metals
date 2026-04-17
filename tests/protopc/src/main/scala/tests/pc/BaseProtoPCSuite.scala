package tests.pc

import java.nio.file.Files

import scala.meta.Dialect
import scala.meta.dialects
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.internal.protopc.ProtoPresentationCompiler
import scala.meta.io.AbsolutePath
import scala.meta.pc.ProtobufLspConfig

import org.slf4j.LoggerFactory
import tests.BaseSuite
import tests.PCSuite

abstract class BaseProtoPCSuite extends BaseSuite with PCSuite {

  val dialect: Dialect = dialects.Scala213

  val tmp: AbsolutePath = AbsolutePath(
    Files.createTempDirectory("proto.metals")
  )

  protected lazy val presentationCompiler: ProtoPresentationCompiler = {
    ProtoPresentationCompiler()
      .withConfiguration(
        PresentationCompilerConfigImpl()
          .copy(
            emitDiagnostics = true,
            protobufLspConfig = ProtobufLspConfig.ENABLED,
          )
      )
      .withLogger(LoggerFactory.getLogger("proto.metals"))
      .asInstanceOf[ProtoPresentationCompiler]
  }

  override def params(
      code: String,
      filename: String = "test.proto",
  ): (String, Int) = super.params(code, filename)

  override def hoverParams(
      code: String,
      filename: String = "test.proto",
  ): (String, Int, Int) = super.hoverParams(code, filename)
}
