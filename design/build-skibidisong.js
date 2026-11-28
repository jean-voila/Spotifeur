// Run inside Sketch with context.repository set; context.output optionally exports PNGs.
// Builds design/spotifeur-skibidisong.sketch: three 1080 × 1920 stories describing Skibidisong mode (v1.7.0).
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
const VERSION = 'v1.7.0';
const doc = new sketch.Document();
doc.colorSpace = sketch.Document.ColorSpace.sRGB;
const page = doc.pages[0];
page.name = 'Skibidisong';
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
function board(name,x) {
  const b=new sketch.Group({parent:page,name,groupBehavior:sketch.GroupBehavior.Frame,
    frame:R(x,0,1080,1920),style:{fills:[C.background]},
    exportFormats:[{fileFormat:'png',size:'1x'},{fileFormat:'png',size:'2x'}]});
  const mask=rectangle(b,'Canvas mask',0,0,1080,1920,0,C.background);
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
function card(parent,name,x,y,w,h) {
  return rectangle(parent,'Card / '+name,x,y,w,h,40,gradient(C.raised,C.surface),{
    borders:[{color:C.outline,thickness:2,position:'Inside'}],
    shadows:[{color:'#00000059',blur:40,x:0,y:18}]});
}
// The same signal for both rows, so the Spotify excerpt lines up with part of the YouTube Music one.
function signal(t) {
  return Math.sin(t*0.9)*0.55 + Math.sin(t*2.3+1)*0.3 + Math.sin(t*5.1+2)*0.15;
}
function wave(parent,name,x,y,width,height,from,to,color) {
  const steps=180;
  let path='';
  for (let i=0;i<=steps;i++) {
    const t=from+(to-from)*i/steps;
    path+=(i?' L ':'M ')+(width*i/steps)+' '+(height/2-signal(t)*height/2);
  }
  const shape=sketch.ShapePath.fromSVGPath(path);
  shape.parent=parent; shape.name='Wave / '+name; shape.frame=R(x,y,width,height);
  shape.style={fills:[],borders:[{color,thickness:6,position:'Center'}],borderOptions:{lineEnd:'Round',lineJoin:'Round'}};
  return shape;
}
function line(parent,name,x,y,height,color) {
  const shape=sketch.ShapePath.fromSVGPath('M 0 0 L 0 '+height);
  shape.parent=parent; shape.name=name; shape.frame=R(x,y,2,height);
  shape.style={fills:[],borders:[{color,thickness:3,position:'Center'}],borderOptions:{dashPattern:[10,10],lineEnd:'Round'}};
  return shape;
}
function story(index,name,title,description) {
  const b=board(name,index*1240);
  const shapes=new sketch.Group({parent:b,name:'Background / Expressive shapes'});
  cookie(shapes,'Cookie / Top right',800,-190,460,C.container,C.deep);
  rectangle(shapes,'Jade pill',-300,1180,1600,560,280,gradient(C.container,C.deep)).transform.rotation=-24;
  cookie(shapes,'Cookie / Bottom left',-170,1650,380,C.container,C.deep);
  logo(b,82,128,64);
  text(b,'Brand','Spotifeur',166,131,44,C.text,750);
  text(b,'Version',VERSION,698,141,32,C.jade,600,300,'right');
  text(b,'Title',title,82,256,96,C.text,750,916);
  text(b,'Description',description,82,392,40,C.secondary,500,916);
  text(b,'Repository','github.com/jean-voila/Spotifeur',82,1780,35,C.jade,600);
  return b;
}

// 1. What the mode does.
const what=story(0,'story-01-skibidisong','Skibidisong',
  'On a Spotify account that stops each track after a few seconds, Spotify tracks play their YouTube Music recording.');
text(what,'Where','Playlists · Albums · Queue · Downloads',82,580,34,C.jade,550);
phone(what,'Player','player',82,690,420);
card(what,'Track',566,800,432,190);
text(what,'Track label','Track',606,836,30,C.secondary,500);
text(what,'Track value','From Spotify',606,886,44,C.text,650,360);
card(what,'Audio',566,1030,432,300);
text(what,'Audio label','Audio',606,1066,30,C.secondary,500);
text(what,'Audio value','YouTube Music\nrecording',606,1116,44,C.text,650,360);
wave(what,'Audio',606,1262,352,40,0,9,C.jade);
text(what,'Detail','Turns on when Spotify stops playback, or when the account’s plan shows it will.',
  566,1374,30,C.secondary,500,432);
rectangle(what,'Lavender accent',930,1560,200,52,26,C.lavender).transform.rotation=-24;

// 2. How a recording is chosen.
const how=story(1,'story-02-matching','Matched by ear',
  'The first seconds of the Spotify track are compared with the first seconds of each YouTube Music result, at every offset.');
card(how,'Spotify excerpt',82,620,916,230);
text(how,'Spotify label','Spotify · first 8 s',122,654,32,C.secondary,550);
wave(how,'Spotify',586,720,372,90,6,14,C.jade);
card(how,'YouTube Music excerpt',82,880,916,230);
text(how,'YouTube label','YouTube Music · first 15 s',122,914,32,C.secondary,550);
wave(how,'YouTube Music',122,980,836,90,-4,14,C.bright);
line(how,'Alignment / Start',586,700,410,C.text);
line(how,'Alignment / End',958,700,410,C.text);
const steps=[['01','The first result is checked'],['02','Then the next three'],['03','Then a search with every artist'],['04','Without a match, the first result']];
steps.forEach((row,i)=>{
  const y=1190+i*92;
  text(how,'Step '+row[0],row[0],86,y,36,C.jade,650);
  text(how,'Step text '+row[0],row[1],184,y,36,C.text,550,800);
});
text(how,'Measure','Test playlist of 1,649 tracks: 1,614 matched by ear.',82,1580,32,C.jade,550,916);
rectangle(how,'Lavender accent',940,1420,200,52,26,C.lavender).transform.rotation=-24;

// 3. Order, storage and the setting.
const order=story(2,'story-03-order','Matching order',
  'Matches are made in this order and kept on the device.');
const items=[['01','The track you start','Checked before it plays.'],['02','The queue','Checked ahead of each track.'],
  ['03','Tracks on screen','In the playlist you are viewing.'],['04','The rest of the playlist','In the background.']];
items.forEach((row,i)=>{
  const y=560+i*168;
  text(order,'Order '+row[0],row[0],86,y,36,C.jade,650);
  text(order,'Order title '+row[0],row[1],170,y-2,38,C.text,650,420);
  text(order,'Order detail '+row[0],row[2],170,y+54,29,C.secondary,500,420);
});
phone(order,'Playback settings','playback',626,520,348);
text(order,'Downloads','Downloads keep the YouTube Music recording.',82,1400,34,C.text,550,916);
text(order,'Setting','Settings → Playback → Skibidisong.\nOn by default.',82,1500,34,C.jade,600,916);
rectangle(order,'Lavender accent',940,1640,200,52,26,C.lavender).transform.rotation=-24;

doc.selectedLayers.clear();
doc.save(root+'/design/spotifeur-skibidisong.sketch',err=>{
  if(err)throw err;
  if (context.output) page.canvasLevelFrames.forEach(b=>sketch.export(b,{output:context.output,formats:'png',scales:'1',overwriting:true}));
  console.log('Saved three Skibidisong stories.');
});
})();
