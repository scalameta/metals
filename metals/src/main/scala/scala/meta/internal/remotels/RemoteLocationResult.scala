package scala.meta.internal.remotels

import java.{util => ju}
import org.eclipse.{lsp4j => l}

class RemoteLocationResult(val result: ju.List[l.Location])
