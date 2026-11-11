package probe.bootstrap;

import com.spotify.core.http.*;
import com.spotify.connectivity.Scheduler;
import java.io.*;
import java.net.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native HTTP callbacks use the core scheduler; all socket operations run on IO threads. */
public final class SdkHttpFactory implements HttpConnectionFactory, AutoCloseable {
    /** Lets an instrumented test cut this engine off while keeping what it holds in memory. */
    public static volatile boolean offlineForTest;
    /** Receives (file ID, HTTP status) for every key-service response; set by the app. */
    public static volatile java.util.function.BiConsumer<String,Integer> keyResponseListener;
    /** Top-level field numbers and varint values of a request body; length-delimited fields show only their size. */
    private static String shape(byte[] body) {
        if(body==null)return "none";
        StringBuilder out=new StringBuilder();int i=0;
        try {
            while(i<body.length) {
                long tag=0;int shift=0;byte b;do{b=body[i++];tag|=(long)(b&0x7f)<<shift;shift+=7;}while((b&0x80)!=0);
                int field=(int)(tag>>>3),type=(int)(tag&7);
                if(type==0){long v=0;shift=0;do{b=body[i++];v|=(long)(b&0x7f)<<shift;shift+=7;}while((b&0x80)!=0);out.append(field).append('=').append(v).append(' ');}
                else if(type==2){long n=0;shift=0;do{b=body[i++];n|=(long)(b&0x7f)<<shift;shift+=7;}while((b&0x80)!=0);i+=(int)n;out.append(field).append(":len").append(n).append(' ');}
                else if(type==5){i+=4;out.append(field).append(":fixed32 ");}
                else if(type==1){i+=8;out.append(field).append(":fixed64 ");}
                else return out.append("?").toString();
            }
        } catch(RuntimeException failure){out.append("truncated");}
        return out.toString().trim();
    }
    /** Field 1 of a request body (the request header of a batched metadata request), or null. */
    private static byte[] headerField(byte[] body) {
        if(body==null)return null;
        int i=0;
        try {
            while(i<body.length) {
                long tag=0;int shift=0;byte b;do{b=body[i++];tag|=(long)(b&0x7f)<<shift;shift+=7;}while((b&0x80)!=0);
                int field=(int)(tag>>>3),type=(int)(tag&7);
                if(type==0){do{b=body[i++];}while((b&0x80)!=0);continue;}
                if(type!=2)return null;
                long n=0;shift=0;do{b=body[i++];n|=(long)(b&0x7f)<<shift;shift+=7;}while((b&0x80)!=0);
                if(field==1)return Arrays.copyOfRange(body,i,i+(int)n);
                i+=(int)n;
            }
        } catch(RuntimeException failure){return null;}
        return null;
    }
    private final java.util.function.BiFunction<String,Boolean,Map<String,String>> apiHeaders;
    private final java.util.function.Function<String,byte[]> keyLoad;
    private final java.util.function.BiConsumer<String,byte[]> keySave;
    private final Scheduler scheduler;
    private final Map<String,String> sessionHeaders;
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private final Set<Delegate> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean freshBearerNoted = new AtomicBoolean();
    private final Set<String> blockedServices=ConcurrentHashMap.newKeySet();
    private final Object mediaGate = new Object();
    private long nextMediaRequestNanos;
    public SdkHttpFactory(Scheduler scheduler) { this(scheduler,Collections.emptyMap()); }
    public SdkHttpFactory(Scheduler scheduler,Map<String,String> sessionHeaders) { this(scheduler,sessionHeaders,id -> null,(id,bytes) -> {},(host,rejected) -> Collections.emptyMap()); }
    public SdkHttpFactory(Scheduler scheduler,Map<String,String> sessionHeaders,java.util.function.Function<String,byte[]> keyLoad,java.util.function.BiConsumer<String,byte[]> keySave,java.util.function.BiFunction<String,Boolean,Map<String,String>> apiHeaders) {
        this.scheduler = scheduler; this.sessionHeaders=new ConcurrentHashMap<>(sessionHeaders); this.keyLoad=keyLoad; this.keySave=keySave;this.apiHeaders=apiHeaders;
    }
    private static String keyFile(URL url) {
        if (!url.getHost().equals("spclient.wg.spotify.com") && !url.getHost().endsWith("-spclient.spotify.com")) return null;
        String path=url.getPath();
        return path.matches("/playplay/v1/key/[0-9a-f]{40}") ? path.substring(path.lastIndexOf('/')+1) : null;
    }
    private static String retainedResponse(URL url, String method) {
        String file=keyFile(url);
        if(file!=null)return file;
        // The SDK resolves its regional service host before it can ask for a local media handle.
        if(!method.equals("GET") || !url.getHost().equals("apresolve.spotify.com"))return null;
        try {
            byte[] digest=java.security.MessageDigest.getInstance("SHA-1").digest(("route:"+url.toString()).getBytes("UTF-8"));
            StringBuilder value=new StringBuilder();for(byte part:digest)value.append(String.format(java.util.Locale.ROOT,"%02x",part & 255));return value.toString();
        } catch(Exception failure){throw new IllegalStateException(failure);}
    }
    @Override public HttpConnectionDelegate createDelegate() {
        if (closed.get()) throw new IllegalStateException("HTTP factory released");
        Delegate delegate = new Delegate(); active.add(delegate); return delegate;
    }
    @Override public void close() {
        if (!closed.compareAndSet(false,true)) return;
        for (Delegate delegate : active) delegate.abort();
        deadlines.shutdownNow(); io.shutdown(); sessionHeaders.clear();
    }
    private static int error(String name) {
        try { return HttpConnection.class.getField(name).getInt(null); }
        catch (Exception failure) { throw new IllegalStateException("Missing native HTTP error constant", failure); }
    }
    @SuppressWarnings("unchecked")
    private static Map<String,String> headers(byte[] data) throws Exception {
        Class<?> type = Class.forName("com.spotify.jvm.jni.NativeHelpers",true,SdkHttpFactory.class.getClassLoader());
        Object companion = type.getField("Companion").get(null);
        Method method = companion.getClass().getMethod("byteArrayToMap",byte[].class);
        return new LinkedHashMap<>((Map<String,String>)method.invoke(companion,(Object)data));
    }
    private static String field(Map<String,String> headers,String name) {
        for(Map.Entry<String,String> entry:headers.entrySet())if(entry.getKey().equalsIgnoreCase(name))return entry.getValue();
        return null;
    }
    private static void replace(Map<String,String> headers,String name,String value) {
        headers.keySet().removeIf(key -> key.equalsIgnoreCase(name));headers.put(name,value);
    }
    private static int millis(long value) { return (int)Math.max(0,Math.min(Integer.MAX_VALUE,value)); }
    private final class Delegate implements HttpConnectionDelegate {
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean aborted = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private volatile boolean expired;
        private volatile HttpURLConnection socket;
        private volatile ScheduledFuture<?> deadline;
        @Override public boolean isRequestStarted() { return started.get(); }
        @Override public void abort() {
            if (!aborted.compareAndSet(false,true)) return;
            ScheduledFuture<?> task=deadline; if(task!=null)task.cancel(false);
            HttpURLConnection connection=socket;
            if(connection!=null)io.execute(connection::disconnect);
            active.remove(this);
        }
        private void emit(Runnable action) {
            if(closed.get()||aborted.get())return;
            scheduler.post(() -> { if(!aborted.get() && !terminal.get()) action.run(); });
        }
        private void finish(Runnable action) {
            ScheduledFuture<?> task=deadline; if(task!=null)task.cancel(false);
            if(closed.get()||aborted.get())return;
            scheduler.post(() -> {
                if(!aborted.get() && terminal.compareAndSet(false,true)) action.run();
                active.remove(this);
            });
        }
        @Override public void send(HttpConnection sink,HttpRequest request,HttpOptions options) {
            if(!started.compareAndSet(false,true))throw new IllegalStateException("Request already started");
            if(closed.get() || aborted.get())return;
            if(options.getTimeout()>0)deadline=deadlines.schedule(() -> {
                expired=true; HttpURLConnection connection=socket; if(connection!=null)io.execute(connection::disconnect);
            }, options.getTimeout(),TimeUnit.MILLISECONDS);
            io.execute(() -> {
                try {
                    URL url=new URL(request.getUrl()); String method=request.getMethod(); byte[] body=request.getBody();
                    Map<String,String> fields=headers(request.getHeaders());
                    final String nativeClientToken=field(fields,"client-token");
                    boolean authRetried=false;
                    boolean refreshAuth=false;
                    for(int redirects=0;;redirects++) {
                        if(aborted.get())return;
                        if(expired)throw new SocketTimeoutException();
                        if(!"https".equalsIgnoreCase(url.getProtocol()))throw new MalformedURLException("HTTPS required");
                        String keyId=retainedResponse(url,method);
                        if(url.getPath().contains("key") || url.getPath().contains("playplay"))android.util.Log.i("SpotifeurNativeHttp","key route="+url.getHost()+url.getPath().replaceAll("[0-9a-fA-F]{32,}","{file}")+" recognized="+(keyId!=null)+" offline="+offlineForTest);
                        // Successful per-account media envelopes are immutable for a file. Reuse
                        // them online too; loading a long queue must not request all keys again.
                        byte[] retained=keyId!=null && (offlineForTest || keyFile(url)!=null)?keyLoad.apply(keyId):null;
                        if(offlineForTest || retained!=null) {
                            byte[] kept=retained;
                            if(kept==null) {
                                String[] route=url.getPath().split("/");
                                String category=url.getHost()+"/"+(route.length>1?route[1]:"root");
                                if(blockedServices.add(category))android.util.Log.d("SpotifeurNativeHttp","offline request blocked service="+category);
                                throw new java.net.ConnectException();
                            }
                            HttpResponse response=new HttpResponse(200,url.toString(),"Content-Type: application/x-protobuf\n\n");
                            emit(() -> sink.onHeaders(response));
                            emit(() -> sink.onBytesAvailable(kept,kept.length));
                            finish(() -> { java.util.Arrays.fill(kept,(byte)0); sink.onComplete(false); });
                            android.util.Log.i("SpotifeurNativeHttp","local media envelope restored");
                            return;
                        }
                        if(keyFile(url)!=null) {
                            android.util.Log.i("SpotifeurNativeHttp","key request "+method+" body="+shape(body));
                            synchronized(mediaGate) {
                                while(System.nanoTime()<nextMediaRequestNanos) {
                                    if(aborted.get())return;
                                    if(expired)throw new SocketTimeoutException();
                                    mediaGate.wait(Math.min(200,Math.max(1,TimeUnit.NANOSECONDS.toMillis(nextMediaRequestNanos-System.nanoTime()))));
                                }
                                nextMediaRequestNanos=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(500);
                            }
                        }
                        if(url.getPath().contains("extended-metadata"))android.util.Log.i("SpotifeurNativeHttp","metadata route="+url.getHost()+url.getPath()+" body="+shape(body)+" header="+shape(headerField(body)));
                        HttpURLConnection connection=(HttpURLConnection)url.openConnection(); socket=connection;
                        connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
                        connection.setConnectTimeout(millis(options.getConnectTimeout())); connection.setReadTimeout(millis(options.getTimeout()));
                        connection.setRequestMethod(method);
                        String host=url.getHost();
                        if(host.equals("spclient.wg.spotify.com") || host.endsWith("-spclient.spotify.com") || host.equals("login5.spotify.com")) {
                            for(Map.Entry<String,String> header:sessionHeaders.entrySet()) {
                                if(host.equals("login5.spotify.com") && header.getKey().equalsIgnoreCase("Authorization"))continue;
                                boolean present=false;
                                for(String existing:fields.keySet())if(existing.equalsIgnoreCase(header.getKey())){present=true;break;}
                                if(!present)fields.put(header.getKey(),header.getValue());
                            }
                        }
                        boolean managedAuth=host.equals("spclient.wg.spotify.com") || host.endsWith("-spclient.spotify.com");
                        if(managedAuth) {
                            Map<String,String> current=apiHeaders.apply(host,refreshAuth);
                            refreshAuth=false;
                            String bearer=field(current,"Authorization");
                            if(bearer!=null) {
                                replace(fields,"Authorization",bearer);
                                if(!bearer.equals(field(sessionHeaders,"Authorization")) && freshBearerNoted.compareAndSet(false,true))
                                    android.util.Log.i("SpotifeurNativeHttp","fresh Android-session bearer supplied to native API");
                            }
                            String token=field(current,"client-token");
                            if(token!=null && (nativeClientToken==null || nativeClientToken.isEmpty() || field(fields,"client-token")==null))replace(fields,"client-token",token);
                        }
                        if(aborted.get())return;
                        if(offlineForTest){connection.disconnect();continue;}
                        for(Map.Entry<String,String> field:fields.entrySet()) {
                            if(field.getKey().equalsIgnoreCase("Accept-Encoding"))continue;
                            connection.setRequestProperty(field.getKey(),field.getValue());
                        }
                        connection.setRequestProperty("Accept-Encoding","gzip");
                        if(body!=null && (body.length>0 || method.equals("POST") || method.equals("PUT") || method.equals("PATCH"))) {
                            connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length);
                            try(OutputStream stream=connection.getOutputStream()){stream.write(body);}
                        }
                        int status=connection.getResponseCode();
                        if(status==429 && keyFile(url)!=null) {
                            long seconds=30;
                            try { seconds=Long.parseLong(connection.getHeaderField("Retry-After")); }catch(Exception ignored){}
                            synchronized(mediaGate) { nextMediaRequestNanos=Math.max(nextMediaRequestNanos,System.nanoTime()+TimeUnit.SECONDS.toNanos(Math.max(1,Math.min(3600,seconds)))); }
                        }
                        if(status==401 && managedAuth && !authRetried) {
                            authRetried=true;refreshAuth=true;connection.disconnect();continue;
                        }
                        String path=url.getPath();
                        String keyed=keyFile(url);
                        java.util.function.BiConsumer<String,Integer> listener=keyResponseListener;
                        if(keyed!=null && listener!=null && status!=401) {
                            try{listener.accept(keyed,status);}catch(RuntimeException ignored){}
                        }
                        String category=path.contains("license")?"license":path.contains("sneaktables")?"manifest":path.contains("key")?"media-state":"service";
                        if(status>=400)android.util.Log.w("SpotifeurNativeHttp","response category="+category+" status="+status);
                        boolean redirect=status==301 || status==302 || status==303 || status==307 || status==308;
                        String location=connection.getHeaderField("Location");
                        if(redirect && options.isFollowRedirects() && location!=null) {
                            if(redirects>=19) { finish(() -> sink.onError(error("kErrorHttpTooManyRedirects")));return; }
                            URL next=new URL(url,location);
                            if(!next.getHost().equalsIgnoreCase(url.getHost()) || next.getPort()!=url.getPort()) {
                                fields.entrySet().removeIf(field -> field.getKey().equalsIgnoreCase("Authorization") || field.getKey().equalsIgnoreCase("client-token") || field.getKey().equalsIgnoreCase("Cookie"));
                            }
                            if(status==303 || ((status==301 || status==302) && !method.equals("GET") && !method.equals("HEAD"))) {
                                method="GET";body=null;
                                fields.entrySet().removeIf(field -> field.getKey().equalsIgnoreCase("Content-Length") || field.getKey().equalsIgnoreCase("Content-Type") || field.getKey().equalsIgnoreCase("Content-Encoding"));
                            }
                            emit(sink::onRedirect);connection.disconnect();url=next;continue;
                        }
                        StringBuilder raw=new StringBuilder();
                        for(Map.Entry<String,List<String>> field:connection.getHeaderFields().entrySet()) {
                            if(field.getKey()!=null)for(String value:field.getValue())raw.append(field.getKey()).append(": ").append(value).append('\n');
                        }
                        raw.append('\n');
                        HttpResponse response=new HttpResponse(status,url.toString(),raw.toString()); emit(() -> sink.onHeaders(response));
                        InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();
                        if(input!=null&&"gzip".equalsIgnoreCase(connection.getContentEncoding()))input=new java.util.zip.GZIPInputStream(input);
                        ByteArrayOutputStream kept=keyId!=null && status==200?new ByteArrayOutputStream():null;
                        if(input!=null)try(InputStream stream=input) {
                            byte[] buffer=new byte[8192];int count;
                            while(!aborted.get() && (count=stream.read(buffer))!=-1) {
                                if(count==0)continue;
                                if(kept!=null) { if(kept.size()+count<=65536)kept.write(buffer,0,count);else kept=null; }
                                byte[] packet=Arrays.copyOf(buffer,count);emit(() -> sink.onBytesAvailable(packet,packet.length));
                            }
                        }
                        if(expired)throw new SocketTimeoutException();
                        if(kept!=null && kept.size()>0) { byte[] bytes=kept.toByteArray();try{keySave.accept(keyId,bytes);android.util.Log.i("SpotifeurNativeHttp","media envelope saved bytes="+bytes.length);}finally{Arrays.fill(bytes,(byte)0);} }
                        finish(() -> sink.onComplete(false));return;
                    }
                } catch(Exception failure) {
                    if(aborted.get())return;
                    if(!offlineForTest)android.util.Log.w("SpotifeurNativeHttp","transport failure="+failure.getClass().getSimpleName());
                    String code=expired || failure instanceof SocketTimeoutException?"kErrorHttpTimeout":failure instanceof MalformedURLException?"kErrorHttpInvalidUrl":"kErrorHttpConnectFail";
                    finish(() -> sink.onError(error(code)));
                } finally { HttpURLConnection connection=socket;if(connection!=null)connection.disconnect(); }
            });
        }
    }
}
