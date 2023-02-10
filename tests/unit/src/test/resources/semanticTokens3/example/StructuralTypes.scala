<<package>>/*keyword*/ <<example>>/*namespace*/

<<import>>/*keyword*/ <<reflect>>/*namespace*/.<<Selectable>>/*class*/.<<reflectiveSelectable>>/*method*/

<<object>>/*keyword*/ <<StructuralTypes>>/*class*/:
  <<type>>/*keyword*/ <<User>>/*type*/ = {
    <<def>>/*keyword*/ <<name>>/*method*/: <<String>>/*type*/
    <<def>>/*keyword*/ <<age>>/*method*/: <<Int>>/*class,abstract*/
  }

  <<val>>/*keyword*/ <<user>>/*variable,readonly*/ = <<null>>/*keyword*/.<<asInstanceOf>>/*method*/[<<User>>/*type*/]
  <<user>>/*variable,readonly*/.name
  <<user>>/*variable,readonly*/.age

  <<val>>/*keyword*/ <<V>>/*variable,readonly*/: <<Object>>/*class*/ {
    <<def>>/*keyword*/ <<scalameta>>/*method*/: <<String>>/*type*/
  } = <<new>>/*keyword*/:
    <<def>>/*keyword*/ <<scalameta>>/*method*/ = <<"4.0">>/*string*/
  <<V>>/*variable,readonly*/.scalameta
<<end>>/*keyword*/ StructuralTypes