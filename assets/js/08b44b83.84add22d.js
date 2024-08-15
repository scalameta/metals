"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[2308],{6887:(e,t,s)=>{s.r(t),s.d(t,{assets:()=>l,contentTitle:()=>o,default:()=>h,frontMatter:()=>r,metadata:()=>a,toc:()=>c});var i=s(4848),n=s(8453);const r={id:"scripts",title:"Scripts support"},o=void 0,a={id:"features/scripts",title:"Scripts support",description:"Working with a script",source:"@site/target/docs/features/scripts.md",sourceDirName:"features",slug:"/features/scripts",permalink:"/metals/docs/features/scripts",draft:!1,unlisted:!1,editUrl:"https://github.com/scalameta/metals/edit/main/docs/features/scripts.md",tags:[],version:"current",frontMatter:{id:"scripts",title:"Scripts support"},sidebar:"docs",previous:{title:"Code Actions",permalink:"/metals/docs/features/codeactions"},next:{title:"Overview",permalink:"/metals/docs/build-tools/overview"}},l={},c=[{value:"Working with a script",id:"working-with-a-script",level:3},{value:"Advanced information",id:"advanced-information",level:3}];function d(e){const t={a:"a",code:"code",h3:"h3",img:"img",li:"li",p:"p",ul:"ul",...(0,n.R)(),...e.components};return(0,i.jsxs)(i.Fragment,{children:[(0,i.jsx)(t.h3,{id:"working-with-a-script",children:"Working with a script"}),"\n",(0,i.jsxs)(t.p,{children:["Whenever, Metals opens a script file with ",(0,i.jsx)(t.code,{children:"*.sc"})," extension, but only when it's\nnot ",(0,i.jsx)(t.code,{children:"*.worksheet.sc"})," or a build server has not already started for it, users will\nbe prompted to choose whether they want to use ",(0,i.jsx)(t.a,{href:"http://ammonite.io/",children:"Ammonite"}),"\nor ",(0,i.jsx)(t.a,{href:"https://scala-cli.virtuslab.org/",children:"Scala CLI"})," to power the script."]}),"\n",(0,i.jsx)(t.p,{children:(0,i.jsx)(t.img,{src:"https://i.imgur.com/ghR1Src.gif",alt:"scala-cli"})}),"\n",(0,i.jsx)(t.p,{children:"Scripts are usually used for smaller programs and the main difference is that\nstatements can be put on top level without a need for additional objects or\nmethods."}),"\n",(0,i.jsx)(t.p,{children:"Once a user has chosen a specific scripting tool, Metals will also suggest to\nimport all newly opened scripts automatically or to always to do that manually."}),"\n",(0,i.jsx)(t.p,{children:"Both tools will start a build server in the background that will be able to\ncompile your code and provide Metals with all the necessary information."}),"\n",(0,i.jsxs)(t.p,{children:["Scala CLI scripts can also be used standalone when Scala CLI generated ",(0,i.jsx)(t.code,{children:".bsp"}),"\ndirectory after running ",(0,i.jsx)(t.code,{children:"scala-cli compile"})," or ",(0,i.jsx)(t.code,{children:"scala-cli setup-ide"})," on that\nscript."]}),"\n",(0,i.jsx)(t.h3,{id:"advanced-information",children:"Advanced information"}),"\n",(0,i.jsxs)(t.p,{children:["Additionally, advanced users can start the underlying build server manually with\n",(0,i.jsx)(t.code,{children:"Metals: Start Scala CLI BSP server"})," (",(0,i.jsx)(t.code,{children:"scala-cli-start"}),") or\n",(0,i.jsx)(t.code,{children:"Metals: Start Ammonite BSP server"})," (",(0,i.jsx)(t.code,{children:"ammonite-start"}),"). They will also be able\nto stop it with ",(0,i.jsx)(t.code,{children:"Metals: Stop Scala CLI BSP server"}),"(",(0,i.jsx)(t.code,{children:"scala-cli-stop"}),") or\n",(0,i.jsx)(t.code,{children:"Metals: Stop Ammonite BSP server"}),"(ammonite-stop). These commands can be used\nfor more fine grained control when to turn on or of scripting support. This is\nespecially useful since the additional build server running underneath can take\nup some additional resources."]}),"\n",(0,i.jsx)(t.p,{children:"If the script is in a dedicated folder, by default we will treat all the scripts\nand scala files in that directory as ones that can be used together. So you\nwould be able to import method and classes from those files. However, if the\nscript is contained within a directory that also contains other sources, that\nscript will be treated as a standalone one in order to avoid flaky compilation\nerrors coming from normal files in the workspace."}),"\n",(0,i.jsx)(t.p,{children:"Current limitations can be found:"}),"\n",(0,i.jsxs)(t.ul,{children:["\n",(0,i.jsx)(t.li,{children:(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/issues?q=is%3Aopen+is%3Aissue+label%3A%22ammonite+support%22",children:"here for Ammonite"})}),"\n",(0,i.jsx)(t.li,{children:(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/issues?q=is%3Aopen+is%3Aissue+label%3Ascala-cli",children:"here for Scala CLI"})}),"\n"]}),"\n",(0,i.jsxs)(t.p,{children:["For troubleshooting take a look at the ",(0,i.jsx)(t.a,{href:"/docs/troubleshooting/faq#ammonite-scripts",children:"FAQ"})]})]})}function h(e={}){const{wrapper:t}={...(0,n.R)(),...e.components};return t?(0,i.jsx)(t,{...e,children:(0,i.jsx)(d,{...e})}):d(e)}},8453:(e,t,s)=>{s.d(t,{R:()=>o,x:()=>a});var i=s(6540);const n={},r=i.createContext(n);function o(e){const t=i.useContext(r);return i.useMemo((function(){return"function"==typeof e?e(t):{...t,...e}}),[t,e])}function a(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(n):e.components||n:o(e.components),i.createElement(r.Provider,{value:t},e.children)}}}]);