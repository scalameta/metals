<<package>>/*keyword*/ <<example>>/*namespace*/

<<// inline keyword>>/*comment*/
<<inline>>/*keyword*/ <<def>>/*keyword*/ <<inlineMethod>>/*method,definition*/(<<x>>/*parameter,declaration,readonly*/: <<Int>>/*class,abstract*/): <<Int>>/*class,abstract*/ = <<x>>/*parameter,readonly*/ <<+>>/*method*/ <<1>>/*number*/

<<inline>>/*keyword*/ <<val>>/*keyword*/ <<inlineVal>>/*variable,definition,readonly*/ = <<42>>/*number*/

<<// opaque type>>/*comment*/
<<opaque>>/*keyword*/ <<type>>/*keyword*/ <<OpaqueInt>>/*type,definition,abstract*/ = <<Int>>/*class,abstract*/

<<// open class>>/*comment*/
<<open>>/*keyword*/ <<class>>/*keyword*/ <<OpenClass>>/*class*/

<<// transparent trait>>/*comment*/
<<transparent>>/*keyword*/ <<trait>>/*keyword*/ <<TransparentTrait>>/*interface,abstract*/

<<// infix method>>/*comment*/
<<class>>/*keyword*/ <<InfixExample>>/*class*/:
  <<infix>>/*keyword*/ <<def>>/*keyword*/ <<combine>>/*method,definition*/(<<other>>/*parameter,declaration,readonly*/: <<InfixExample>>/*class*/): <<InfixExample>>/*class*/ = <<this>>/*keyword*/

<<// derives clause>>/*comment*/
<<case>>/*keyword*/ <<class>>/*keyword*/ <<Point>>/*class*/(<<x>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/, <<y>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/) <<derives>>/*keyword*/ CanEqual

<<// as in import rename>>/*comment*/
<<import>>/*keyword*/ <<scala>>/*namespace*/.<<collection>>/*namespace*/.<<mutable>>/*namespace*/.<<ListBuffer>>/*class*/ <<as>>/*keyword*/ <<MutableList>>/*class*/