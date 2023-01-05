<<package>>/*keyword*/ <<example>>/*namespace*/

<<import>>/*keyword*/ <<io>>/*namespace*/.<<circe>>/*variable,readonly*/.<<derivation>>/*variable,readonly*/.<<annotations>>/*variable,readonly*/.<<JsonCodec>>/*variable,readonly*/

<<@>>/*keyword*/<<JsonCodec>>/*class*/
<<// FIXME: https://github.com/scalameta/scalameta/issues/1789>>/*comment*/
<<case>>/*keyword*/ <<class>>/*keyword*/ <<MacroAnnotation>>/*class*/(
    <<name>>/*variable,readonly*/: <<String>>/*type*/
) {
  <<def>>/*keyword*/ <<method>>/*method*/ = <<42>>/*number*/
}

<<object>>/*keyword*/ <<MacroAnnotations>>/*class*/ {
  <<import>>/*keyword*/ <<scala>>/*namespace*/.<<meta>>/*namespace*/.<<_>>/*variable*/
  <<// IntelliJ has never managed to goto definition for the inner classes from Trees.scala>>/*comment*/
  <<// due to the macro annotations.>>/*comment*/
  <<val>>/*keyword*/ <<x>>/*variable,readonly*/: <<Defn>>/*class*/.<<Class>>/*interface,abstract*/ = <<Defn>>/*class*/.<<Class>>/*method,deprecated*/(
    <<Nil>>/*variable,readonly*/,
    <<Type>>/*class*/.<<Name>>/*class*/(<<"test">>/*string*/),
    <<Nil>>/*variable,readonly*/,
    <<Ctor>>/*class*/.<<Primary>>/*class*/(<<Nil>>/*variable,readonly*/, <<Term>>/*class*/.<<Name>>/*class*/(<<"this">>/*string*/), <<Nil>>/*variable,readonly*/),
    <<Template>>/*class*/(<<Nil>>/*variable,readonly*/, <<Nil>>/*variable,readonly*/, <<Self>>/*class*/(<<Name>>/*class*/.<<Anonymous>>/*class*/(), <<None>>/*class*/), <<Nil>>/*variable,readonly*/),
  )
  <<val>>/*keyword*/ <<y>>/*variable,readonly*/: <<Mod>>/*class*/.<<Final>>/*interface,abstract*/ = <<Mod>>/*class*/.<<Final>>/*method*/()
}