package scala.meta.internal.metals.mcp

object McpMessages {

  object FindDep {

    private val MaxDisplayedPreReleases = 3

    def versionMessage(
        latestStable: Option[String],
        preReleases: Seq[String] = Nil,
    ): String = {
      val stableLine = latestStable.fold("No stable version found")(version =>
        s"Latest stable version found: $version"
      )
      val preReleaseLine =
        if (preReleases.isEmpty) ""
        else {
          val displayed = preReleases.take(MaxDisplayedPreReleases)
          val truncated =
            if (preReleases.size > MaxDisplayedPreReleases) ", [...]" else ""
          s"\nDevelopment/pre-release matches: ${displayed.mkString(", ")}$truncated"
        }
      s"$stableLine$preReleaseLine\n"
    }

    def dependencyReturnMessage(
        key: String,
        completed: Seq[String],
    ): String = {
      s"""|Tool managed to complete `$key` field and got potential values to use for it: ${completed.mkString(", ")}
          |""".stripMargin
    }

    def noCompletionsFound: String =
      "No completions found"
  }
}

sealed trait IncorrectArgumentException extends Exception

class MissingArgumentException(key: String)
    extends Exception(s"Missing argument: $key")
    with IncorrectArgumentException

class IncorrectArgumentTypeException(key: String, expected: String)
    extends Exception(s"Incorrect argument type for $key, expected: $expected")
    with IncorrectArgumentException

class InvalidSymbolTypeException(invalid: Seq[String], validTypes: String)
    extends Exception(
      s"Invalid symbol types: ${invalid.mkString(", ")}. Valid types are: $validTypes"
    )
    with IncorrectArgumentException

object MissingFileInFocusException
    extends Exception(s"Missing fileInFocus and failed to infer it.")
    with IncorrectArgumentException
