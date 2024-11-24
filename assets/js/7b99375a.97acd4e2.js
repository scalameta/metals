"use strict";(self.webpackChunk=self.webpackChunk||[]).push([["6671"],{6881:function(e,t,s){s.r(t),s.d(t,{assets:function(){return o},contentTitle:function(){return r},default:function(){return d},frontMatter:function(){return a},metadata:function(){return l},toc:function(){return h}});var l=s(535),i=s(5893),n=s(65);let a={authors:"tgodzik",title:"Metals v1.3.5 - Thallium"},r=void 0,o={authorsImageUrls:[void 0]},h=[{value:"TL;DR",id:"tldr",level:2},{value:"Disable best effort compilation",id:"disable-best-effort-compilation",level:2},{value:"Contributors",id:"contributors",level:2},{value:"Miscellaneous",id:"miscellaneous",level:2},{value:"Merged PRs",id:"merged-prs",level:2},{value:"v1.3.5 (2024-08-01)",id:"v135-2024-08-01",level:2}];function c(e){let t={a:"a",code:"code",h2:"h2",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,n.a)(),...e.components};return(0,i.jsxs)(i.Fragment,{children:[(0,i.jsx)(t.p,{children:"Metals v1.3.5 is a bugfix release and it's main purpose is to disable best\neffort compilation for the time being."}),"\n",(0,i.jsx)(t.p,{children:"This version also removed support for some of Scala 2.12 and 2.13 versions. We\nwill keep releasing Metals for the last 4 versions in the 2.12.x and 2.13.x\nlines. This means that older versions will not keep getting bugfixes or new\nfeatures, which are version specific, but Metals will keep working for them."}),"\n",(0,i.jsx)("table",{children:(0,i.jsxs)("tbody",{children:[(0,i.jsxs)("tr",{children:[(0,i.jsx)("td",{children:"Commits since last release"}),(0,i.jsx)("td",{align:"center",children:"24"})]}),(0,i.jsxs)("tr",{children:[(0,i.jsx)("td",{children:"Merged PRs"}),(0,i.jsx)("td",{align:"center",children:"14"})]}),(0,i.jsxs)("tr",{children:[(0,i.jsx)("td",{children:"Contributors"}),(0,i.jsx)("td",{align:"center",children:"4"})]}),(0,i.jsxs)("tr",{children:[(0,i.jsx)("td",{children:"Closed issues"}),(0,i.jsx)("td",{align:"center",children:"3"})]}),(0,i.jsxs)("tr",{children:[(0,i.jsx)("td",{children:"New features"}),(0,i.jsx)("td",{align:"center",children:"0"})]})]})}),"\n",(0,i.jsxs)(t.p,{children:["For full details:\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/71?closed=1",children:"https://github.com/scalameta/metals/milestone/71?closed=1"})]}),"\n",(0,i.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Vim, Emacs, Zed,\nHelix and Sublime Text. Metals is developed at the\n",(0,i.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"})," and ",(0,i.jsx)(t.a,{href:"https://virtuslab.com",children:"VirtusLab"}),"\nwith the help from contributors from the community."]}),"\n",(0,i.jsx)(t.h2,{id:"tldr",children:"TL;DR"}),"\n",(0,i.jsxs)(t.p,{children:["Check out ",(0,i.jsx)(t.a,{href:"https://scalameta.org/metals/",children:"https://scalameta.org/metals/"}),", and\ngive Metals a try!"]}),"\n",(0,i.jsxs)(t.ul,{children:["\n",(0,i.jsx)(t.li,{children:(0,i.jsx)(t.a,{href:"#disable-best-effort-compilation",children:"Disable best effort compilation"})}),"\n"]}),"\n",(0,i.jsx)(t.h2,{id:"disable-best-effort-compilation",children:"Disable best effort compilation"}),"\n",(0,i.jsx)(t.p,{children:"Some users when testing the newest RC of Scala 3.5.0, which supports best effort\ncompilation, reported that Metals show some errors in the code, which is not the\ncase for Scala 3.4.x or when using sbt."}),"\n",(0,i.jsxs)(t.p,{children:["This will be most likely fixed later on, but for now, we decided to disable best\neffort by default. If you want to still test it out you need to start Metals\nwith ",(0,i.jsx)(t.code,{children:"-Dmetals.enable-best-effort=true"})," or put that into\n",(0,i.jsx)(t.code,{children:"metals.serverProperties"})," in case of VS Code."]}),"\n",(0,i.jsx)(t.h2,{id:"contributors",children:"Contributors"}),"\n",(0,i.jsx)(t.p,{children:"Big thanks to everybody who contributed to this release or reported an issue!"}),"\n",(0,i.jsx)(t.pre,{children:(0,i.jsx)(t.code,{children:"$ git shortlog -sn --no-merges v1.3.4..v1.3.5\n     14	Tomasz Godzik\n     7	Scalameta Bot\n     2	Katarzyna Marek\n     1	tgodzik\n"})}),"\n",(0,i.jsx)(t.h2,{id:"miscellaneous",children:"Miscellaneous"}),"\n",(0,i.jsxs)(t.ul,{children:["\n",(0,i.jsxs)(t.li,{children:["fix: don't suggest completions for param names in definition\n",(0,i.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n"]}),"\n",(0,i.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,i.jsxs)(t.h2,{id:"v135-2024-08-01",children:[(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/tree/v1.3.5",children:"v1.3.5"})," (2024-08-01)"]}),"\n",(0,i.jsx)(t.p,{children:(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/compare/v1.3.4...v1.3.5",children:"Full Changelog"})}),"\n",(0,i.jsx)(t.p,{children:(0,i.jsx)(t.strong,{children:"Merged pull requests:"})}),"\n",(0,i.jsxs)(t.ul,{children:["\n",(0,i.jsxs)(t.li,{children:["bugfix: Disable best effort compilation by default\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6644",children:"#6644"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["chore: Bump scalameta to 4.9.9\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6626",children:"#6626"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["improvement: Timeout when resolving mtags for a long time\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6636",children:"#6636"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["improvement: Fall back to current Scala version in unit tests\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6638",children:"#6638"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["fix: don't suggest completions for param names in definition\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6620",children:"#6620"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["build(deps): Update ujson from 3.3.1 to 4.0.0\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6634",children:"#6634"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["build(deps): Update requests from 0.8.3 to 0.9.0\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6633",children:"#6633"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["build(deps): Update metaconfig-core from 0.12.0 to 0.13.0\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6631",children:"#6631"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["build(deps): Update scalafmt-core from 3.8.2 to 3.8.3\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6635",children:"#6635"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["improvement: Also run from Java when running from config\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6306",children:"#6306"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["docs: release notes v1.3.4 [skip ci]\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6619",children:"#6619"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["build(deps): Update mill-contrib-testng from 0.11.8 to 0.11.9\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6611",children:"#6611"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["improvement: Move jdt dependencies outside of build.sbt\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6577",children:"#6577"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["chore: Bump scalameta to 4.9.8\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6570",children:"#6570"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n"]})]})}function d(e={}){let{wrapper:t}={...(0,n.a)(),...e.components};return t?(0,i.jsx)(t,{...e,children:(0,i.jsx)(c,{...e})}):c(e)}},65:function(e,t,s){s.d(t,{Z:function(){return r},a:function(){return a}});var l=s(7294);let i={},n=l.createContext(i);function a(e){let t=l.useContext(n);return l.useMemo(function(){return"function"==typeof e?e(t):{...t,...e}},[t,e])}function r(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(i):e.components||i:a(e.components),l.createElement(n.Provider,{value:t},e.children)}},535:function(e){e.exports=JSON.parse('{"permalink":"/metals/blog/2024/08/01/thallium","source":"@site/blog/2024-08-01-thallium.md","title":"Metals v1.3.5 - Thallium","description":"Metals v1.3.5 is a bugfix release and it\'s main purpose is to disable best","date":"2024-08-01T00:00:00.000Z","tags":[],"readingTime":2.25,"hasTruncateMarker":false,"authors":[{"name":"Tomasz Godzik","url":"https://twitter.com/TomekGodzik","imageURL":"https://github.com/tgodzik.png","key":"tgodzik","page":null}],"frontMatter":{"authors":"tgodzik","title":"Metals v1.3.5 - Thallium"},"unlisted":false,"prevItem":{"title":"Metals v1.4.0 - Palladium","permalink":"/metals/blog/2024/10/24/palladium"},"nextItem":{"title":"Metals v1.3.4 - Thallium","permalink":"/metals/blog/2024/07/24/thallium"}}')}}]);