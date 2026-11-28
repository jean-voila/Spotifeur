package probe.bootstrap;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Owns the supplied SDK's native session graph. Inputs remain in this process. */
public final class NativeEngine implements AutoCloseable {
    private final List<Object> owned=new ArrayList<>();
    private final MemoryCredentials memoryCredentials=new MemoryCredentials();
    private Object timer,processorFactory,delivery,router;
    private Object networkManager;
    private SdkServices services;
    private OrbitBootstrap.CoreScheduler coreScheduler;
    private SdkHttpFactory http;
    private byte[] credentialBytes;
    private boolean closed;
    public void setOfflineForTest(boolean value){SdkHttpFactory.offlineForTest=value;}
    public Object getProcessorFactory(){return processorFactory;}
    public Object getContentDeliveryTransport(){return delivery;}
    public synchronized void updateNetwork(String connectionType,byte[] state) throws Exception {
        if(closed)return;
        invoke(timer,"post",(Runnable)() -> {
            try {
                invoke(networkManager,"setConnectionType",cls("com.spotify.connectivity.ConnectionType").getField(connectionType).get(null),false);
                invoke(networkManager,"setNetworkAllowed",!connectionType.equals("NONE"));
                services.updateReachability(state);
            } catch(Exception failure){note("Network update failed: "+failure.getClass().getSimpleName());}
        });
    }
    public byte[] requestSingle(String service,String method,byte[] body) throws Exception {
        Object single=invoke(delivery,"callSingle",service,method,body);
        single=invoke(single,"timeout",30L,TimeUnit.SECONDS);
        return (byte[])invoke(single,"blockingGet");
    }
    /** Generic internal Esperanto route; the content-delivery transport exposes audio only. */
    public byte[] requestService(String service,String method,byte[] body) throws Exception {
        CompletableFuture<byte[]> result=new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicReference<Object> lifetime=new java.util.concurrent.atomic.AtomicReference<>();
        invoke(timer,"post",(Runnable)() -> {
            try {
                Object request=cls("com.spotify.cosmos.cosmos.Request").getConstructor(String.class,String.class,byte[].class)
                    .newInstance("POST","sp://esperanto/"+service+"/"+method,body);
                Class<?> callbackType=cls("com.spotify.cosmos.cosmos.ResolveCallback");
                Object callback=java.lang.reflect.Proxy.newProxyInstance(callbackType.getClassLoader(),new Class<?>[]{callbackType},(proxy,called,args) -> {
                    if(called.getName().equals("onError"))result.completeExceptionally((Throwable)args[0]);
                    if(called.getName().equals("onResolved")) {
                        int status=((Number)invoke(args[0],"getStatus")).intValue();
                        if(status>=200 && status<300)result.complete((byte[])invoke(args[0],"getBody"));
                        else result.completeExceptionally(new java.io.IOException("Native service status "+status));
                    }
                    return null;
                });
                lifetime.set(invoke(router,"resolve",request,callback));
            } catch(Throwable failure){result.completeExceptionally(failure);}
        });
        try{return result.get(30,TimeUnit.SECONDS);}
        finally {invoke(timer,"post",(Runnable)() -> {try{Object value=lifetime.get();if(value!=null)invoke(value,"release");}catch(Exception ignored){}});}
    }
    public AutoCloseable subscribe(String service,String method,byte[] body,java.util.function.Consumer<byte[]> next,java.util.function.Consumer<Throwable> error) throws Exception {
        final class Subscription implements io.reactivex.rxjava3.core.Observer,AutoCloseable {
            volatile io.reactivex.rxjava3.disposables.Disposable disposable;
            volatile boolean disposed;
            public void onSubscribe(io.reactivex.rxjava3.disposables.Disposable value){disposable=value;if(disposed)value.dispose();}
            public void onNext(Object value){if(!disposed)next.accept((byte[])value);}
            public void onError(Throwable value){if(!disposed)error.accept(value);}
            public void onComplete(){}
            public void close(){disposed=true;if(disposable!=null)disposable.dispose();}
        }
        Subscription subscription=new Subscription();
        invoke(timer,"post",(Runnable)() -> {
            try { ((io.reactivex.rxjava3.core.Observable)invoke(delivery,"callStream",service,method,body)).subscribe(subscription); }
            catch(Throwable failure){subscription.onError(failure);}
        });
        return subscription;
    }
    private static Class<?> cls(String name) throws ClassNotFoundException{return Class.forName(name,true,NativeEngine.class.getClassLoader());}
    private static void note(String message){android.util.Log.i("SpotifeurNative",message);}
    private static Object invoke(Object receiver,String name,Object... args) throws Exception {
        Class<?> type=receiver instanceof Class?(Class<?>)receiver:receiver.getClass();
        for(Method method:type.getMethods()) {
            if(!method.getName().equals(name)||method.getParameterCount()!=args.length)continue;
            Class<?>[] params=method.getParameterTypes();boolean compatible=true;
            for(int i=0;i<args.length;i++) {
                Class<?> expected=params[i];
                if(expected==boolean.class)expected=Boolean.class;
                if(expected==int.class)expected=Integer.class;
                if(expected==long.class)expected=Long.class;
                if(args[i]!=null&&!expected.isInstance(args[i]))compatible=false;
            }
            if(compatible)return method.invoke(receiver instanceof Class?null:receiver,args);
        }
        throw new NoSuchMethodException(type.getName()+"."+name);
    }
    public static void load(String library){System.load(library);}
    public static NativeEngine open(Map<String,Object> input) throws Exception {
        NativeEngine engine=new NativeEngine();
        try{engine.initialize(input);return engine;}catch(Exception error){engine.close();throw error;}
    }
    @SuppressWarnings("unchecked") private void initialize(Map<String,Object> input) throws Exception {
        final String directory=(String)input.get("directory");
        final String username=(String)input.get("username");
        final String accessToken=(String)input.get("accessToken");
                final String deviceId=(String)input.get("deviceId");
        final Map<String,String> attributes=new HashMap<>((Map<String,String>)input.get("attributes"));
        credentialBytes=((byte[])input.get("credential")).clone();
        timer=invoke(cls("com.spotify.concurrency.asyncimpl.NativeTimerManagerThreadImpl"),"create","Spotifeur Core");
        invoke(timer,"start");
        OrbitBootstrap.CoreScheduler scheduler=new OrbitBootstrap.CoreScheduler(timer);
        coreScheduler=scheduler;
        Object cosmosScheduler=scheduler;
        Object analytics=new OrbitBootstrap.OfflineAnalytics();
        Object events=new OrbitBootstrap.OfflineEvents();
        services=new SdkServices((byte[])input.get("reachability"));
        Object reachability=services;
        Map<String,String> sessionHeaders=new HashMap<>();
        sessionHeaders.put("Authorization","Bearer "+accessToken);
        sessionHeaders.put("client-token",(String)input.get("clientToken"));
        sessionHeaders.put("Spotify-App-Version","9.2.4.2135");
        sessionHeaders.put("X-Client-Id","9a8d2f0ce77a4e248bb71fefcb557637");
        sessionHeaders.put("App-Platform","Android");
        SdkHttpFactory.offlineForTest=Boolean.TRUE.equals(input.get("offline"));
        http=new SdkHttpFactory(scheduler,sessionHeaders,
            (java.util.function.Function<String,byte[]>)input.getOrDefault("keyLoad",(java.util.function.Function<String,byte[]>)(id -> null)),
            (java.util.function.BiConsumer<String,byte[]>)input.getOrDefault("keySave",(java.util.function.BiConsumer<String,byte[]>)((id,bytes) -> {})),
            (java.util.function.BiFunction<String,Boolean,Map<String,String>>)input.getOrDefault("apiHeaders",(java.util.function.BiFunction<String,Boolean,Map<String,String>>)((host,rejected) -> Collections.emptyMap())));
        CountDownLatch done=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        invoke(timer,"post",(Runnable)() -> {
            List<Object> owned=this.owned;
            List<Object> sessionOwned=this.owned;
            try {
                    note("Creating native session services");
                    invoke(cls("com.spotify.core.http.NativeHttpConnection"),"initialize",http);
                    Object mobileInfo=cls("com.spotify.connectivity.MobileDeviceInfo").getConstructor().newInstance();
                    invoke(mobileInfo,"setApiLevel",android.os.Build.VERSION.SDK_INT);
                    invoke(mobileInfo,"setOsVersion",android.os.Build.VERSION.RELEASE);
                    invoke(mobileInfo,"setName","Spotifeur");
                    invoke(mobileInfo,"setModel",android.os.Build.MODEL);
                    invoke(mobileInfo,"setBrand",android.os.Build.BRAND);
                    invoke(mobileInfo,"setManufacturer",android.os.Build.MANUFACTURER);
                    invoke(mobileInfo,"setCachePath",directory);
                    invoke(mobileInfo,"setInstallerName","");
                    invoke(mobileInfo,"setDesktopFormFactor",false);
                    android.util.DisplayMetrics display=android.content.res.Resources.getSystem().getDisplayMetrics();
                    invoke(mobileInfo,"setScreenWidth",display.widthPixels);
                    invoke(mobileInfo,"setScreenHeight",display.heightPixels);
                    invoke(mobileInfo,"setScreenDensityCurrent",display.densityDpi);
                    invoke(mobileInfo,"setScreenDensityStable",android.util.DisplayMetrics.DENSITY_DEVICE_STABLE);
                    invoke(mobileInfo,"setSmallestScreenWidthDp",android.content.res.Resources.getSystem().getConfiguration().smallestScreenWidthDp);
                    invoke(cls("com.spotify.connectivity.ConnectivitySdk"),"setMobileDeviceInfo",mobileInfo);
                    note("Native HTTP and device configuration initialized");
                    Object fileIO = invoke(cls("com.spotify.fileaccess.fileioimpl.NativeFileIOImpl"), "create"); owned.add(fileIO);
                    Object prefs = invoke(cls("com.spotify.prefs.prefsimpl.NativePrefs"), "create"); owned.add(prefs);
                    router = invoke(cls("com.spotify.cosmos.cosmosimpl.NativeRouter"), "create"); owned.add(router);
                    invoke(router, "initializeScheduling", cosmosScheduler);
                    note("Core primitives and router scheduling CREATED");
                    Object manager = invoke(cls("com.spotify.connectivity.connectivitysdkpolicyimpl.NativeConnectivityManager"), "create", router, scheduler, analytics, true); owned.add(manager);
                    networkManager=manager;
                    invoke(manager,"setNetworkAllowed",true);
                    invoke(manager,"setConnectionType",cls("com.spotify.connectivity.ConnectionType").getField((String)input.get("connectionType")).get(null),false);
                    Object connection = invoke(cls("com.spotify.connectivity.connectivitysdkpolicyimpl.DefaultConnectionTypeProvider"), "create", manager); owned.add(connection);
                    Object policy = invoke(cls("com.spotify.connectivity.connectivitysdkpolicyimpl.DefaultConnectivityPolicyProvider"), "create", manager); owned.add(policy);
                    Object credentials = memoryCredentials;
                    Object traffic = invoke(cls("com.spotify.connectivity.trafficsetupimpl.NativeTrafficSetupImpl"), "create"); owned.add(traffic);
                    note("Connectivity manager/providers, empty credential store, traffic CREATED");
                    File cache = new File(directory,"connectivity-cache"); cache.mkdirs();
                    Object config = cls("com.spotify.connectivity.ApplicationScopeConfiguration").getConstructor().newInstance();
                    invoke(config,"setCachePath",cache.getPath());
                    invoke(config,"setClientId","9a8d2f0ce77a4e248bb71fefcb557637");
                    invoke(config,"setClientVersionLong","9.2.4.2135");
                    invoke(config,"setDeviceId",deviceId);
                    invoke(config,"setDeviceHardwareModel","Spotifeur");
                    invoke(config,"setDefaultHTTPUserAgent","Spotify/9.2.4.2135 Android/"+android.os.Build.VERSION.SDK_INT+" (Spotifeur)");
                    invoke(config,"setAccesspointLanguage","en");
                    // The app's Android ClientToken provider owns renewal, including after an offline start.
                    invoke(config,"setEnableClientToken",false);
                    invoke(config,"setAppNativeLogin5SetupLifecycle",true);
                    invoke(config,"setAppNativeAccountsSetupLifecycle",false);
                    note("BEFORE connectivity NativeApplicationScope.create");
                    Object connectivity = invoke(cls("com.spotify.connectivity.NativeApplicationScope"), "create", timer,router,analytics,connection,policy,credentials,config,reachability,traffic,fileIO);
                    owned.add(connectivity); note("Connectivity NativeApplicationScope CREATED");
                    File coreCache = new File(directory,"core-cache"); coreCache.mkdirs();
                    File settings = new File(directory,"core-settings"); settings.mkdirs();
                    Object coreConfig = cls("com.spotify.core.coreimpl.ApplicationScopeConfiguration").getConstructor(String.class,String.class,boolean.class,String.class).newInstance(coreCache.getPath(),settings.getPath(),false,"");
                    note("BEFORE Core NativeApplicationScopeImpl.create");
                    Object coreApp = invoke(cls("com.spotify.core.coreimpl.NativeApplicationScopeImpl"), "create",timer,router,prefs,coreConfig,events,fileIO);
                    owned.add(coreApp); note("Core NativeApplicationScopeImpl CREATED");
                    Object transport = invoke(coreApp,"serverTimeTransport");
                    note("Core serverTimeTransport: " + (transport != null ? "AVAILABLE" : "NULL"));
                    Object loginConfig = cls("com.spotify.authentication.login5.Login5Configuration").getConstructor().newInstance();
                    invoke(loginConfig,"setClientId","9a8d2f0ce77a4e248bb71fefcb557637");
                    invoke(loginConfig,"setDeviceId",deviceId);
                    invoke(loginConfig,"setProtocolVersion",3);
                    note("BEFORE Login5/OAuth scopes ");
                    Object login5 = invoke(cls("com.spotify.authentication.login5setupimpl.NativeLogin5SetupImpl"),"create",loginConfig,timer,connectivity); sessionOwned.add(login5);
                    Object access = cls("com.spotify.authentication.oauth.AccessToken").getConstructor().newInstance();
                    invoke(access,"setTokenType","Bearer"); invoke(access,"setAccessToken",accessToken.isEmpty()?"spotifeur-local-media":accessToken);
                    // This is an internal media-context lifetime, not a server grant. Real outgoing
                    // bearer tokens are checked/renewed by apiHeaders before every native API call.
                    // Expired web credentials must not block interpretation of an on-device envelope.
                    invoke(access,"setExpiresAt",2147483647L);
                    Object credential = cls("com.spotify.authentication.credentials.UnencryptedCredentials").getConstructor(String.class,byte[].class).newInstance(username,credentialBytes);
                    Object backoff = cls("com.spotify.authentication.oauthsetupimpl.Login5BackoffConfig").getConstructor().newInstance();
                    Object oauth = invoke(cls("com.spotify.authentication.oauthsetupimpl.NativeOAuthSetupImpl"),"create",loginConfig,timer,login5,access,credential,router,connectivity,backoff,false,false); sessionOwned.add(oauth);
                    note("Login5/OAuth scopes CREATED");
                    note("BEFORE PubSub.create");
                    Object pubsub = invoke(cls("com.spotify.connectivity.pubsubsetupv2impl.NativePubSubSetupV2Impl"),"create",reachability,timer,oauth,connectivity); sessionOwned.add(pubsub);
                    note("PubSub CREATED");
                    Object productState = invoke(cls("com.spotify.connectivity.auth.NativeProductStateSetup"),"create",timer,attributes); sessionOwned.add(productState);
                    Object session = invoke(cls("com.spotify.connectivity.auth.NativeSession"),"createNativeSessionWithoutAp",timer,connectivity,credential,oauth,productState,deviceId); sessionOwned.add(session);
                    note("NativeSession CREATED ");
                    note("Native components: oauthPresent="+(oauth!=null)+" oauthHandle="+(oauth!=null && ((Number)invoke(oauth,"getNThis")).longValue()!=0)+" login5Present="+(login5!=null));
                    Object authenticatedConfig = cls("com.spotify.connectivity.AuthenticatedScopeConfiguration").getConstructor().newInstance();
                    invoke(authenticatedConfig,"setCachePath",cache.getPath());
                    invoke(authenticatedConfig,"setClientId","9a8d2f0ce77a4e248bb71fefcb557637");
                    invoke(authenticatedConfig,"setClientVersionLong","9.2.4.2135");
                    invoke(authenticatedConfig,"setDeviceId",deviceId);
                    invoke(authenticatedConfig,"setWebgateLocale","fr-FR");
                    invoke(authenticatedConfig,"setWebgateTimeZone","Europe/Paris");
                    invoke(authenticatedConfig,"setWebgateUserAgentParameterAppPlatform","Android");
                    invoke(authenticatedConfig,"setWebgateUserAgentParameterAppVersion","9.2.4.2135");
                    invoke(authenticatedConfig,"setWebgateUserAgentParameterOsVersion",android.os.Build.VERSION.RELEASE);
                    note("BEFORE NativeAuthenticatedScope.create");
                    Object authenticated = invoke(cls("com.spotify.connectivity.NativeAuthenticatedScope"),"create",timer,router,connectivity,session,pubsub,analytics,authenticatedConfig,fileIO); sessionOwned.add(authenticated);
                    note("NativeAuthenticatedScope CREATED");
                    Object remote = invoke(cls("com.spotify.remoteconfig.NativeRemoteConfigImpl"),"create",reachability); sessionOwned.add(remote);
                    Object local = invoke(cls("com.spotify.localfiles.localfilesimpl.NativeLocalFilesDelegate"),"noop"); sessionOwned.add(local);
                    Object userdir = invoke(cls("com.spotify.appstorage.userdirectoryimpl.NativeUserDirectoryManagerImpl"),"create",coreCache.getPath(),settings.getPath(),username); sessionOwned.add(userdir);
                    Object foreground = invoke(cls("com.spotify.offline.NativeForegroundProvider"),"noop"); sessionOwned.add(foreground);
                    Object registry = cls("com.spotify.offline.NativeOfflinePluginRegistry").getConstructor(java.util.List.class).newInstance(new ArrayList<>());
                    Object fullConfig = cls("com.spotify.core.corefullimpl.FullAuthenticatedScopeConfiguration").getConstructor().newInstance();
                    invoke(fullConfig,"setClientId","9a8d2f0ce77a4e248bb71fefcb557637");
                    invoke(fullConfig,"setDeviceId",deviceId);
                    invoke(fullConfig,"setCachePath",coreCache.getPath());
                    invoke(fullConfig,"setSettingsPath",settings.getPath());
                    invoke(fullConfig,"setVolatileCachePath",new File(directory,"volatile").getPath());
                    invoke(fullConfig,"setNativeBundleId","com.spotify.music");
                    invoke(fullConfig,"setVersionName","9.2.4.2135");
                    invoke(fullConfig,"setCanPlay",true);
                    invoke(fullConfig,"setDeviceType",invoke(cls("com.spotify.core.core.DeviceType").getField("SMARTPHONE").get(null),"getValue"));
                    invoke(fullConfig,"setDeviceName","Spotifeur");
                    invoke(fullConfig,"setBrand",android.os.Build.BRAND);
                    invoke(fullConfig,"setModel",android.os.Build.MODEL);
                    invoke(fullConfig,"setLocale","fr-FR");
                    invoke(fullConfig,"setSupportedContentTypes",(Object)new String[]{"audio"});
                    Object crypto = invoke(cls("p.n0q0"),"a");
                    note("BEFORE FullAuthenticatedScope.create");
                    Object full = invoke(cls("com.spotify.core.corefullimpl.NativeFullAuthenticatedScopeImpl"),"create",timer,router,prefs,remote,manager,connectivity,session,authenticated,coreApp,local,userdir,fullConfig,registry,foreground,fileIO,null,reachability,reachability,reachability,crypto); sessionOwned.add(full);
                    note("FullAuthenticatedScope CREATED");
                    processorFactory = invoke(full,"mediaProcessorFactory"); sessionOwned.add(processorFactory);
                    note("mediaProcessorFactory AVAILABLE="+(processorFactory != null));
                    delivery = invoke(full,"contentDeliveryIOTransport"); note("contentDeliveryIOTransport AVAILABLE="+(delivery != null));


            } catch(Throwable error){failure.set(error);} finally{done.countDown();}
        });
        if(!done.await(30,TimeUnit.SECONDS))throw new IllegalStateException("Native initialization timed out");
        if(failure.get()!=null)throw new IllegalStateException("Native initialization failed",failure.get());
        if(processorFactory==null||delivery==null)throw new IllegalStateException("Native audio components unavailable");
    }
    private void onCore(Runnable action) throws Exception {
        CountDownLatch done=new CountDownLatch(1);
        invoke(timer,"post",(Runnable)() -> {try{action.run();}finally{done.countDown();}});
        if(!done.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Native shutdown timed out");
    }
    @Override public synchronized void close() {
        if(closed)return;closed=true;
        if(timer==null)return;
        try {
            // Let callbacks posted by shutdown run before their owners are destroyed.
            onCore(() -> { for(int i=owned.size()-1;i>=0;i--)try{invoke(owned.get(i),"prepareForShutdown");}catch(Exception ignored){} });
            onCore(() -> { if(http!=null)http.close(); });
            for(int i=owned.size()-1;i>=0;i--) {
                Object object=owned.get(i);
                onCore(() -> {try{invoke(object,"flushCaches");}catch(Exception ignored){} });
                onCore(() -> {try{invoke(object,"destroy");}catch(Exception ignored){} });
            }
            owned.clear();memoryCredentials.deleteStoredCredentials();
            if(credentialBytes!=null)Arrays.fill(credentialBytes,(byte)0);
            onCore(() -> {});
            coreScheduler.close();
            invoke(timer,"stop");invoke(timer,"destroy");timer=null;
        } catch(Exception ignored) { }
    }
}
