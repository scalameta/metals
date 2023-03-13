<<package>>/*keyword*/ <<example>>/*namespace*/

<<import>>/*keyword*/ <<io>>/*namespace*/.<<circe>>/*namespace*/.<<derivation>>/*namespace*/.<<annotations>>/*namespace*/.<<JsonCodec>>/*class*/

<<@>>/*keyword*/<<JsonCodec>>/*class*/
<<// FIXME: https://github.com/scalameta/scalameta/issues/1789>>/*comment*/
<<case>>/*keyword*/ <<class>>/*keyword*/ <<MacroAnnotation>>/*class*/(
    <<name>>/*variable,declaration,readonly*/: <<String>>/*type*/
) {
  <<def>>/*keyword*/ <<method>>/*method,definition*/ = <<42>>/*number*/
}

<<object>>/*keyword*/ <<MacroAnnotations>>/*class*/ {
  <<import>>/*keyword*/ <<scala>>/*namespace*/.<<meta>>/*namespace*/.<<_>>/*variable*/
  <<// IntelliJ has never managed to goto definition for the inner classes from Trees.scala>>/*comment*/
  <<// due to the macro annotations.>>/*comment*/
  <<val>>/*keyword*/ <<x>>/*variable,definition,readonly*/: <<Defn>>/*class*/.<<Class>>/*interface,abstract*/ = <<Defn>>/*class*/.<<Class>>/*class*/(
    <<Nil>>/*variable,readonly*/,
    <<Type>>/*class*/.<<Name>>/*class*/(<<"test">>/*string*/),
    <<Nil>>/*variable,readonly*/,
    <<Ctor>>/*class*/.<<Primary>>/*class*/(<<Nil>>/*variable,readonly*/, <<Term>>/*class*/.<<Name>>/*class*/(<<"this">>/*string*/), <<Nil>>/*variable,readonly*/),
    <<Template>>/*class*/(<<Nil>>/*variable,readonly*/, <<Nil>>/*variable,readonly*/, <<Self>>/*class*/(<<Name>>/*class*/.<<Anonymous>>/*class*/(), <<None>>/*class*/), <<Nil>>/*variable,readonly*/),
  )
  <<val>>/*keyword*/ <<y>>/*variable,definition,readonly*/: <<Mod>>/*class*/.<<Final>>/*interface,abstract*/ = <<Mod>>/*class*/.<<Final>>/*class*/()
}