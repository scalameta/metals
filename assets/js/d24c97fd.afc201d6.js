/*! For license information please see d24c97fd.afc201d6.js.LICENSE.txt */
"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[5272],{6047:(e,t,s)=>{s.r(t),s.d(t,{assets:()=>o,contentTitle:()=>i,default:()=>d,frontMatter:()=>l,metadata:()=>a,toc:()=>h});var r=s(4848),n=s(8453);const l={author:"Vadim Chelyshov",title:"Metals v0.11.4 - Aluminium",authorURL:"https://twitter.com/_dos65",authorImageURL:"https://github.com/dos65.png"},i=void 0,a={permalink:"/metals/blog/2022/04/27/aluminium",source:"@site/blog/2022-04-27-aluminium.md",title:"Metals v0.11.4 - Aluminium",description:"We're happy to announce the release of Metals v0.11.4, which includes the hotfix of the issue with cs install metals.",date:"2022-04-27T00:00:00.000Z",tags:[],readingTime:.88,hasTruncateMarker:!1,authors:[{name:"Vadim Chelyshov",url:"https://twitter.com/_dos65",imageURL:"https://github.com/dos65.png",key:null,page:null}],frontMatter:{author:"Vadim Chelyshov",title:"Metals v0.11.4 - Aluminium",authorURL:"https://twitter.com/_dos65",authorImageURL:"https://github.com/dos65.png"},unlisted:!1,prevItem:{title:"Metals v0.11.5 - Aluminium",permalink:"/metals/blog/2022/04/28/aluminium"},nextItem:{title:"Metals v0.11.3 - Aluminium",permalink:"/metals/blog/2022/04/26/aluminium"}},o={authorsImageUrls:[void 0]},h=[{value:"TL;DR",id:"tldr",level:2},{value:"Contributors",id:"contributors",level:2},{value:"Merged PRs",id:"merged-prs",level:2},{value:"v0.11.4 (2022-04-27)",id:"v0114-2022-04-27",level:2}];function c(e){const t={a:"a",code:"code",h2:"h2",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,n.R)(),...e.components};return(0,r.jsxs)(r.Fragment,{children:[(0,r.jsxs)(t.p,{children:["We're happy to announce the release of Metals v0.11.4, which includes the hotfix of ",(0,r.jsxs)(t.a,{href:"https://github.com/coursier/coursier/issues/2406",children:["the issue with ",(0,r.jsx)(t.code,{children:"cs install metals"})]}),"."]}),"\n",(0,r.jsx)("table",{children:(0,r.jsxs)("tbody",{children:[(0,r.jsxs)("tr",{children:[(0,r.jsx)("td",{children:"Commits since last release"}),(0,r.jsx)("td",{align:"center",children:"3"})]}),(0,r.jsxs)("tr",{children:[(0,r.jsx)("td",{children:"Merged PRs"}),(0,r.jsx)("td",{align:"center",children:"3"})]}),(0,r.jsxs)("tr",{children:[(0,r.jsx)("td",{children:"Contributors"}),(0,r.jsx)("td",{align:"center",children:"2"})]}),(0,r.jsxs)("tr",{children:[(0,r.jsx)("td",{children:"Closed issues"}),(0,r.jsx)("td",{align:"center",children:"1"})]}),(0,r.jsxs)("tr",{children:[(0,r.jsx)("td",{children:"New features"}),(0,r.jsx)("td",{align:"center",children:"0"})]})]})}),"\n",(0,r.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Vim, Emacs and\nSublime Text. Metals is developed at the\n",(0,r.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"})," and ",(0,r.jsx)(t.a,{href:"https://virtuslab.com",children:"VirtusLab"}),"\nwith the help from ",(0,r.jsx)(t.a,{href:"https://lunatech.com",children:"Lunatech"})," along with contributors from\nthe community."]}),"\n",(0,r.jsx)(t.h2,{id:"tldr",children:"TL;DR"}),"\n",(0,r.jsxs)(t.p,{children:["Check out ",(0,r.jsx)(t.a,{href:"https://scalameta.org/metals/",children:"https://scalameta.org/metals/"}),", and\ngive Metals a try!"]}),"\n",(0,r.jsxs)(t.ul,{children:["\n",(0,r.jsx)(t.li,{children:"Fix the issue with installing Metals using coursier"}),"\n"]}),"\n",(0,r.jsx)(t.h2,{id:"contributors",children:"Contributors"}),"\n",(0,r.jsx)(t.p,{children:"Big thanks to everybody who contributed to this release or reported an issue!"}),"\n",(0,r.jsx)(t.pre,{children:(0,r.jsx)(t.code,{children:"$ git shortlog -sn --no-merges v0.11.3..v0.11.4\n2\tKamil Podsiad\u0142o\n1\tVadim Chelyshov\n"})}),"\n",(0,r.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,r.jsxs)(t.h2,{id:"v0114-2022-04-27",children:[(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/tree/v0.11.4",children:"v0.11.4"})," (2022-04-27)"]}),"\n",(0,r.jsx)(t.p,{children:(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/compare/v0.11.3...v0.11.4",children:"Full Changelog"})}),"\n",(0,r.jsx)(t.p,{children:(0,r.jsx)(t.strong,{children:"Merged pull requests:"})}),"\n",(0,r.jsxs)(t.ul,{children:["\n",(0,r.jsxs)(t.li,{children:["upgrade bsp4j version to a stable one\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3872",children:"#3872"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["chore: update server version after release\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3869",children:"#3869"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["docs: release notes\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3860",children:"#3860"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n"]})]})}function d(e={}){const{wrapper:t}={...(0,n.R)(),...e.components};return t?(0,r.jsx)(t,{...e,children:(0,r.jsx)(c,{...e})}):c(e)}},1020:(e,t,s)=>{var r=s(6540),n=Symbol.for("react.element"),l=Symbol.for("react.fragment"),i=Object.prototype.hasOwnProperty,a=r.__SECRET_INTERNALS_DO_NOT_USE_OR_YOU_WILL_BE_FIRED.ReactCurrentOwner,o={key:!0,ref:!0,__self:!0,__source:!0};function h(e,t,s){var r,l={},h=null,c=null;for(r in void 0!==s&&(h=""+s),void 0!==t.key&&(h=""+t.key),void 0!==t.ref&&(c=t.ref),t)i.call(t,r)&&!o.hasOwnProperty(r)&&(l[r]=t[r]);if(e&&e.defaultProps)for(r in t=e.defaultProps)void 0===l[r]&&(l[r]=t[r]);return{$$typeof:n,type:e,key:h,ref:c,props:l,_owner:a.current}}t.Fragment=l,t.jsx=h,t.jsxs=h},4848:(e,t,s)=>{e.exports=s(1020)},8453:(e,t,s)=>{s.d(t,{R:()=>i,x:()=>a});var r=s(6540);const n={},l=r.createContext(n);function i(e){const t=r.useContext(l);return r.useMemo((function(){return"function"==typeof e?e(t):{...t,...e}}),[t,e])}function a(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(n):e.components||n:i(e.components),r.createElement(l.Provider,{value:t},e.children)}}}]);