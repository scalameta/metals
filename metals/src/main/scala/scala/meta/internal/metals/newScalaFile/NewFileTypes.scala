package scala.meta.internal.metals.newScalaFile

import scala.meta.internal.metals.clients.language.MetalsQuickPickItem

object NewFileTypes {
  sealed trait NewFileType {
    val id: String
    val label: String
    def toQuickPickItem: MetalsQuickPickItem = MetalsQuickPickItem(id, label)
  }

  case object ScalaFile extends NewFileType {
    override val id: String = "scala-file"
    override val label: String = "Scala file with automatically added package"
  }

  case object Class extends NewFileType {
    override val id: String = "class"
    override val label: String = "Class"
  }

  case object CaseClass extends NewFileType {
    override val id: String = "case-class"
    override val label: String = "Case Class"
  }

  case object Enum extends NewFileType {
    override val id: String = "enum"
    override val label: String = "Enum"
  }

  case object Object extends NewFileType {
    override val id: String = "object"
    override val label: String = "Object"
  }

  case object Trait extends NewFileType {
    override val id: String = "trait"
    override val label: String = "Trait"
  }

  case object PackageObject extends NewFileType {
    override val id: String = "package-object"
    override val label: String = "Package Object"
  }

  case object Worksheet extends NewFileType {
    override val id: String = "worksheet"
    override val label: String = "Worksheet"
  }

  case object AmmoniteScript extends NewFileType {
    override val id: String = "ammonite"
    override val label: String = "Ammonite Script"
  }

  def getFromString(id: String): Option[NewFileType] =
    id match {
      case Class.id => Some(Class)
      case CaseClass.id => Some(CaseClass)
      case Enum.id => Some(Enum)
      case Object.id => Some(Object)
      case Trait.id => Some(Trait)
      case ScalaFile.id => Some(ScalaFile)
      case PackageObject.id => Some(PackageObject)
      case Worksheet.id => Some(Worksheet)
      case AmmoniteScript.id => Some(AmmoniteScript)
      case invalid =>
        scribe.error(
          s"Invalid filetype given to new-scala-file command: $invalid"
        )
        None
    }
}
