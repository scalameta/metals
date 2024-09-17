/*! For license information please see fc2f7557.84cc975c.js.LICENSE.txt */
"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[1474],{5335:(e,t,s)=>{s.r(t),s.d(t,{assets:()=>h,contentTitle:()=>i,default:()=>d,frontMatter:()=>l,metadata:()=>a,toc:()=>c});var r=s(4848),n=s(8453);const l={author:"Jakub Ciesluk",title:"Metals v1.2.2 - Bismuth",authorURL:"https://github.com/jkciesluk",authorImageURL:"https://github.com/jkciesluk.png"},i=void 0,a={permalink:"/metals/blog/2024/02/15/bismuth",source:"@site/blog/2024-02-15-bismuth.md",title:"Metals v1.2.2 - Bismuth",description:"We're happy to announce the release of Metals v1.2.2, which fixes broken sbt builds on Windows.",date:"2024-02-15T00:00:00.000Z",tags:[],readingTime:1.52,hasTruncateMarker:!1,authors:[{name:"Jakub Ciesluk",url:"https://github.com/jkciesluk",imageURL:"https://github.com/jkciesluk.png",key:null,page:null}],frontMatter:{author:"Jakub Ciesluk",title:"Metals v1.2.2 - Bismuth",authorURL:"https://github.com/jkciesluk",authorImageURL:"https://github.com/jkciesluk.png"},unlisted:!1,prevItem:{title:"Metals v1.3.0 - Thallium",permalink:"/metals/blog/2024/04/15/thalium"},nextItem:{title:"Metals v1.2.1 - Bismuth",permalink:"/metals/blog/2024/02/07/bismuth"}},h={authorsImageUrls:[void 0]},c=[{value:"TL;DR",id:"tldr",level:2},{value:"Fix sbt builds on Windows",id:"fix-sbt-builds-on-windows",level:2},{value:"Miscellaneous",id:"miscellaneous",level:2},{value:"Contributors",id:"contributors",level:2},{value:"Merged PRs",id:"merged-prs",level:2},{value:"v1.2.2 (2024-02-15)",id:"v122-2024-02-15",level:2}];function o(e){const t={a:"a",code:"code",h2:"h2",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,n.R)(),...e.components};return(0,r.jsxs)(r.Fragment,{children:[(0,r.jsx)(t.p,{children:"We're happy to announce the release of Metals v1.2.2, which fixes broken sbt builds on Windows."}),"\n",(0,r.jsx)("table",{children:(0,r.jsxs)("tbody",{children:[(0,r.jsxs)("tr",{children:[(0,r.jsx)("td",{children:"Commits since last release"}),(0,r.jsx)("td",{align:"center",children:"9"})]}),(0,r.jsxs)("tr",{children:[(0,r.jsx)("td",{children:"Merged PRs"}),(0,r.jsx)("td",{align:"center",children:"9"})]}),(0,r.jsxs)("tr",{children:[(0,r.jsx)("td",{children:"Contributors"}),(0,r.jsx)("td",{align:"center",children:"4"})]}),(0,r.jsxs)("tr",{children:[(0,r.jsx)("td",{children:"Closed issues"}),(0,r.jsx)("td",{align:"center",children:"4"})]})]})}),"\n",(0,r.jsxs)(t.p,{children:["For full details: ",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/64?closed=1",children:"https://github.com/scalameta/metals/milestone/64?closed=1"})]}),"\n",(0,r.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Vim, Emacs and\nSublime Text. Metals is developed at the\n",(0,r.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"})," and ",(0,r.jsx)(t.a,{href:"https://virtuslab.com",children:"VirtusLab"}),"\nwith the help from contributors from the community."]}),"\n",(0,r.jsx)(t.h2,{id:"tldr",children:"TL;DR"}),"\n",(0,r.jsxs)(t.p,{children:["Check out ",(0,r.jsx)(t.a,{href:"https://scalameta.org/metals/",children:"https://scalameta.org/metals/"}),", and\ngive Metals a try!"]}),"\n",(0,r.jsxs)(t.ul,{children:["\n",(0,r.jsx)(t.li,{children:(0,r.jsx)(t.a,{href:"#fix-sbt-builds-on-windows",children:"Fix sbt builds on Windows"})}),"\n"]}),"\n",(0,r.jsx)(t.h2,{id:"fix-sbt-builds-on-windows",children:"Fix sbt builds on Windows"}),"\n",(0,r.jsx)(t.p,{children:"Previous release unintentionally stopped some sbt projects on Windows from importing. In this release we revert the breaking changes."}),"\n",(0,r.jsx)(t.h2,{id:"miscellaneous",children:"Miscellaneous"}),"\n",(0,r.jsxs)(t.ul,{children:["\n",(0,r.jsxs)(t.li,{children:["bugfix: Deduplicate references results. ",(0,r.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n",(0,r.jsxs)(t.li,{children:["bugfix: Don't rename package when it did not change. ",(0,r.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n",(0,r.jsxs)(t.li,{children:["bugfix: properly resolve build targets for references search. ",(0,r.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n",(0,r.jsxs)(t.li,{children:["bugfix: Allow Scala 2.11 to be run on newer JVMs. ",(0,r.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"})]}),"\n"]}),"\n",(0,r.jsx)(t.h2,{id:"contributors",children:"Contributors"}),"\n",(0,r.jsx)(t.p,{children:"Big thanks to everybody who contributed to this release or reported an issue!"}),"\n",(0,r.jsx)(t.pre,{children:(0,r.jsx)(t.code,{children:"$ git shortlog -sn --no-merges v1.2.1..v1.2.2\n5\tKatarzyna Marek\n     2\tScalameta Bot\n     1\tJakub Ciesluk\n     1\tTomasz Godzik\n"})}),"\n",(0,r.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,r.jsxs)(t.h2,{id:"v122-2024-02-15",children:[(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/tree/v1.2.2",children:"v1.2.2"})," (2024-02-15)"]}),"\n",(0,r.jsx)(t.p,{children:(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/compare/v1.2.1...v1.2.2",children:"Full Changelog"})}),"\n",(0,r.jsx)(t.p,{children:(0,r.jsx)(t.strong,{children:"Merged pull requests:"})}),"\n",(0,r.jsxs)(t.ul,{children:["\n",(0,r.jsxs)(t.li,{children:["bugfix: deduplicate references results\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6116",children:"#6116"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["bugfix: don't rename package when it did not change\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6115",children:"#6115"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["chore: Fix CI after changes in dotty\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6113",children:"#6113"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"jkciesluk"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["bugfix: properly resolve build targets for references search\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6103",children:"#6103"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["bugfix: revert back to using embedded ",(0,r.jsx)(t.code,{children:"sbt"})," launcher for Windows\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6111",children:"#6111"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["build(deps): Update coursier, ... from 2.1.8 to 2.1.9\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6106",children:"#6106"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["build(deps): Update mill-contrib-testng from 0.11.6 to 0.11.7\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6105",children:"#6105"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["bugfix: Allow Scala 2.11 to be run on newer JVMs\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6092",children:"#6092"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["release notes 1.2.1\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6091",children:"#6091"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n"]})]})}function d(e={}){const{wrapper:t}={...(0,n.R)(),...e.components};return t?(0,r.jsx)(t,{...e,children:(0,r.jsx)(o,{...e})}):o(e)}},1020:(e,t,s)=>{var r=s(6540),n=Symbol.for("react.element"),l=Symbol.for("react.fragment"),i=Object.prototype.hasOwnProperty,a=r.__SECRET_INTERNALS_DO_NOT_USE_OR_YOU_WILL_BE_FIRED.ReactCurrentOwner,h={key:!0,ref:!0,__self:!0,__source:!0};function c(e,t,s){var r,l={},c=null,o=null;for(r in void 0!==s&&(c=""+s),void 0!==t.key&&(c=""+t.key),void 0!==t.ref&&(o=t.ref),t)i.call(t,r)&&!h.hasOwnProperty(r)&&(l[r]=t[r]);if(e&&e.defaultProps)for(r in t=e.defaultProps)void 0===l[r]&&(l[r]=t[r]);return{$$typeof:n,type:e,key:c,ref:o,props:l,_owner:a.current}}t.Fragment=l,t.jsx=c,t.jsxs=c},4848:(e,t,s)=>{e.exports=s(1020)},8453:(e,t,s)=>{s.d(t,{R:()=>i,x:()=>a});var r=s(6540);const n={},l=r.createContext(n);function i(e){const t=r.useContext(l);return r.useMemo((function(){return"function"==typeof e?e(t):{...t,...e}}),[t,e])}function a(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(n):e.components||n:i(e.components),r.createElement(l.Provider,{value:t},e.children)}}}]);