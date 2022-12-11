<<package>>/*keyword*/ <<example>>/*namespace*/

<<import>>/*keyword*/ <<io>>/*namespace*/.<<circe>>/*variable,readonly*/.<<derivation>>/*variable,readonly*/.<<deriveDecoder>>/*variable,readonly*/
<<import>>/*keyword*/ <<io>>/*namespace*/.<<circe>>/*variable,readonly*/.<<derivation>>/*variable,readonly*/.<<deriveEncoder>>/*variable,readonly*/

<<class>>/*keyword*/ <<Definitions>>/*class*/ {
  <<Predef>>/*class*/.<<any2stringadd>>/*method,deprecated*/(<<1>>/*number*/)
  <<List>>/*variable,readonly*/[
    <<java>>/*namespace*/.<<util>>/*namespace*/.<<Map>>/*class*/.<<Entry>>/*interface,abstract*/[
      <<java>>/*namespace*/.<<lang>>/*namespace*/.<<Integer>>/*class*/,
      <<java>>/*namespace*/.<<lang>>/*namespace*/.<<Double>>/*class*/,
    ]
  ](
    <<elems>>/*parameter*/ = <<null>>/*keyword*/
  )
  <<println>>/*method*/(<<deriveDecoder>>/*variable,readonly*/[<<MacroAnnotation>>/*class*/])
  <<println>>/*method*/(<<deriveEncoder>>/*variable,readonly*/[<<MacroAnnotation>>/*class*/])
}