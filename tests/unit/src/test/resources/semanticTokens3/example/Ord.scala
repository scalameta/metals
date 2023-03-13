<<package>>/*keyword*/ <<example>>/*namespace*/

<<trait>>/*keyword*/ <<Ord>>/*interface,abstract*/[<<T>>/*typeParameter,definition,abstract*/]:
  <<def>>/*keyword*/ <<compare>>/*method,declaration*/(<<x>>/*parameter,declaration*/: <<T>>/*typeParameter,abstract*/, <<y>>/*parameter,declaration*/: <<T>>/*typeParameter,abstract*/): <<Int>>/*class,abstract*/

<<given>>/*keyword*/ <<intOrd>>/*class*/: <<Ord>>/*interface,abstract*/[<<Int>>/*class,abstract*/] <<with>>/*keyword*/
  <<def>>/*keyword*/ <<compare>>/*method,definition*/(<<x>>/*parameter,declaration*/: <<Int>>/*class,abstract*/, <<y>>/*parameter,declaration*/: <<Int>>/*class,abstract*/) =
    <<if>>/*keyword*/ <<x>>/*parameter*/ <<<>>/*method*/ <<y>>/*parameter*/ <<then>>/*keyword*/ -<<1>>/*number*/ <<else>>/*keyword*/ <<if>>/*keyword*/ <<x>>/*parameter*/ <<>>>/*method*/ <<y>>/*parameter*/ <<then>>/*keyword*/ +<<1>>/*number*/ <<else>>/*keyword*/ <<0>>/*number*/

<<given>>/*keyword*/ <<Ord>>/*interface,abstract*/[<<String>>/*type*/] <<with>>/*keyword*/
  <<def>>/*keyword*/ <<compare>>/*method,definition*/(<<x>>/*parameter,declaration*/: <<String>>/*type*/, <<y>>/*parameter,declaration*/: <<String>>/*type*/) =
    <<x>>/*parameter*/.<<compare>>/*method*/(<<y>>/*parameter*/)