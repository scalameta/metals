package docs

object Image {
  val all: Map[String, Map[String, String]] = Map(
    "vscode" -> Map(
      "importBuild" -> "https://github.com/scalameta/gh-pages-images/blob/master/metals/Image/0VqZWay.png?raw=true",
      "importChanges" -> "https://github.com/scalameta/gh-pages-images/blob/master/metals/Image/72kdZkL.png?raw=true",
      "importCommand" -> "https://github.com/scalameta/gh-pages-images/blob/master/metals/Image/QHLKt8u.png?raw=true",
      "runDoctor" -> "https://github.com/scalameta/gh-pages-images/blob/master/metals/Image/K02g0UM.png?raw=true",
    ),
    "vim" -> Map(
      "importBuild" -> "https://github.com/scalameta/gh-pages-images/blob/master/metals/Image/1EyQPTC.png?raw=true",
      "importChanges" -> "https://github.com/scalameta/gh-pages-images/blob/master/metals/Image/iocTVb6.png?raw=true",
    ),
    "emacs" -> Map(
      "importBuild" -> "https://github.com/scalameta/gh-pages-images/blob/master/metals/Image/UdwMQFk.png?raw=true",
      "importChanges" -> "https://github.com/scalameta/gh-pages-images/blob/master/metals/Image/UFK0p8i.png?raw=true",
    ),
  )
  def importBuild(editor: String): String =
    all(editor)("importBuild")
  def importChanges(editor: String): String =
    all(editor)("importChanges")
  def importCommand(editor: String): String =
    all(editor)("importCommand")
  def runDoctor(editor: String): String =
    all(editor)("runDoctor")
}
