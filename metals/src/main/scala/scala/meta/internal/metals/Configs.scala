package scala.meta.internal.metals

import java.util.Optional
import java.util.Properties
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileSystemWatcher
import scala.meta.io.AbsolutePath
import scala.collection.JavaConverters._
import scala.meta.pc.PresentationCompilerConfig

object Configs {

  final case class GlobSyntaxConfig(value: String) {
    import GlobSyntaxConfig._
    def isUri: Boolean = this == uri
    def isVscode: Boolean = this == vscode
    def registrationOptions(
        workspace: AbsolutePath
    ): DidChangeWatchedFilesRegistrationOptions = {
      val root: String =
        if (isVscode) workspace.toString()
        else workspace.toURI.toString.stripSuffix("/")
      new DidChangeWatchedFilesRegistrationOptions(
        List(
          new FileSystemWatcher(s"$root/*.sbt"),
          new FileSystemWatcher(s"$root/project/*.{scala,sbt}"),
          new FileSystemWatcher(s"$root/project/build.properties")
        ).asJava
      )
    }
  }

  object GlobSyntaxConfig {
    def uri = new GlobSyntaxConfig("uri")
    def vscode = new GlobSyntaxConfig("vscode")
    def default = new GlobSyntaxConfig(
      System.getProperty("metals.glob-syntax", uri.value)
    )
  }

  case class CompilersConfig(debug: Boolean, parameterHint: Option[String])
      extends PresentationCompilerConfig {
    def parameterHintsCommand: Optional[String] =
      Optional.ofNullable(parameterHint.orNull)
  }

  object CompilersConfig {
    def apply(props: Properties = System.getProperties): CompilersConfig = {
      CompilersConfig(
        MetalsServerConfig.binaryOption("pc.debug", default = false),
        Option(props.getProperty("metals.signature-help-command"))
      )
    }
  }

}
