package probe.bootstrap;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Runs under the same child ClassLoader as the supplied Spotify DEX. */
public final class OrbitBootstrap {
    private static final String DEVICE = "00000000-0000-4000-8000-000000000001";
    private static Object invoke(Object receiver, String name, Object... args) throws Exception {
        Class<?> type = receiver instanceof Class ? (Class<?>) receiver : receiver.getClass();
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != args.length) continue;
            Class<?>[] params = method.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < args.length; i++) {
                Class<?> expected = params[i];
                if (expected == boolean.class) expected = Boolean.class;
                if (expected == int.class) expected = Integer.class;
                if (expected == long.class) expected = Long.class;
                if (args[i] != null && !expected.isInstance(args[i])) compatible = false;
            }
            if (compatible) return method.invoke(receiver instanceof Class ? null : receiver, args);
        }
        throw new NoSuchMethodException(type.getName() + "." + name + "/" + args.length);
    }
    public static final class CoreScheduler implements com.spotify.connectivity.Scheduler, com.spotify.cosmos.cosmosimpl.Scheduler, AutoCloseable {
        private final Object timer;
        private boolean closed;
        public CoreScheduler(Object timer) { this.timer=timer; }
        @Override public synchronized void post(Runnable callback) {
            if(closed)return;
            try { invoke(timer,"post",callback); } catch (Exception error) { throw new IllegalStateException(error); }
        }
        @Override public synchronized boolean isOnSchedulerThread() {
            if(closed)return false;
            try { return (Boolean)invoke(timer,"isCurrentThread"); } catch (Exception error) { throw new IllegalStateException(error); }
        }
        @Override public synchronized void close(){closed=true;}
    }
    public static final class OfflineAnalytics implements com.spotify.connectivity.AnalyticsDelegate {
        @Override public void logEvent(byte[] a,byte[] b) { }
    }
    public static final class OfflineEvents implements com.spotify.eventsender.api.EventSenderCoreBridge {
        @Override public void queueMetricsDataSnapshotForSending(byte[] a,byte[] b) { }
        @Override public int send(byte[] a,byte[] b,byte[] c,com.spotify.eventsender.api.EventPersistedCallback callback) { return 0; }
    }
    public static final class OfflineReachability implements com.spotify.esperanto.esperanto.Transport {
        @Override public io.reactivex.rxjava3.core.Observable callStream(String service,String method,byte[] body) {
            android.util.Log.i("OrbitOfflineProbe","STREAM "+service+"/"+method);
            try { return (io.reactivex.rxjava3.core.Observable)invoke(cls("io.reactivex.rxjava3.core.Observable"),"never"); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }
        @Override public io.reactivex.rxjava3.core.Single callSingle(String service,String method,byte[] body) {
            android.util.Log.i("OrbitOfflineProbe","SINGLE "+service+"/"+method);
            try { return (io.reactivex.rxjava3.core.Single)invoke(cls("io.reactivex.rxjava3.core.Single"),"just",new byte[0]); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }
        @Override public byte[] callSync(String service,String method,byte[] body) { android.util.Log.i("OrbitOfflineProbe","SYNC "+service+"/"+method); return new byte[0]; }
    }
    public static final class OfflineHttpFactory implements com.spotify.core.http.HttpConnectionFactory {
        private final CoreScheduler scheduler;
        public OfflineHttpFactory(CoreScheduler scheduler) { this.scheduler=scheduler; }
        @Override public com.spotify.core.http.HttpConnectionDelegate createDelegate() { return new OfflineHttpDelegate(scheduler); }
    }
    public static final class OfflineHttpDelegate implements com.spotify.core.http.HttpConnectionDelegate {
        private final CoreScheduler scheduler;
        private volatile boolean started;
        private volatile boolean aborted;
        public OfflineHttpDelegate(CoreScheduler scheduler) { this.scheduler=scheduler; }
        @Override public void abort() { aborted=true; }
        @Override public boolean isRequestStarted() { return started; }
        @Override public void send(com.spotify.core.http.HttpConnection connection,com.spotify.core.http.HttpRequest request,com.spotify.core.http.HttpOptions options) {
            started=true;
            scheduler.post(() -> {
                if (aborted) return;
                try { connection.onError(cls("com.spotify.core.http.HttpConnection").getField("kErrorHttpConnectFail").getInt(null)); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
        }
    }
    private static void stage(List<String> report, File journal, String message) {
        report.add(message);
        android.util.Log.i("OrbitOfflineProbe", message);
        try (FileOutputStream out = new FileOutputStream(journal, true)) {
            out.write((message + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }
    /** Offline construction fixtures only: no real account or credential input. */
    public static List<String> runScopes(String directory) {
        List<String> report = new ArrayList<>();
        File journal = new File(directory, "scopes-report.txt");
        journal.delete();
        List<Object> owned = new ArrayList<>();
        Object timer = null;
        try {
            timer = invoke(cls("com.spotify.concurrency.asyncimpl.NativeTimerManagerThreadImpl"), "create", "Orbit Offline Scope Probe");
            invoke(timer, "start");
            final Object coreTimer = timer;
            CoreScheduler scheduler = new CoreScheduler(coreTimer);
            Object cosmosScheduler = scheduler;
            Object analytics = new OfflineAnalytics();
            Object events = new OfflineEvents();
            Object reachability = new OfflineReachability();
            CountDownLatch done = new CountDownLatch(1);
            invoke(coreTimer, "post", (Runnable) () -> {
                Object router = null, connectivity = null, coreApp = null;
                List<Object> sessionOwned = new ArrayList<>();
                try {
                    stage(report,journal,"START scopes (no INTERNET permission, no credentials)");
                    invoke(cls("com.spotify.core.http.NativeHttpConnection"),"initialize",new OfflineHttpFactory(scheduler));
                    Object mobileInfo=cls("com.spotify.connectivity.MobileDeviceInfo").getConstructor().newInstance();
                    invoke(mobileInfo,"setApiLevel",android.os.Build.VERSION.SDK_INT);
                    invoke(mobileInfo,"setOsVersion",android.os.Build.VERSION.RELEASE);
                    invoke(mobileInfo,"setName","OfflineOrbitProbe");
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
                    stage(report,journal,"Global OfflineHttpFactory + ConnectivitySdk MobileDeviceInfo INITIALIZED");
                    Object fileIO = invoke(cls("com.spotify.fileaccess.fileioimpl.NativeFileIOImpl"), "create"); owned.add(fileIO);
                    Object prefs = invoke(cls("com.spotify.prefs.prefsimpl.NativePrefs"), "create"); owned.add(prefs);
                    router = invoke(cls("com.spotify.cosmos.cosmosimpl.NativeRouter"), "create"); owned.add(router);
                    invoke(router, "initializeScheduling", cosmosScheduler);
                    stage(report,journal,"Core primitives and router scheduling CREATED");
                    Object manager = invoke(cls("com.spotify.connectivity.connectivitysdkpolicyimpl.NativeConnectivityManager"), "create", router, scheduler, analytics, true); owned.add(manager);
                    invoke(manager,"setNetworkAllowed",false);
                    invoke(manager,"setConnectionType",cls("com.spotify.connectivity.ConnectionType").getField("NONE").get(null),false);
                    Object connection = invoke(cls("com.spotify.connectivity.connectivitysdkpolicyimpl.DefaultConnectionTypeProvider"), "create", manager); owned.add(connection);
                    Object policy = invoke(cls("com.spotify.connectivity.connectivitysdkpolicyimpl.DefaultConnectivityPolicyProvider"), "create", manager); owned.add(policy);
                    Object credentials = invoke(cls("com.spotify.connectivity.connectivitysdkcredentialsstorage.PrefsCredentialsStorage"), "create", prefs, DEVICE); owned.add(credentials);
                    Object traffic = invoke(cls("com.spotify.connectivity.trafficsetupimpl.NativeTrafficSetupImpl"), "create"); owned.add(traffic);
                    stage(report,journal,"Connectivity manager/providers, empty credential store, traffic CREATED");
                    File cache = new File(directory,"connectivity-cache"); cache.mkdirs();
                    Object config = cls("com.spotify.connectivity.ApplicationScopeConfiguration").getConstructor().newInstance();
                    invoke(config,"setCachePath",cache.getPath());
                    invoke(config,"setClientId","9a8d2f0ce77a4e248bb71fefcb557637");
                    invoke(config,"setClientVersionLong","9.2.4.2135");
                    invoke(config,"setDeviceId",DEVICE);
                    invoke(config,"setDeviceHardwareModel","OfflineOrbitProbe");
                    invoke(config,"setDefaultHTTPUserAgent","Spotify/9.2.4.2135 Android/36 (OfflineOrbitProbe)");
                    invoke(config,"setAccesspointLanguage","en");
                    invoke(config,"setEnableClientToken",false);
                    invoke(config,"setAppNativeLogin5SetupLifecycle",true);
                    invoke(config,"setAppNativeAccountsSetupLifecycle",false);
                    stage(report,journal,"BEFORE connectivity NativeApplicationScope.create");
                    connectivity = invoke(cls("com.spotify.connectivity.NativeApplicationScope"), "create", coreTimer,router,analytics,connection,policy,credentials,config,reachability,traffic,fileIO);
                    stage(report,journal,"Connectivity NativeApplicationScope CREATED");
                    File coreCache = new File(directory,"core-cache"); coreCache.mkdirs();
                    File settings = new File(directory,"core-settings"); settings.mkdirs();
                    Object coreConfig = cls("com.spotify.core.coreimpl.ApplicationScopeConfiguration").getConstructor(String.class,String.class,boolean.class,String.class).newInstance(coreCache.getPath(),settings.getPath(),false,"");
                    stage(report,journal,"BEFORE Core NativeApplicationScopeImpl.create");
                    coreApp = invoke(cls("com.spotify.core.coreimpl.NativeApplicationScopeImpl"), "create",coreTimer,router,prefs,coreConfig,events,fileIO);
                    stage(report,journal,"Core NativeApplicationScopeImpl CREATED");
                    Object transport = invoke(coreApp,"serverTimeTransport");
                    stage(report,journal,"Core serverTimeTransport: " + (transport != null ? "AVAILABLE" : "NULL"));
                    Object loginConfig = cls("com.spotify.authentication.login5.Login5Configuration").getConstructor().newInstance();
                    invoke(loginConfig,"setClientId","9a8d2f0ce77a4e248bb71fefcb557637");
                    invoke(loginConfig,"setDeviceId",DEVICE);
                    invoke(loginConfig,"setProtocolVersion",3);
                    stage(report,journal,"BEFORE fixture Login5/OAuth scopes (offline, no account)");
                    Object login5 = invoke(cls("com.spotify.authentication.login5setupimpl.NativeLogin5SetupImpl"),"create",loginConfig,coreTimer,connectivity); sessionOwned.add(login5);
                    Object access = cls("com.spotify.authentication.oauth.AccessToken").getConstructor().newInstance();
                    invoke(access,"setTokenType","Bearer"); invoke(access,"setAccessToken","offline-probe-no-account");
                    invoke(access,"setExpiresAt",System.currentTimeMillis()/1000+3600);
                    Object credential = cls("com.spotify.authentication.credentials.UnencryptedCredentials").getConstructor(String.class,byte[].class).newInstance("orbit-offline-probe",new byte[0]);
                    Object backoff = cls("com.spotify.authentication.oauthsetupimpl.Login5BackoffConfig").getConstructor().newInstance();
                    Object oauth = invoke(cls("com.spotify.authentication.oauthsetupimpl.NativeOAuthSetupImpl"),"create",loginConfig,coreTimer,login5,access,credential,router,connectivity,backoff,false,false); sessionOwned.add(oauth);
                    stage(report,journal,"Fixture Login5/OAuth scopes CREATED");
                    stage(report,journal,"BEFORE Fixture PubSub.create");
                    Object pubsub = invoke(cls("com.spotify.connectivity.pubsubsetupv2impl.NativePubSubSetupV2Impl"),"create",reachability,coreTimer,oauth,connectivity); sessionOwned.add(pubsub);
                    java.util.Map<String,String> fixtureAttributes=new java.util.HashMap<>();
                    fixtureAttributes.put("type","free"); fixtureAttributes.put("catalogue","free"); fixtureAttributes.put("product","free"); fixtureAttributes.put("country","FR");
                    stage(report,journal,"Fixture PubSub CREATED");
                    Object productState = invoke(cls("com.spotify.connectivity.auth.NativeProductStateSetup"),"create",coreTimer,fixtureAttributes); sessionOwned.add(productState);
                    Object session = invoke(cls("com.spotify.connectivity.auth.NativeSession"),"createNativeSessionWithoutAp",coreTimer,connectivity,credential,oauth,productState,DEVICE); sessionOwned.add(session);
                    stage(report,journal,"Fixture NativeSession CREATED (not authenticated by server)");
                    stage(report,journal,"Native wrappers: oauthPresent="+(oauth!=null)+" oauthHandle="+(oauth!=null && ((Number)invoke(oauth,"getNThis")).longValue()!=0)+" login5Present="+(login5!=null));
                    Object authenticatedConfig = cls("com.spotify.connectivity.AuthenticatedScopeConfiguration").getConstructor().newInstance();
                    invoke(authenticatedConfig,"setCachePath",cache.getPath());
                    invoke(authenticatedConfig,"setClientId","9a8d2f0ce77a4e248bb71fefcb557637");
                    invoke(authenticatedConfig,"setClientVersionLong","9.2.4.2135");
                    invoke(authenticatedConfig,"setDeviceId",DEVICE);
                    invoke(authenticatedConfig,"setWebgateLocale","fr-FR");
                    invoke(authenticatedConfig,"setWebgateTimeZone","Europe/Paris");
                    invoke(authenticatedConfig,"setWebgateUserAgentParameterAppPlatform","Android");
                    invoke(authenticatedConfig,"setWebgateUserAgentParameterAppVersion","9.2.4.2135");
                    invoke(authenticatedConfig,"setWebgateUserAgentParameterOsVersion",android.os.Build.VERSION.RELEASE);
                    stage(report,journal,"BEFORE Fixture NativeAuthenticatedScope.create");
                    Object authenticated = invoke(cls("com.spotify.connectivity.NativeAuthenticatedScope"),"create",coreTimer,router,connectivity,session,pubsub,analytics,authenticatedConfig,fileIO); sessionOwned.add(authenticated);
                    stage(report,journal,"Fixture NativeAuthenticatedScope CREATED");
                    Object remote = invoke(cls("com.spotify.remoteconfig.NativeRemoteConfigImpl"),"create",reachability); sessionOwned.add(remote);
                    Object local = invoke(cls("com.spotify.localfiles.localfilesimpl.NativeLocalFilesDelegate"),"noop"); sessionOwned.add(local);
                    Object userdir = invoke(cls("com.spotify.appstorage.userdirectoryimpl.NativeUserDirectoryManagerImpl"),"create",coreCache.getPath(),settings.getPath(),"orbit-offline-probe"); sessionOwned.add(userdir);
                    Object foreground = invoke(cls("com.spotify.offline.NativeForegroundProvider"),"noop"); sessionOwned.add(foreground);
                    Object registry = cls("com.spotify.offline.NativeOfflinePluginRegistry").getConstructor(java.util.List.class).newInstance(new ArrayList<>());
                    Object fullConfig = cls("com.spotify.core.corefullimpl.FullAuthenticatedScopeConfiguration").getConstructor().newInstance();
                    invoke(fullConfig,"setClientId","9a8d2f0ce77a4e248bb71fefcb557637");
                    invoke(fullConfig,"setDeviceId",DEVICE);
                    invoke(fullConfig,"setCachePath",coreCache.getPath());
                    invoke(fullConfig,"setSettingsPath",settings.getPath());
                    invoke(fullConfig,"setVolatileCachePath",new File(directory,"volatile").getPath());
                    invoke(fullConfig,"setNativeBundleId","fr.spotifeur.orbitprobe");
                    invoke(fullConfig,"setVersionName","9.2.4.2135");
                    invoke(fullConfig,"setSupportedContentTypes",(Object)new String[]{"audio"});
                    Object crypto = cls("com.spotify.native_crypto.NativeCryptoProvider").getConstructor().newInstance();
                    stage(report,journal,"BEFORE Fixture FullAuthenticatedScope.create");
                    Object full = invoke(cls("com.spotify.core.corefullimpl.NativeFullAuthenticatedScopeImpl"),"create",coreTimer,router,prefs,remote,manager,connectivity,session,authenticated,coreApp,local,userdir,fullConfig,registry,foreground,fileIO,null,reachability,reachability,reachability,crypto); sessionOwned.add(full);
                    stage(report,journal,"Fixture FullAuthenticatedScope CREATED");
                    Object processorFactory = invoke(full,"mediaProcessorFactory"); sessionOwned.add(processorFactory);
                    stage(report,journal,"mediaProcessorFactory AVAILABLE="+(processorFactory != null));
                    stage(report,journal,"contentDeliveryIOTransport AVAILABLE="+(invoke(full,"contentDeliveryIOTransport") != null));

                } catch (Throwable error) {
                    stage(report,journal,"SCOPE FAILURE: " + cause(error).getClass().getName() + ": " + cause(error).getMessage());
                } finally {
                    for(int i=sessionOwned.size()-1;i>=0;i--) {
                        Object item=sessionOwned.get(i);
                        try { try { invoke(item,"prepareForShutdown"); } catch(NoSuchMethodException ignored) { } invoke(item,"destroy"); }
                        catch(Throwable error) { stage(report,journal,"Fixture cleanup: "+cause(error).getClass().getName()); }
                    }
                    for (Object scope : new Object[]{coreApp,connectivity}) if (scope != null) {
                        try { invoke(scope,"prepareForShutdown"); invoke(scope,"destroy"); stage(report,journal,scope.getClass().getName()+" DESTROYED"); }
                        catch (Throwable error) { stage(report,journal,"Scope cleanup: "+cause(error).getClass().getName()); }
                    }
                    if (router != null) try { invoke(router,"deinitializeScheduling"); } catch (Throwable ignored) { }
                    for (int i=owned.size()-1;i>=0;i--) try { invoke(owned.get(i),"destroy"); } catch (Throwable error) { stage(report,journal,"Primitive cleanup: "+cause(error).getClass().getName()); }
                    done.countDown();
                }
            });
            if (!done.await(15,TimeUnit.SECONDS)) {
                stage(report,journal,"Scope bootstrap TIMEOUT; process retained to avoid destroying objects in use");
                return report;
            }
            invoke(timer,"stop"); invoke(timer,"destroy"); timer = null;
            stage(report,journal,"Scope core timer DESTROYED; no account/session constructed");
        } catch (Throwable error) {
            stage(report,journal,"BOOTSTRAP FAILURE: "+cause(error).getClass().getName()+": "+cause(error).getMessage());
        }
        return report;
    }
    private static Throwable cause(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        return error;
    }
    private static Class<?> cls(String name) throws ClassNotFoundException {
        return Class.forName(name, true, OrbitBootstrap.class.getClassLoader());
    }
    private static void primitive(List<String> lines, String name, String create) {
        Object object = null;
        try {
            Class<?> type = cls(name);
            object = type.getMethod(create).invoke(null);
            if (object == null) throw new IllegalStateException("null result");
            // Report only pointer existence; never publish native addresses.
            lines.add(name + ": CREATED");
        } catch (Throwable error) {
            lines.add(name + ": " + cause(error).getClass().getName());
        } finally {
            if (object != null) try {
                object.getClass().getMethod("destroy").invoke(object);
                lines.add(name + ": DESTROYED");
            } catch (Throwable error) {
                lines.add(name + " destroy: " + cause(error).getClass().getName());
            }
        }
    }
    public static List<String> run(String libraryPath) {
        List<String> lines = new ArrayList<>();
        try {
            System.load(libraryPath);
            lines.add("JNI_OnLoad: LOADED");
        } catch (Throwable error) {
            lines.add("JNI_OnLoad: " + cause(error).getClass().getName());
            // No credentials have been loaded; error text is useful solely for linkage diagnostics.
            lines.add(String.valueOf(cause(error).getMessage()));
            return lines;
        }
        primitive(lines, "com.spotify.fileaccess.fileioimpl.NativeFileIOImpl", "create");
        primitive(lines, "com.spotify.prefs.prefsimpl.NativePrefs", "create");
        primitive(lines, "com.spotify.cosmos.cosmosimpl.NativeRouter", "create");
        primitive(lines, "com.spotify.localfiles.localfilesimpl.NativeLocalFilesDelegate", "noop");
        Object timer = null;
        boolean started = false;
        try {
            Class<?> timerType = cls("com.spotify.concurrency.asyncimpl.NativeTimerManagerThreadImpl");
            timer = timerType.getMethod("create", String.class).invoke(null, "Orbit Offline Probe");
            timerType.getMethod("start").invoke(timer);
            started = true;
            CountDownLatch ran = new CountDownLatch(1);
            timerType.getMethod("post", Runnable.class).invoke(timer, (Runnable) ran::countDown);
            lines.add("Core timer post: " + (ran.await(3, TimeUnit.SECONDS) ? "RAN" : "TIMEOUT"));
        } catch (Throwable error) {
            lines.add("Core timer: " + cause(error).getClass().getName());
        } finally {
            if (timer != null) try {
                if (started) timer.getClass().getMethod("stop").invoke(timer);
                timer.getClass().getMethod("destroy").invoke(timer);
                lines.add("Core timer: DESTROYED");
            } catch (Throwable error) {
                lines.add("Core timer destroy: " + cause(error).getClass().getName());
            }
        }
        String[] contracts = {
            "com.spotify.connectivity.NativeApplicationScope",
            "com.spotify.authentication.login5setupimpl.NativeLogin5SetupImpl",
            "com.spotify.authentication.oauthsetupimpl.NativeOAuthSetupImpl",
            "com.spotify.connectivity.auth.NativeSession",
            "com.spotify.connectivity.NativeAuthenticatedScope",
            "com.spotify.core.coreimpl.NativeApplicationScopeImpl",
            "com.spotify.core.corefullimpl.NativeFullAuthenticatedScopeImpl",
            "com.spotify.contentdelivery.mediaprocessingimpl.NativeMediaProcessorFactoryImpl",
            "com.spotify.contentdelivery.mediaprocessingimpl.NativeMediaProcessorImpl"
        };
        for (String contract : contracts) try {
            Class<?> type = Class.forName(contract, false, OrbitBootstrap.class.getClassLoader());
            for (Method method : type.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isNative(method.getModifiers())) lines.add(method.toGenericString());
            }
        } catch (Throwable error) {
            lines.add(contract + ": " + cause(error).getClass().getName());
        }
        lines.add("NO ACCOUNT / NO SESSION / NO MEDIA HANDLE REQUESTED");
        return lines;
    }
}
