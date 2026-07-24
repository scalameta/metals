package scala.meta.internal.jpc

import com.sun.tools.javac.parser.JavaTokenizer
import com.sun.tools.javac.parser.Scanner
import com.sun.tools.javac.parser.ScannerFactory

// `Scanner`'s constructor is protected; subclass to make it callable.
private[jpc] final class CommentCollectingScanner(
    factory: ScannerFactory,
    tokenizer: JavaTokenizer
) extends Scanner(factory, tokenizer)
