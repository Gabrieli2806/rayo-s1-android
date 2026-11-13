package local.gabos.share;

/**
 * The page served at "/": lets any device with a browser on the same network
 * (iPhone, laptop, tablet) send photos and videos without installing anything.
 * It uses the same /offer and /upload calls as the Android sender.
 */
final class WebPage {
    private WebPage(){}

    private static final String[] EN={
        "en","Send to","Photos and videos","Choose files","Your name","On the projector","Show now","Save",
        "Show plays them once and keeps nothing. Save stores them in the projector's gallery.","Send",
        "Waiting for approval on the projector…","Sending","Done. They are on the projector.","Declined on the projector.",
        "No answer on the projector.","The projector is busy with another transfer.","Not enough space on the projector.",
        "Something went wrong","Only photos and videos can be sent.","Choose at least one photo or video.","Browser",
        "Both devices must be on the same Wi-Fi network.","files selected"};
    private static final String[] ES={
        "es","Enviar a","Fotos y videos","Elegir archivos","Tu nombre","En el proyector","Mostrar ahora","Guardar",
        "Mostrar los reproduce una vez y no guarda nada. Guardar los deja en la galería del proyector.","Enviar",
        "Esperando aprobación en el proyector…","Enviando","Listo. Ya están en el proyector.","Rechazado en el proyector.",
        "Sin respuesta en el proyector.","El proyector está ocupado con otro envío.","No hay espacio suficiente en el proyector.",
        "Algo salió mal","Solo se pueden enviar fotos y videos.","Elige al menos una foto o un video.","Navegador",
        "Ambos dispositivos deben estar en la misma red Wi-Fi.","archivos elegidos"};

