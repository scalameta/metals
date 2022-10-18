<<package>>/*keyword*/ <<example>>/*namespace*/

<<object>>/*keyword*/ <<StructuralTypes>>/*class*/ {
  <<type>>/*keyword*/ <<User>>/*type*/ = {
    <<def>>/*keyword*/ <<name>>/*method,abstract*/: String/*type*/
    <<def>>/*keyword*/ <<age>>/*method,abstract*/: Int/*class,abstract*/
  }

  <<val>>/*keyword*/ <<user>>/*variable,readonly*/ = <<null>>/*keyword*/.<<asInstanceOf>>/*method*/[<<User>>/*type*/]
  <<user>>/*variable,readonly*/.<<name>>/*method,abstract*/
  <<user>>/*variable,readonly*/.<<age>>/*method,abstract*/

  <<val>>/*keyword*/ <<V>>/*variable,readonly*/: <<Object>>/*class,abstract*/ {
    <<def>>/*keyword*/ <<scalameta>>/*method,abstract*/: <<String>>/*type*/
  } = <<new>>/*keyword*/ {
    <<def>>/*keyword*/ <<scalameta>>/*method*/ = <<"4.0">>/*string*/
  }
  <<V>>/*variable,readonly*/.<<scalameta>>/*method,abstract*/
}