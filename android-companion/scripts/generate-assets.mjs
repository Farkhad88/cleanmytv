// Reuse only the mandatory v0.7.0 source. Run from the repository root.
import {readFileSync,writeFileSync,readdirSync,mkdirSync} from 'node:fs';
import {resolve} from 'node:path';
import {build} from '../../node_modules/esbuild/lib/main.js';
import {treeHash} from '../../scripts/tree-hash.mjs';
const root=resolve('android-companion/upstream-reference'); const out=resolve('android-companion/app/src/main/assets'); mkdirSync(out,{recursive:true});
const home=readFileSync(root+'/src/main/home.ts','utf8');
const literal=home.match(/export const DEFAULTS: HomeConfig = (\{[\s\S]*?\n\})/)[1];
// This evaluates the reviewed upstream literal, not user-provided input.
const defaults=Function('return ('+literal+')')();
writeFileSync(out+'/home-defaults.json',JSON.stringify(defaults));
const themeBundle=await build({entryPoints:[root+'/src/shared/themes.ts'],bundle:true,format:'esm',platform:'node',write:false});
const module=await import('data:text/javascript;base64,'+Buffer.from(themeBundle.outputFiles[0].text).toString('base64'));
if(module.BUILT_IN_THEMES.length!==28) throw new Error('Wrong baseline: expected 28 themes');
writeFileSync(out+'/themes.json',JSON.stringify(module.BUILT_IN_THEMES));
await build({stdin:{contents:`import {drawWallpaper} from './src/renderer/src/lib/themes'; import {BUILT_IN_THEMES,withPicture} from './src/shared/themes'; window.cmtvDraw=(paint)=>drawWallpaper(paint,1920,1080).toDataURL('image/jpeg',0.86); window.cmtvThemes=BUILT_IN_THEMES; window.cmtvPicture=withPicture;`,resolveDir:root},bundle:true,platform:'browser',format:'iife',outfile:out+'/artwork.js',minify:true});
const profiles=[]; const pk=/^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;
for(const id of readdirSync('resources/upstream/examples').sort()) {
 const dir='resources/upstream/examples/'+id; let raw,meta;try{raw=readFileSync(dir+'/debloat.txt','utf8');meta=JSON.parse(readFileSync(dir+'/tv.json','utf8'))}catch{continue}
 let section='',header=[],entries=[];
 for(const line of raw.replace(/^\uFEFF/,'').split(/\r?\n/).map(x=>x.trim())) {
  if(!line){section='';continue} const commented=line.startsWith('#');const body=line.replace(/^#+\s*/,'');
  const [code,...notes]=body.split('#'); const pkg=code.trim().split(/\s+/)[0];
  if(pk.test(pkg)&&code.trim()===pkg){let old=entries.find(e=>e.pkg===pkg); if(old){if(!commented)old.suggested=true}else entries.push({pkg,note:notes.join('#').trim(),suggested:!commented,section});}
  else if(commented){if(entries.length===0)header.push(body);section=body;}
  else if(!pk.test(pkg)) throw new Error(id+': invalid profile line');
 }
 const name=String(meta.tv||''); const models=new Set([name.trim(),name.replace(/\s*\(.*$/,'').replace(new RegExp('^'+String(meta.brand||'').replace(/[.*+?^${}()|[\]\\]/g,'\\$&')+'\\s+','i'),'').trim()]);
 // Only whole explicitly written model codes are aliases; no manufacturer/family inference.
 for(const token of name.split(/[\s,()/]+/)) if(/[0-9]/.test(token)&&/[A-Za-z]/.test(token)&&token.length>=3) models.add(token);
 const version=/Android(?: TV)?\s+(\d+)/i.exec(String(meta.os||''))?.[1]; const sdks={7:25,8:26,9:28,10:29,11:30,12:31,13:33,14:34,15:35,16:36};
 profiles.push({id,title:header[0]||id,brand:String(meta.brand||''),models:[...models].filter(Boolean),sdk:sdks[version]||0,entries});
}
writeFileSync(out+'/profiles.json',JSON.stringify(profiles));
writeFileSync(out+'/upstream.json',readFileSync('upstream.json'));
console.log(`Generated ${profiles.length} exact-model profiles, ${module.BUILT_IN_THEMES.length} themes and the original procedural renderer.`);
