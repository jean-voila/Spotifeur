// Run inside Sketch with context.repository set; context.output optionally exports PNGs.
(() => {
const sketch=require('sketch/dom');
const root=context.repository;
if (!root) throw new Error('Pass the repository path in --context as {"repository":"/path/to/Spotifeur"}');
Mocha.sharedRuntime().loadFrameworkWithName_inDirectory('CoreText','/System/Library/Frameworks');
CTFontManagerRegisterFontsForURL(NSURL.fileURLWithPath(root+'/app/src/main/res/font/gflex_variable.ttf'),1,null);
const C = {
  background: '#0A100D', surface: '#141C17', raised: '#202A24',
  jade: '#6FE3A6', strong: '#0F9D63', bright: '#B4F7D1',
  container: '#0B4F35', deep: '#04281B', lavender: '#C9BEFF',
  text: '#EAF6EF', secondary: '#B9CABF', outline: '#3F4E45',
};
const doc = new sketch.Document();
doc.colorSpace = sketch.Document.ColorSpace.sRGB;
const page = doc.pages[0];
page.name = 'Instagram';
Object.keys(C).forEach(name => doc.colors.push({name, color: C[name]}));

const R = (x, y, width, height) => new sketch.Rectangle(x, y, width, height);

function rectangle(parent, name, x, y, w, h, radius, fill, extra) {
  return new sketch.ShapePath({parent, name, frame: R(x,y,w,h),
    style: Object.assign({fills: [fill], borders: [], corners: {radii: radius}}, extra || {})});
}
function gradient(a, b) {
  return {fillType: 'Gradient', gradient: {gradientType: 'Linear', from: {x:0,y:0},
    to: {x:1,y:1}, stops: [{position:0,color:a},{position:1,color:b}]}};
}
function cookie(parent, name, x, y, size, a, b) {
  // Continuous scalloped path, using cubic interpolation rather than polygon corners.
  const points = [];
  const count = 144;
  for (let i=0; i<count; i++) {
    const angle = i * Math.PI * 2 / count;
    const r = size * (0.45 + 0.043 * Math.cos(9*angle));
    points.push({x:size/2+r*Math.cos(angle), y:size/2+r*Math.sin(angle)});
  }
  let path = 'M '+points[0].x+' '+points[0].y;
  for (let i=0; i<count; i++) {
    const p0=points[(i-1+count)%count], p1=points[i], p2=points[(i+1)%count], p3=points[(i+2)%count];
    path += ' C '+(p1.x+(p2.x-p0.x)/6)+' '+(p1.y+(p2.y-p0.y)/6)+' '+
      (p2.x-(p3.x-p1.x)/6)+' '+(p2.y-(p3.y-p1.y)/6)+' '+p2.x+' '+p2.y;
  }
  const shape = sketch.ShapePath.fromSVGPath(path+' Z');
  shape.parent=parent; shape.name=name; shape.frame=R(x,y,size,size);
  shape.style={fills:[gradient(a,b)],borders:[],shadows:[{color:'#00000024',blur:44,x:0,y:20}]};
  return shape;
}
function text(parent, name, value, x, y, size, color, weight, width, align) {
  const layer = new sketch.Text({parent,name,text:value,frame:R(x,y,width || 1000,size*1.5),
    fixedWidth: Boolean(width),style:{fontFamily:'Google Sans Flex',fontSize:size,
      textColor:color,alignment:align || 'left',kerning: size>=70 ? -size*0.025 : 0}});
  const axes=layer.style.fontAxes;
  if (!axes) throw new Error('Google Sans Flex was not loaded');
  axes.Roundness.value=100; axes.Weight.value=weight || 500;
  axes['Optical Size'].value=Math.min(size,144);
  layer.style.fontAxes=axes;
  if (!width) layer.adjustToFit();
  layer.name=name;
  return layer;
}
function logo(parent,x,y,size) {
  const vector=sketch.createLayerFromData(NSData.dataWithContentsOfFile(root+'/design/logo.svg'),'svg');
  vector.parent=parent;vector.name='Logo / Original vector';vector.frame=R(x,y,size,size);
}
function board(name,w,h,x,y) {
  const b=new sketch.Group({parent:page,name,groupBehavior:sketch.GroupBehavior.Frame,
    frame:R(x,y,w,h),style:{fills:[C.background]},
    exportFormats:[{fileFormat:'png',size:'1x'},{fileFormat:'png',size:'2x'}]});
  const mask=rectangle(b,'Canvas mask',0,0,w,h,0,C.background);
  mask.masksSiblings=true;
  mask.maskMode=sketch.Layer.MaskMode.Outline;
  return b;
}
function phone(parent,name,file,x,y,screenWidth) {
  const bezel=12, h=screenWidth*1200/537;
  const g=new sketch.Group({parent,name:'Phone / '+name,frame:R(x,y,screenWidth+24,h+24)});
  rectangle(g,'Frame',0,0,screenWidth+24,h+24,58,C.raised,{
    borders:[{color:'#526158',thickness:2,position:'Inside'}],
    shadows:[{color:'#00000066',blur:38,x:0,y:22}]});
  rectangle(g,'Inner bezel',4,4,screenWidth+16,h+16,54,'#080B09');
  const clip=rectangle(g,'Screen mask',bezel,bezel,screenWidth,h,46,'#000000');
  clip.masksSiblings=true;clip.maskMode=sketch.Layer.MaskMode.Outline;
  new sketch.Image({parent:g,name:'Screenshot / '+file+'.jpg',
    image:root+'/docs/assets/img/screens/'+file+'.jpg',frame:R(bezel,bezel,screenWidth,h)});
  return g;
}

function slide(number,title,description) {
  const b=board('carousel-'+String(number).padStart(2,'0'),1080,1350,(number-1)*1260,2180);
  const mask=b.layers[0];
  mask.style.fills=[{fillType:'Gradient',gradient:{gradientType:'Linear',from:{x:0,y:0},to:{x:1,y:1},stops:[{position:0,color:'#082E21'},{position:0.52,color:'#09130E'},{position:1,color:'#030705'}]}}];
  logo(b,72,64,56);
  text(b,'Brand','Spotifeur',148,65,40,C.jade,750);
  text(b,'Page',String(number).padStart(2,'0')+' / 06',834,76,26,C.secondary,500,174,'right');
  text(b,'Title',title,72,168,74,C.text,750,936);
  text(b,'Description',description,76,270,31,C.secondary,500,928);
  text(b,'Repository','github.com/jean-voila/Spotifeur',76,1288,22,C.secondary,500);
  return b;
}
function pair(b,left,right,labelLeft,labelRight) {
  phone(b,labelLeft,left,120,398,350);
  phone(b,labelRight,right,586,432,350);
}
const story=board('story',1080,1920,0,0);
const storyBG=new sketch.Group({parent:story,name:'Background / Expressive shapes'});
cookie(storyBG,'Cookie / Top right',790,-170,480,C.container,C.deep);
rectangle(storyBG,'Jade pill',-250,800,1540,630,315,gradient(C.container,C.deep)).transform.rotation=-26;
cookie(storyBG,'Cookie / Bottom left',-160,1590,410,C.container,C.deep);
rectangle(storyBG,'Lavender accent',941,1400,220,54,27,C.lavender).transform.rotation=-24;
logo(story,82,222,98);
text(story,'Name','Spotifeur',211,210,105,C.text,780);
text(story,'Description','Open-source music player\nfor Android',82,376,44,C.text,600,900);
text(story,'Sources','Spotify · YouTube Music · Local files',82,519,32,C.jade,500);
phone(story,'Home','home',82,666,402);
phone(story,'Player','player',574,732,402);
text(story,'Repository','github.com/jean-voila/Spotifeur',82,1712,35,C.jade,600);

const cover=slide(1,'Spotifeur','Free, open-source music player for Android.');
text(cover,'Sources','Spotify · YouTube Music · Local files',76,321,30,C.jade,550);
pair(cover,'home','player','Home','Player');
const playback=slide(2,'Playback','Queue and synced lyrics.');
pair(playback,'queue','lyrics','Queue','Lyrics');
const library=slide(3,'Library','Albums and playlists synced with Spotify.');
pair(library,'album','playlist','Album','Playlist');
const themes=slide(4,'Appearance','Themes, colors, layouts and gestures.');
pair(themes,'appearance','settings','Appearance','Settings');
const offline=slide(5,'Offline','Downloads and audio cache.');
phone(offline,'Home','home',112,410,350);
text(offline,'Downloads title','Downloads',572,460,36,C.jade,650,422);
text(offline,'Downloads detail','Tracks, albums\nand playlists',572,518,31,C.text,500,422);
text(offline,'Cache title','Adjustable cache',572,696,36,C.jade,650,422);
text(offline,'Cache detail','From 512 MB to 32 GB',572,754,31,C.text,500,422);
text(offline,'Quality title','Audio quality',572,905,36,C.jade,650,422);
text(offline,'Quality detail','Separate settings for\nWi-Fi, mobile data\nand downloads',572,963,30,C.text,500,422);
const install=slide(6,'Installation','Android 8.0 or later · ARM64');
logo(install,76,414,154);
text(install,'Availability','Available on GitHub',274,445,43,C.text,650,720);
const steps=[['01','Download the APK','From the project’s GitHub releases.'],['02','Install on Android','Open the APK file on your phone.'],['03','Set up the app','Sign in to Spotify or continue without an account.']];
steps.forEach((row,i)=>{
 const y=660+i*164;
 text(install,'Step '+row[0],row[0],80,y,38,C.jade,600);
 text(install,'Step title '+row[0],row[1],184,y-4,40,C.text,600,820);
 text(install,'Step detail '+row[0],row[2],186,y+56,29,C.secondary,500,800);
});
text(install,'Languages','Interface available in English and French.',80,1193,28,C.jade,500);
doc.selectedLayers.clear();
doc.save(root+'/design/spotifeur-instagram.sketch',err=>{
 if(err)throw err;
 if (context.output) page.canvasLevelFrames.forEach(b=>sketch.export(b,{output:context.output,formats:'png',scales:'1',overwriting:true}));
 console.log('Saved the editable story and six carousel slides.');
});
})();
