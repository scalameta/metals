<<package>>/*keyword*/ <<example>>/*namespace*/

<<extension>>/*keyword*/ (<<i>>/*parameter*/: <<Int>>/*class,abstract*/) <<def>>/*keyword*/ <<asString>>/*method*/: <<String>>/*type*/ = <<i>>/*parameter*/.<<toString>>/*method*/

<<extension>>/*keyword*/ (<<s>>/*parameter*/: <<String>>/*type*/)
  <<def>>/*keyword*/ <<asInt>>/*method*/: <<Int>>/*class,abstract*/ = <<s>>/*parameter*/.<<toInt>>/*method*/
  <<def>>/*keyword*/ <<double>>/*method*/: <<String>>/*type*/ = <<s>>/*parameter*/ <<*>>/*method*/ <<2>>/*number*/

<<trait>>/*keyword*/ <<AbstractExtension>>/*interface,abstract*/:
  <<extension>>/*keyword*/ (<<d>>/*parameter*/: <<Double>>/*class,abstract*/)
    <<def>>/*keyword*/ <<abc>>/*method*/: <<String>>/*type*/