    static String html(String acceptLanguage,String deviceName){
        String[] t=acceptLanguage!=null&&acceptLanguage.trim().toLowerCase().startsWith("es")?ES:EN;
        String name=escape(deviceName);
        return "<!doctype html><html lang=\""+t[0]+"\"><head><meta charset=\"utf-8\">"
            +"<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>GabOS Share</title><style>"
            +":root{--accent:#8ab4f8;--ink:#f1f3f4;--dim:#aab0b8;--card:#1b222d;--neutral:#2a2f37}"
            +"*{box-sizing:border-box}body{margin:0;min-height:100vh;color:var(--ink);font:16px/1.4 system-ui,-apple-system,Roboto,sans-serif;"
            +"background:linear-gradient(135deg,#0d0f13,#0e131c 50%,#101d36)}main{max-width:560px;margin:0 auto;padding:32px 16px 48px}"
            +"h1{font-size:34px;font-weight:500;margin:0}.sub{color:var(--dim);margin:6px 0 0}.label{color:var(--dim);font-weight:700;"
            +"letter-spacing:.12em;font-size:13px;text-transform:uppercase;margin:30px 0 10px}.card{background:var(--card);border-radius:16px;padding:18px}"
            +"button,.pick{display:block;width:100%;border:0;border-radius:28px;padding:16px;font-size:18px;color:var(--ink);background:var(--neutral);"
            +"text-align:center;cursor:pointer}button.primary{background:var(--accent);color:#0d0f13;margin-top:28px}button:disabled{opacity:.5}"
            +"input[type=text]{width:100%;padding:14px;border-radius:12px;border:0;background:var(--neutral);color:var(--ink);font-size:17px}"
            +".modes{display:flex;gap:12px}.modes button{flex:1}.modes button.on{background:var(--accent);color:#0d0f13}"
            +".bar{height:8px;border-radius:4px;background:var(--neutral);overflow:hidden;margin-top:14px}.bar i{display:block;height:100%;width:0;background:var(--accent)}"
            +"#files{color:var(--dim);margin-top:10px}#status{margin-top:20px;min-height:24px}footer{color:var(--dim);font-size:13px;margin-top:36px;text-align:center}"
            +"</style></head><body><main><h1>GabOS Share</h1><p class=\"sub\">"+t[1]+" "+name+"</p>"
            +"<div class=\"label\">"+t[2]+"</div><div class=\"card\"><label class=\"pick\">"+t[3]
            +"<input id=\"pick\" type=\"file\" accept=\"image/*,video/*\" multiple hidden></label><div id=\"files\"></div></div>"
            +"<div class=\"label\">"+t[4]+"</div><input id=\"who\" type=\"text\" maxlength=\"40\">"
            +"<div class=\"label\">"+t[5]+"</div><div class=\"modes\"><button id=\"show\" class=\"on\">"+t[6]+"</button><button id=\"save\">"+t[7]+"</button></div>"
            +"<p class=\"sub\">"+t[8]+"</p><button id=\"send\" class=\"primary\">"+t[9]+"</button>"
            +"<div id=\"status\"></div><div class=\"bar\"><i id=\"bar\"></i></div><footer>"+t[21]+"<br>GabOS · g2806.net</footer></main><script>"
            +"var T="+jsArray(t)+",mode='show',$=function(i){return document.getElementById(i)};"
            +"try{$('who').value=localStorage.getItem('gabos-name')||''}catch(e){}"
            +"function st(s){$('status').textContent=s}"
            +"$('pick').onchange=function(){$('files').textContent=this.files.length?this.files.length+' '+T[22]:''};"
            +"$('show').onclick=function(){mode='show';this.className='on';$('save').className=''};"
            +"$('save').onclick=function(){mode='save';this.className='on';$('show').className=''};"
            +"function up(id,i,f,done,total){return new Promise(function(ok,fail){var x=new XMLHttpRequest();"
            +"x.open('POST','/upload?id='+encodeURIComponent(id)+'&index='+i);"
            +"x.upload.onprogress=function(e){$('bar').style.width=((done+e.loaded)*100/Math.max(total,1))+'%'};"
            +"x.onload=function(){if(x.status==200)ok();else{var m=T[17];try{m=JSON.parse(x.responseText).error||m}catch(e){}fail(new Error(m))}};"
            +"x.onerror=function(){fail(new Error(T[17]))};x.send(f)})}"
            +"$('send').onclick=async function(){var fs=[].slice.call($('pick').files);"
            +"if(!fs.length){st(T[19]);return}"
            +"if(fs.some(function(f){return !/^(image|video)\\//.test(f.type)})){st(T[18]);return}"
            +"var who=$('who').value.trim()||T[20];try{localStorage.setItem('gabos-name',who)}catch(e){}"
            +"var b=this;b.disabled=true;$('bar').style.width='0';st(T[10]);"
            +"try{var r=await fetch('/offer',{method:'POST',headers:{'Content-Type':'application/json'},"
            +"body:JSON.stringify({sender:who,mode:mode,items:fs.map(function(f){return{name:f.name,mime:f.type,size:f.size}})})});"
            +"var j=await r.json();if(!j.accepted){st(j.reason=='declined'?T[13]:j.reason=='timeout'?T[14]:j.reason=='busy'?T[15]:j.reason=='no space'?T[16]:T[17]+': '+(j.error||j.reason));return}"
            +"var total=fs.reduce(function(a,f){return a+f.size},0),done=0;"
            +"try{for(var i=0;i<fs.length;i++){st(T[11]+' '+(i+1)+' / '+fs.length+' · '+fs[i].name);await up(j.id,i,fs[i],done,total);done+=fs[i].size}}"
            +"catch(e){fetch('/cancel?id='+encodeURIComponent(j.id),{method:'POST'});throw e}"
            +"$('bar').style.width='100%';st(T[12])}catch(e){st(T[17]+': '+e.message)}finally{b.disabled=false}};"
            +"</script></body></html>";
    }

    private static String jsArray(String[] values){
        StringBuilder b=new StringBuilder("[");
        for(int i=0;i<values.length;i++){
            if(i>0)b.append(',');
            b.append('"');
            for(char c:values[i].toCharArray()){
                if(c=='"'||c=='\\')b.append('\\').append(c);
                else if(c=='<')b.append("\\u003c");
                else b.append(c);
            }
            b.append('"');
        }
        return b.append(']').toString();
    }

    static String escape(String s){
        StringBuilder b=new StringBuilder();
        for(char c:s.toCharArray()){
            switch(c){
                case '<':b.append("&lt;");break;case '>':b.append("&gt;");break;case '&':b.append("&amp;");break;
                case '"':b.append("&quot;");break;case '\'':b.append("&#39;");break;default:b.append(c);
            }
        }
        return b.toString();
    }
}
