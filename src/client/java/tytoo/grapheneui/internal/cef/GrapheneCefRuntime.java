package tytoo.grapheneui.internal.cef;

import io.github.trethore.jcefgithub.CefAppBuilder;
import io.github.trethore.jcefgithub.CefInitializationException;
import io.github.trethore.jcefgithub.UnsupportedPlatformException;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.util.Util;
import org.cef.CefApp;
import org.cef.CefClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tytoo.grapheneui.api.bridge.GrapheneBridge;
import tytoo.grapheneui.api.config.GrapheneContainerConfig;
import tytoo.grapheneui.api.config.GrapheneGlobalConfig;
import tytoo.grapheneui.api.config.GrapheneHttpConfig;
import tytoo.grapheneui.api.runtime.GrapheneHttpServer;
import tytoo.grapheneui.api.runtime.GrapheneRuntime;
import tytoo.grapheneui.api.surface.BrowserSurface;
import tytoo.grapheneui.internal.bridge.GrapheneBridgeOptions;
import tytoo.grapheneui.internal.bridge.GrapheneBridgeRuntime;
import tytoo.grapheneui.internal.browser.GrapheneBrowser;
import tytoo.grapheneui.internal.browser.surface.GrapheneBrowserSurfaceManager;
import tytoo.grapheneui.internal.cef.startup.GrapheneCefStartupProgressHandler;
import tytoo.grapheneui.internal.cef.startup.GrapheneNativeDownloadOverlay;
import tytoo.grapheneui.internal.cef.startup.GrapheneNativeDownloadState;
import tytoo.grapheneui.internal.devtools.GrapheneDevToolsResolver;
import tytoo.grapheneui.internal.event.GrapheneLoadEventBus;
import tytoo.grapheneui.internal.event.GrapheneTitleEventBus;
import tytoo.grapheneui.internal.http.GrapheneHttpServerRuntime;
import tytoo.grapheneui.internal.logging.GrapheneDebugLogger;
import tytoo.grapheneui.internal.mc.McClient;
import tytoo.grapheneui.internal.platform.GraphenePlatform;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

