package scala.meta.internal.metals.newScalaFile

import scala.meta.internal.metals.clients.language.MetalsQuickPickItem

object NewFileTypes {
  sealed trait NewFileType {
    val id: String
    val syntax: String
    val label: String
    def toQuickPickItem: MetalsQuickPickItem = MetalsQuickPickItem(id, label)
  }

  case object ScalaFile extends NewFileType {
    override val id: String = "scala-file"
    override val label: String = "Scala file with automatically added package"
  }

  case object Class extends NewFileType {
    override val id: String = "scala-class"
    override val syntax: String = "class"
    override val label: String = "Class"
  }

  case object CaseClass extends NewFileType {
    override val id: String = "scala-case-class"
    override val syntax: String = "NA"
    override val label: String = "Case Class"
  }

  case object Enum extends NewFileType {
    override val id: String = "enum"
    override val syntax: String = "NA"
    override val label: String = "Enum"
  }

  case object Object extends NewFileType {
    override val id: String = "scala-object"
    override val syntax: String = "object"
    override val label: String = "Object"
  }

  case object Trait extends NewFileType {
    override val id: String = "scala-trait"
    override val syntax: String = "trait"
    override val label: String = "Trait"
  }

  case object PackageObject extends NewFileType {
    override val id: String = "scala-package-object"
    override val syntax: String = "NA"
    override val label: String = "Package Object"
  }

  case object Worksheet extends NewFileType {
    override val id: String = "scala-worksheet"
    override val syntax: String = "NA"
    override val label: String = "Worksheet"
  }

  case object AmmoniteScript extends NewFileType {
    override val id: String = "ammonite-script"
    override val syntax: String = "NA"
    override val label: String = "Ammonite Script"
  }

  case object JavaClass extends NewFileType {
    override val id: String = "java-class"
    override val syntax: String = "class"
    override val label: String = "Class"
  }

  case object JavaInterface extends NewFileType {
    override val id: String = "java-interface"
    override val syntax: String = "interface"
    override val label: String = "Interface"
  }

  case object JavaEnum extends NewFileType {
    override val id: String = "java-enum"
    override val syntax: String = "enum"
    override val label: String = "Enum"
  }

  case object JavaRecord extends NewFileType {
    override val id: String = "java-record"
    override val syntax: String = "NA"
    override val label: String = "Record"
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
      case JavaClass.id => Some(JavaClass)
      case JavaInterface.id => Some(JavaInterface)
      case JavaEnum.id => Some(JavaEnum)
      case JavaRecord.id => Some(JavaRecord)
      case invalid =>
        scribe.error(
          s"Invalid filetype given to new-(scala/java)-file command: $invalid"
        )
        None
    }
}
