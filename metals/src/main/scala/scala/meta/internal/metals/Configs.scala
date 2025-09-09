package scala.meta.internal.metals

import java.util.Properties

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.io.AbsolutePath
import scala.meta.pc.PresentationCompilerConfig.OverrideDefFormat

import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.jsonrpc.messages.Either

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
        (List(
          new FileSystemWatcher(Either.forLeft(s"$root/**/*.{scala,java}")),
          new FileSystemWatcher(Either.forLeft(s"$root/*.sbt")),
          new FileSystemWatcher(Either.forLeft(s"$root/pom.xml")),
          new FileSystemWatcher(Either.forLeft(s"$root/*.sc")),
          new FileSystemWatcher(Either.forLeft(s"$root/*?.gradle")),
          new FileSystemWatcher(Either.forLeft(s"$root/*.gradle.kts")),
          new FileSystemWatcher(Either.forLeft(s"$root/project/*.{scala,sbt}")),
          new FileSystemWatcher(
            Either.forLeft(s"$root/project/project/*.{scala,sbt}")
          ),
          new FileSystemWatcher(
            Either.forLeft(s"$root/project/build.properties")
          ),
          new FileSystemWatcher(
            Either.forLeft(s"$root/.metals/.reports/bloop/*/*")
          ),
          new FileSystemWatcher(Either.forLeft(s"$root/**/.bsp/*.json")),
        ) ++ bazelPaths(root)).asJava
      )
    }

    def bazelPaths(root: String): List[FileSystemWatcher] =
      List(
        new FileSystemWatcher(Either.forLeft(s"$root/**/BUILD")),
        new FileSystemWatcher(Either.forLeft(s"$root/**/BUILD.bazel")),
        new FileSystemWatcher(Either.forLeft(s"$root/WORKSPACE")),
        new FileSystemWatcher(Either.forLeft(s"$root/WORKSPACE.bazel")),
        new FileSystemWatcher(Either.forLeft(s"$root/**/*.bzl")),
        new FileSystemWatcher(Either.forLeft(s"$root/*.bazelproject")),
      )
  }

  object GlobSyntaxConfig {
    def uri = new GlobSyntaxConfig("uri")
    def vscode = new GlobSyntaxConfig("vscode")
    def default =
      new GlobSyntaxConfig(
        System.getProperty("metals.glob-syntax", uri.value)
      )
    def fromString(value: String): Option[GlobSyntaxConfig] =
      value match {
        case "vscode" => Some(vscode)
        case "uri" => Some(uri)
        case _ => None
      }
  }

  object CompilersConfig {
    def apply(
        props: Properties = System.getProperties
    ): PresentationCompilerConfigImpl = {
      PresentationCompilerConfigImpl(
        debug =
          MetalsServerConfig.binaryOption("metals.pc.debug", default = false),
        _parameterHintsCommand =
          Option(props.getProperty("metals.signature-help.command")),
        _completionCommand =
          Option(props.getProperty("metals.completion.command")),
        overrideDefFormat =
          props.getProperty("metals.override-def-format") match {
            case "unicode" => OverrideDefFormat.Unicode
            case "ascii" => OverrideDefFormat.Ascii
            case _ => OverrideDefFormat.Ascii
          },
        isCompletionItemDetailEnabled = MetalsServerConfig.binaryOption(
          "metals.completion-item.detail",
          default = true,
        ),
        isCompletionItemDocumentationEnabled = MetalsServerConfig.binaryOption(
          "metals.completion-item.documentation",
          default = true,
        ),
        isHoverDocumentationEnabled = MetalsServerConfig.binaryOption(
          "metals.hover.documentation",
          default = true,
        ),
        snippetAutoIndent = MetalsServerConfig.binaryOption(
          "metals.snippet-auto-indent",
          default = true,
        ),
        isSignatureHelpDocumentationEnabled = MetalsServerConfig.binaryOption(
          "metals.signature-help.documentation",
          default = true,
        ),
        isCompletionItemResolve = MetalsServerConfig.binaryOption(
          "metals.completion-item.resolve",
          default = true,
        ),
      )
    }
  }

}
