package tests

import java.nio.file.StandardCopyOption

import scala.meta.internal.metals.MetalsEnrichments._

import org.eclipse.lsp4j.CreateFile
import org.eclipse.lsp4j.CreateFileOptions
import org.eclipse.lsp4j.DeleteFile
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.RenameFileOptions
import org.eclipse.lsp4j.ResourceOperation

/**
 * Client implementation of how to interpret `ResourceOperation` from LSP, used for testing purposes.
 */
object ResourceOperations {

  def applyResourceOperation(resourceOperation: ResourceOperation): Unit = {
    resourceOperation match {
      case operation: CreateFile =>
        createFile(operation)
      case operation: RenameFile =>
        renameFile(operation)
      case operation: DeleteFile =>
        deleteFile(operation)
      case _ =>
    }
  }

  /**
   * This method should implement how to interpret the CreateFile operation
   * there is no guarantee that is compliant with the LSP specs
   * @param operation CreateFile operation
   */
  def createFile(operation: CreateFile): Unit = {
    val uri = operation.getUri
    val options: Option[CreateFileOptions] = Option(operation.getOptions)

    val overwrite =
      options
        .map(opt => opt.getOverwrite: Boolean)
        .getOrElse(false)
    val ignoreIfExists = options
      .map(opt => opt.getIgnoreIfExists: Boolean)
      .getOrElse(true.booleanValue())

    val path = uri.toAbsolutePath
    val fileExists = path.exists

    if (fileExists && !ignoreIfExists && overwrite) {
      path.writeText("")
    } else if (!fileExists) {
      path.touch()
    }

  }

  def renameFile(operation: RenameFile): Unit = {
    val oldUri = operation.getOldUri
    val newUri = operation.getNewUri
    val options: Option[RenameFileOptions] = Option(operation.getOptions)

    val overwrite = options
      .map(opt => opt.getOverwrite: Boolean)
      .getOrElse(false)
    val ignoreIfExists = options
      .map(opt => opt.getOverwrite: Boolean)
      .getOrElse(false)

    val oldPath = oldUri.toAbsolutePath
    val newPath = newUri.toAbsolutePath
    val fileExists = newPath.exists

    if (fileExists && !ignoreIfExists && overwrite) {
      oldPath.move(newPath, Some(StandardCopyOption.REPLACE_EXISTING))
    } else {
      oldPath.move(newPath, None)
    }
  }

  /**
   * This method definitely doesn't follow the DeleteFile operations' specs
   * not taking into account the DeleteFileOptions
   */
  def deleteFile(operation: DeleteFile): Unit = {
    val uri = operation.getUri
    val path = uri.toAbsolutePath
    path.delete()
  }

}