public final class GrapheneCefRuntime implements GrapheneRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(GrapheneCefRuntime.class);
    private static final GrapheneDebugLogger DEBUG_LOGGER = GrapheneDebugLogger.of(GrapheneCefRuntime.class);

    private static final long CEF_SHUTDOWN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long CEF_SHUTDOWN_POLL_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(25);
    private static final long BROWSER_CLOSE_TIMEOUT_MILLIS = 10_000L;
    private static final String FAILED_INITIALIZATION_MESSAGE = "Failed to initialize Graphene CEF runtime";
    private static final ExecutorService STARTUP_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "graphene-cef-startup");
        thread.setDaemon(true);
        return thread;
    });

    private final Object lock = new Object();
    private final GrapheneBrowserSurfaceManager surfaceManager;
    private final GrapheneDevToolsResolver devToolsResolver = new GrapheneDevToolsResolver();
    private final GrapheneLoadEventBus loadEventBus = new GrapheneLoadEventBus();
    private final GrapheneTitleEventBus titleEventBus = new GrapheneTitleEventBus();
    private final GrapheneBridgeRuntime bridgeRuntime;
    private final GrapheneCefBrowserShutdownTracker browserShutdownTracker = new GrapheneCefBrowserShutdownTracker();
    private boolean initialized;
    private boolean shutdownInProgress;
    private boolean shutdownLifecycleRegistered;
    private CefApp cefApp;
    private CefClient cefClient;
    private int remoteDebuggingPort = -1;
    private GrapheneHttpServerRuntime httpServer = GrapheneHttpServerRuntime.disabled();
    private CompletableFuture<Void> initializationFuture;

    public GrapheneCefRuntime(GrapheneBrowserSurfaceManager surfaceManager) {
        this(surfaceManager, GrapheneBridgeOptions.defaults());
    }

    public GrapheneCefRuntime(GrapheneBrowserSurfaceManager surfaceManager, GrapheneBridgeOptions bridgeOptions) {
        this.surfaceManager = Objects.requireNonNull(surfaceManager, "surfaceManager");
        this.bridgeRuntime = new GrapheneBridgeRuntime(Objects.requireNonNull(bridgeOptions, "bridgeOptions"));
    }

    private static void logStartupConfiguration(CefAppBuilder cefAppBuilder) {
        LOGGER.info(
                "Initializing CEF on platform linux={}, wayland={}, subprocess={}, args={}",
                GraphenePlatform.isLinux(),
                GraphenePlatform.isWaylandSession(),
                cefAppBuilder.getCefSettings().browser_subprocess_path,
                cefAppBuilder.getJcefArgs()
        );
    }

    private static void awaitCefTermination() {
        if (McClient.mc().isSameThread()) {
            DEBUG_LOGGER.debug("Skipping blocking CEF termination wait on the Minecraft main thread");
            return;
        }

        long deadlineNanos = System.nanoTime() + CEF_SHUTDOWN_TIMEOUT_NANOS;

        while (CefApp.getState() != CefApp.CefAppState.TERMINATED && System.nanoTime() < deadlineNanos) {
            LockSupport.parkNanos(CEF_SHUTDOWN_POLL_INTERVAL_NANOS);
            if (Thread.currentThread().isInterrupted()) {
                LOGGER.warn("Interrupted while waiting for CEF termination");
                return;
            }
        }

        if (CefApp.getState() != CefApp.CefAppState.TERMINATED) {
            LOGGER.warn("Timed out while waiting for CEF termination; process may remain alive");
        }
    }

    private static int browserIdentifier(GrapheneBrowser browser) {
        try {
            return browser.getIdentifier();
        } catch (RuntimeException ignored) {
            // Browser may already be disposed while diagnostics are logged.
            return -1;
        }
    }

    public void initialize(GrapheneGlobalConfig globalConfig, Map<String, GrapheneContainerConfig> containerConfigs) {
        try {
            initializeAsync(globalConfig, containerConfigs).join();
        } catch (CompletionException exception) {
            throw propagateInitializationFailure(exception.getCause());
        }
    }

    public CompletableFuture<Void> initializeAsync(
            GrapheneGlobalConfig globalConfig,
            Map<String, GrapheneContainerConfig> containerConfigs
    ) {
        GrapheneGlobalConfig validatedGlobalConfig = Objects.requireNonNull(globalConfig, "globalConfig");
        Map<String, GrapheneContainerConfig> validatedContainerConfigs = Map.copyOf(
                Objects.requireNonNull(containerConfigs, "containerConfigs")
        );
        synchronized (lock) {
            if (initialized) {
                return CompletableFuture.completedFuture(null);
            }

            if (initializationFuture != null) {
                return initializationFuture;
            }

            if (shutdownInProgress) {
                return CompletableFuture.failedFuture(new IllegalStateException("Graphene CEF runtime is shutting down"));
            }

            CompletableFuture<Void> startupFuture = CompletableFuture.runAsync(
                    () -> initializeInternal(validatedGlobalConfig, validatedContainerConfigs),
                    STARTUP_EXECUTOR
            );
            initializationFuture = startupFuture;
            startupFuture.whenComplete((ignored, throwable) -> {
                synchronized (lock) {
                    if (initializationFuture == startupFuture) {
                        initializationFuture = null;
                    }
                }

                if (throwable != null) {
                    LOGGER.error("Failed to initialize Graphene CEF runtime asynchronously", unwrapInitializationFailure(throwable));
                }
            });
            return startupFuture;
        }
    }

    public CefClient requireClient() {
        synchronized (lock) {
            if (!initialized || cefClient == null) {
                throw new IllegalStateException("Graphene is not initialized. Call GrapheneCore.register(...) first.");
            }

            return cefClient;
        }
    }

    public GrapheneLoadEventBus getLoadEventBus() {
        return loadEventBus;
    }

    public GrapheneTitleEventBus getTitleEventBus() {
        return titleEventBus;
    }

    public GrapheneBridge attachBridge(GrapheneBrowser browser) {
        synchronized (lock) {
            if (!initialized) {
                throw new IllegalStateException("Graphene is not initialized. Call GrapheneCore.register(...) first.");
            }

            GrapheneBridge bridge = bridgeRuntime.attach(browser);
            DEBUG_LOGGER.debug("Attached bridge for browser identifier={}", browserIdentifier(browser));
            return bridge;
        }
    }

    public void detachBridge(GrapheneBrowser browser) {
        synchronized (lock) {
            bridgeRuntime.detach(browser);
            DEBUG_LOGGER.debug("Detached bridge for browser identifier={}", browserIdentifier(browser));
        }
    }

    public void onNavigationRequested(GrapheneBrowser browser) {
        synchronized (lock) {
            if (!initialized) {
                return;
            }

            bridgeRuntime.onNavigationRequested(browser);
            DEBUG_LOGGER.debug("Bridge navigation requested for browser identifier={}", browserIdentifier(browser));
        }
    }

    public void ensureBootstrap(GrapheneBrowser browser) {
        synchronized (lock) {
            if (!initialized) {
                return;
            }

            bridgeRuntime.ensureBootstrap(browser);
            DEBUG_LOGGER.debug("Requested bridge bootstrap check for browser identifier={}", browserIdentifier(browser));
        }
    }

    @Override
    public int getRemoteDebuggingPort() {
        synchronized (lock) {
            return remoteDebuggingPort;
        }
    }

    @Override
    public CompletableFuture<URI> resolveDevToolsUri(BrowserSurface surface) {
        BrowserSurface validatedSurface = Objects.requireNonNull(surface, "surface");
        return devToolsResolver.resolveUri(getRemoteDebuggingPort(), validatedSurface.currentUrl());
    }

    @Override
    public CompletableFuture<URI> openDevTools(BrowserSurface surface) {
        return resolveDevToolsUri(surface).thenApply(devToolsUri -> {
            Util.getPlatform().openUri(devToolsUri);
            return devToolsUri;
        });
    }

    @Override
    public GrapheneHttpServer httpServer() {
        synchronized (lock) {
            return httpServer;
        }
    }

    @Override
    public boolean isInitialized() {
        synchronized (lock) {
            return initialized;
        }
    }

    public void shutdown() {
        shutdownInternal();
    }

    private boolean ensureCanInitialize() {
        if (initialized) {
            DEBUG_LOGGER.debug("Skipping CEF initialize because runtime is already initialized");
            return false;
        }

        if (shutdownInProgress) {
            throw new IllegalStateException("Graphene CEF runtime is shutting down");
        }

        return true;
    }

    private void initializeInternal(
            GrapheneGlobalConfig globalConfig,
            Map<String, GrapheneContainerConfig> containerConfigs
    ) {
        GrapheneNativeDownloadState downloadState = new GrapheneNativeDownloadState(GrapheneCefInstaller.currentPlatformIdentifier());
        GrapheneNativeDownloadOverlay downloadOverlay = new GrapheneNativeDownloadOverlay(downloadState);

        try {
            synchronized (lock) {
                if (!ensureCanInitialize()) {
                    return;
                }

                GrapheneHttpServerRuntime startedHttpServer = createHttpServerIfConfigured(containerConfigs);
                CefAppBuilder cefAppBuilder = createConfiguredBuilder(globalConfig, downloadState, downloadOverlay);
                buildCefApp(cefAppBuilder, startedHttpServer);
                initializeClient(cefAppBuilder, startedHttpServer);
                registerShutdownLifecycle();
                logInitializationState();
            }
        } finally {
            dismissNativeDownloadOverlay(downloadState, downloadOverlay);
        }
    }

    private CefAppBuilder createConfiguredBuilder(
            GrapheneGlobalConfig globalConfig,
            GrapheneNativeDownloadState downloadState,
            GrapheneNativeDownloadOverlay downloadOverlay
    ) {
        CefAppBuilder cefAppBuilder = GrapheneCefInstaller.createBuilder(globalConfig);
        cefAppBuilder.setProgressHandler(new GrapheneCefStartupProgressHandler(
                downloadState,
                () -> showNativeDownloadOverlay(downloadOverlay)
        ));
        logStartupConfiguration(cefAppBuilder);
        GrapheneCefAppHandler appHandler = new GrapheneCefAppHandler(globalConfig.fileSystemAccessMode());
        cefAppBuilder.setAppHandler(appHandler);
        return cefAppBuilder;
    }

    private void showNativeDownloadOverlay(GrapheneNativeDownloadOverlay downloadOverlay) {
        McClient.runOnMainThread(() -> {
            if (McClient.currentOverlay() == downloadOverlay) {
                return;
            }

            if (McClient.currentOverlay() != null) {
                return;
            }

            McClient.setOverlay(downloadOverlay);
        });
    }

    private void dismissNativeDownloadOverlay(
            GrapheneNativeDownloadState downloadState,
            GrapheneNativeDownloadOverlay downloadOverlay
    ) {
        downloadState.reset();
        McClient.runOnMainThread(() -> {
            if (McClient.currentOverlay() == downloadOverlay) {
                McClient.setOverlay(null);
            }
        });
    }

    private IllegalStateException propagateInitializationFailure(Throwable throwable) {
        Throwable cause = unwrapInitializationFailure(throwable);
        if (cause instanceof IllegalStateException illegalStateException) {
            return illegalStateException;
        }

        return new IllegalStateException(FAILED_INITIALIZATION_MESSAGE, cause);
    }

    private Throwable unwrapInitializationFailure(Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof CompletionException completionException && completionException.getCause() != null) {
            cause = completionException.getCause();
        }

        return cause == null ? new IllegalStateException(FAILED_INITIALIZATION_MESSAGE) : cause;
    }

    private void buildCefApp(CefAppBuilder cefAppBuilder, GrapheneHttpServerRuntime startedHttpServer) {
        try {
            cefApp = cefAppBuilder.build();
        } catch (InterruptedException exception) {
            startedHttpServer.close();
            Thread.currentThread().interrupt();
            throw new IllegalStateException(FAILED_INITIALIZATION_MESSAGE, exception);
        } catch (IOException | UnsupportedPlatformException | CefInitializationException exception) {
            startedHttpServer.close();
            throw new IllegalStateException(FAILED_INITIALIZATION_MESSAGE, exception);
        } catch (RuntimeException exception) {
            startedHttpServer.close();
            throw exception;
        }
    }

    private void initializeClient(CefAppBuilder cefAppBuilder, GrapheneHttpServerRuntime startedHttpServer) {
        try {
            cefClient = cefApp.createClient();
            GrapheneCefClientConfig.configure(
                    cefClient,
                    loadEventBus,
                    titleEventBus,
                    bridgeRuntime,
                    browserShutdownTracker
            );
            int configuredRemoteDebugPort = cefAppBuilder.getCefSettings().remote_debugging_port;
            remoteDebuggingPort = configuredRemoteDebugPort > 0 ? configuredRemoteDebugPort : -1;
            httpServer = startedHttpServer;
            initialized = true;
        } catch (RuntimeException exception) {
            startedHttpServer.close();
            resetInitializationStateAfterFailure();
            throw exception;
        }
    }

    private void resetInitializationStateAfterFailure() {
        if (cefClient != null) {
            cefClient.dispose();
        }

        cefApp.dispose();
        awaitCefTermination();

        cefClient = null;
        cefApp = null;
        httpServer = GrapheneHttpServerRuntime.disabled();
        remoteDebuggingPort = -1;
        initialized = false;
    }

    private void logInitializationState() {
        if (remoteDebuggingPort > 0) {
            LOGGER.info("CEF runtime initialized on debug port {}", remoteDebuggingPort);
        } else {
            LOGGER.info("CEF runtime initialized with remote debugging disabled");
        }

        if (httpServer.isRunning()) {
            LOGGER.info("Graphene HTTP server initialized at {}", httpServer.baseUrl());
        }
    }

    private GrapheneHttpServerRuntime createHttpServerIfConfigured(Map<String, GrapheneContainerConfig> containerConfigs) {
        LinkedHashMap<String, GrapheneHttpConfig> httpConfigs = new LinkedHashMap<>();
        for (Map.Entry<String, GrapheneContainerConfig> containerConfigEntry : containerConfigs.entrySet()) {
            containerConfigEntry.getValue().http().ifPresent(httpConfig -> httpConfigs.put(containerConfigEntry.getKey(), httpConfig));
        }

        if (httpConfigs.isEmpty()) {
            return GrapheneHttpServerRuntime.disabled();
        }

        return createHttpServer(httpConfigs);
    }

    private GrapheneHttpServerRuntime createHttpServer(Map<String, GrapheneHttpConfig> httpConfigs) {
        GrapheneHttpServerRuntime startedHttpServer = GrapheneHttpServerRuntime.start(httpConfigs);
        DEBUG_LOGGER.debug(
                "Graphene HTTP server started: host={}, port={}, mountCount={}",
                startedHttpServer.host(),
                startedHttpServer.port(),
                httpConfigs.size()
        );
        return startedHttpServer;
    }

    private void registerShutdownLifecycle() {
        if (shutdownLifecycleRegistered) {
            return;
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register(ignoredClient -> shutdown());
        shutdownLifecycleRegistered = true;
        DEBUG_LOGGER.debug("Registered CEF client lifecycle shutdown hook");
    }

    private void shutdownInternal() {
        ShutdownResources resources = beginShutdown();
        if (resources == null) {
            return;
        }

        try {
            closeSurfaces();
            runShutdownStep(bridgeRuntime::shutdown, "Failed to shut down Graphene bridge runtime");
            runShutdownStep(loadEventBus::clear, "Failed to clear Graphene load event listeners");
            runShutdownStep(titleEventBus::clear, "Failed to clear Graphene title event listeners");
            disposeNativeResources(resources);
            LOGGER.info("CEF runtime disposed (client lifecycle)");
        } finally {
            synchronized (lock) {
                shutdownInProgress = false;
            }
        }
    }

    private ShutdownResources beginShutdown() {
        synchronized (lock) {
            if (shutdownInProgress) {
                DEBUG_LOGGER.debug("Skipping CEF shutdown because another shutdown is already in progress");
                return null;
            }

            if (!initialized) {
                DEBUG_LOGGER.debug("Skipping CEF shutdown because runtime is not initialized");
                return null;
            }

            shutdownInProgress = true;

            GrapheneHttpServerRuntime activeHttpServer = httpServer;
            ShutdownResources resources = new ShutdownResources(
                    cefClient,
                    cefApp,
                    activeHttpServer::close,
                    activeHttpServer.isRunning()
            );
            cefClient = null;
            cefApp = null;
            httpServer = GrapheneHttpServerRuntime.disabled();
            remoteDebuggingPort = -1;
            initialized = false;

            DEBUG_LOGGER.debug("Starting CEF shutdown closeClient={} closeApp={} httpRunning={}",
                    resources.cefClient() != null,
                    resources.cefApp() != null,
                    resources.httpServerRunning()
            );

            return resources;
        }
    }

    private void disposeNativeResources(ShutdownResources resources) {
        CefClient activeClient = resources.cefClient();
        if (activeClient != null) {
            runShutdownStep(activeClient::dispose, "Failed to dispose CEF client");
            awaitBrowserCloseCallbacks();
        }

        CefApp activeApp = resources.cefApp();
        if (activeApp != null) {
            runShutdownStep(() -> disposeCefApp(activeApp), "Failed to dispose CEF app");
        }

        runShutdownStep(resources.closeHttpServer(), "Failed to stop Graphene HTTP server");
    }

    private void closeSurfaces() {
        runShutdownStep(surfaceManager::closeAll, "Failed to close browser surfaces during CEF shutdown");
    }

    private void disposeCefApp(CefApp activeApp) {
        activeApp.dispose();
        awaitCefTermination();
    }

    private void awaitBrowserCloseCallbacks() {
        if (McClient.mc().isSameThread()) {
            DEBUG_LOGGER.debug(
                    "Skipping blocking CEF browser close wait on the Minecraft main thread; openBrowserCount={}",
                    browserShutdownTracker.openBrowserCount()
            );
            return;
        }

        try {
            browserShutdownTracker.allBrowsersClosedFuture().get(BROWSER_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while waiting for CEF browsers to close");
        } catch (ExecutionException | TimeoutException exception) {
            LOGGER.warn(
                    "Timed out while waiting for {} CEF browser(s) to close",
                    browserShutdownTracker.openBrowserCount(),
                    exception
            );
        }
    }

    private void runShutdownStep(Runnable action, String failureMessage) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            LOGGER.warn(failureMessage, exception);
        }
    }

    private record ShutdownResources(
            CefClient cefClient,
            CefApp cefApp,
            Runnable closeHttpServer,
            boolean httpServerRunning
    ) {
    }
}
