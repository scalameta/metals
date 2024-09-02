/*! For license information please see 38b82327.e14bb432.js.LICENSE.txt */
"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[1284],{7730:(e,s,t)=>{t.r(s),t.d(s,{assets:()=>i,contentTitle:()=>n,default:()=>d,frontMatter:()=>r,metadata:()=>l,toc:()=>h});var o=t(4848),a=t(8453);const r={author:"Vadim Chelyshov",title:"Towards better releases",authorURL:"https://twitter.com/_dos65",authorImageURL:"https://github.com/dos65.png"},n=void 0,l={permalink:"/metals/blog/2022/02/23/towards-better-releases",source:"@site/blog/2022-02-23-towards-better-releases.md",title:"Towards better releases",description:"As many of you might have noticed, the previous 0.11.0 release didn't go smoothly due to a number of issues that came to light only after the release was published.",date:"2022-02-23T00:00:00.000Z",tags:[],readingTime:2.915,hasTruncateMarker:!1,authors:[{name:"Vadim Chelyshov",url:"https://twitter.com/_dos65",imageURL:"https://github.com/dos65.png",key:null,page:null}],frontMatter:{author:"Vadim Chelyshov",title:"Towards better releases",authorURL:"https://twitter.com/_dos65",authorImageURL:"https://github.com/dos65.png"},unlisted:!1,prevItem:{title:"Metals v0.11.2 - Aluminium",permalink:"/metals/blog/2022/03/08/aluminium"},nextItem:{title:"Metals v0.11.1 - Aluminium",permalink:"/metals/blog/2022/01/17/aluminium"}},i={authorsImageUrls:[void 0]},h=[{value:"Decouple Metals releases from Scala releases",id:"decouple-metals-releases-from-scala-releases",level:2},{value:"Pre-release version for VSCode extension",id:"pre-release-version-for-vscode-extension",level:2}];function c(e){const s={a:"a",code:"code",em:"em",h2:"h2",li:"li",p:"p",ul:"ul",...(0,a.R)(),...e.components};return(0,o.jsxs)(o.Fragment,{children:[(0,o.jsxs)(s.p,{children:["As many of you might have noticed, the previous ",(0,o.jsx)(s.code,{children:"0.11.0"})," release didn't go smoothly due to a number of issues that came to light only after the release was published.\nSome of them were quite critical and the only option to continue to work was to downgrade Metals until ",(0,o.jsx)(s.code,{children:"0.11.1"})," arrived."]}),"\n",(0,o.jsx)(s.p,{children:"We apologize for that!"}),"\n",(0,o.jsx)(s.p,{children:"However, this post is not only about saying sorry.\nIn order to avoid such situations in the future we have taken it upon ourselves to take steps that will allow us to detect issues earlier."}),"\n",(0,o.jsx)(s.p,{children:"There were two main improvements that were implemented since the last release:"}),"\n",(0,o.jsxs)(s.ul,{children:["\n",(0,o.jsx)(s.li,{children:"Decouple Metals releases from Scala releases"}),"\n",(0,o.jsx)(s.li,{children:"Pre-release version for VSCode extension"}),"\n"]}),"\n",(0,o.jsx)(s.h2,{id:"decouple-metals-releases-from-scala-releases",children:"Decouple Metals releases from Scala releases"}),"\n",(0,o.jsxs)(s.p,{children:["We haven't mentioned this in the release notes for ",(0,o.jsx)(s.code,{children:"0.11.0"}),", but since that version we have changed the way Metals detects whether a specific version is supported and thanks to that we are now able to backpublish new Scala versions support.\nThe support of Scala ",(0,o.jsx)(s.code,{children:"3.1.1"})," Scala was added to Metals ",(0,o.jsx)(s.code,{children:"0.11.1"})," using this new mechanism.\nIt allows us to provide new Scala version support much faster than before and also support Scala3-NIGTHTLY versions."]}),"\n",(0,o.jsx)(s.p,{children:"The additional benefit from this approach is that it eliminates time pressure for future Metals releases.\nIf you look at the release notes for previous versions, almost every one brings at least one new compiler version support.\nHaving this limitation and the need to provide the new release as soon as possible always increases the chances of something affecting the release. So, now with this new feature we are able to take our time for final fixes and do releases with more confidence."}),"\n",(0,o.jsx)(s.p,{children:"The current state for Scala versions support is:"}),"\n",(0,o.jsxs)(s.ul,{children:["\n",(0,o.jsx)(s.li,{children:"Every Metals release comes with support to all known supported Scala versions + last 5 latest Scala3-NIGHTLY versions.\nThis is applied to SNAPSHOT releases too."}),"\n",(0,o.jsxs)(s.li,{children:["In case a new Scala version appear, the latest Metals release will receive its support automatically.\nFor example, the next Scala ",(0,o.jsx)(s.code,{children:"3.1.2"}),"  will be supported only by the latest Metals ",(0,o.jsx)(s.code,{children:"0.11.1"})," but not by ",(0,o.jsx)(s.code,{children:"0.11.0"}),".\nThat works for Scala3-NIGHTLY versions too. Metals has a scheduled daily job that publishes artifacts for newly discovered NIGHTLY versions."]}),"\n"]}),"\n",(0,o.jsx)(s.h2,{id:"pre-release-version-for-vscode-extension",children:"Pre-release version for VSCode extension"}),"\n",(0,o.jsxs)(s.p,{children:["Another great feature that was added was the possibility to use the ",(0,o.jsx)(s.code,{children:"pre-release"})," versions of the Metals extension.\nIf you open the Metals extension page, you will find a ",(0,o.jsx)(s.code,{children:"Switch to Pre-release version"})," button.\nThis version is like a snapshot, it's published for every change in the ",(0,o.jsx)(s.a,{href:"https://github.com/scalameta/metals-vscode",children:"scalameta/metals-vscode"})," repository."]}),"\n",(0,o.jsx)(s.p,{children:"It allows to test not yet released features, as well as to check if everything works fine for your workspace using the latest main branch.\nSome issues might be observed only under the particular version of the client as was the case with the ill fated 0.11.0 release."}),"\n",(0,o.jsxs)(s.p,{children:["Also, there is a new setting ",(0,o.jsx)(s.code,{children:"Metals: Suggest Latest Upgrade"})," that is enabled by default for ",(0,o.jsx)(s.code,{children:"pre-release"}),".\nIf you have it enabled, you will receive notifications with an option to upgrade Metals server version to the latest snapshot once a day."]}),"\n",(0,o.jsxs)(s.p,{children:["We hope that some brave users will start using this ",(0,o.jsx)(s.code,{children:"pre-release"})," version and report issues if you encounter any.\nThis will help us spot problems earlier."]}),"\n",(0,o.jsxs)(s.p,{children:[(0,o.jsx)(s.em,{children:"Notice"}),":\nUsing pre-release versions may result in a less stable experience.\nIn case of issues, if you are switching back from ",(0,o.jsx)(s.code,{children:"pre-release"})," to ",(0,o.jsx)(s.code,{children:"release"})," you also need to downgrade ",(0,o.jsx)(s.code,{children:"Metals: Server Version"})," manually. The actual version might be found at ",(0,o.jsx)(s.a,{href:"https://scalameta.org/metals/docs/#latest-metals-server-versions",children:"docs page"})," or ",(0,o.jsx)(s.a,{href:"https://scalameta.org/metals/latests.json",children:"latest.json"})]})]})}function d(e={}){const{wrapper:s}={...(0,a.R)(),...e.components};return s?(0,o.jsx)(s,{...e,children:(0,o.jsx)(c,{...e})}):c(e)}},1020:(e,s,t)=>{var o=t(6540),a=Symbol.for("react.element"),r=Symbol.for("react.fragment"),n=Object.prototype.hasOwnProperty,l=o.__SECRET_INTERNALS_DO_NOT_USE_OR_YOU_WILL_BE_FIRED.ReactCurrentOwner,i={key:!0,ref:!0,__self:!0,__source:!0};function h(e,s,t){var o,r={},h=null,c=null;for(o in void 0!==t&&(h=""+t),void 0!==s.key&&(h=""+s.key),void 0!==s.ref&&(c=s.ref),s)n.call(s,o)&&!i.hasOwnProperty(o)&&(r[o]=s[o]);if(e&&e.defaultProps)for(o in s=e.defaultProps)void 0===r[o]&&(r[o]=s[o]);return{$$typeof:a,type:e,key:h,ref:c,props:r,_owner:l.current}}s.Fragment=r,s.jsx=h,s.jsxs=h},4848:(e,s,t)=>{e.exports=t(1020)},8453:(e,s,t)=>{t.d(s,{R:()=>n,x:()=>l});var o=t(6540);const a={},r=o.createContext(a);function n(e){const s=o.useContext(r);return o.useMemo((function(){return"function"==typeof e?e(s):{...s,...e}}),[s,e])}function l(e){let s;return s=e.disableParentContext?"function"==typeof e.components?e.components(a):e.components||a:n(e.components),o.createElement(r.Provider,{value:s},e.children)}}}]);