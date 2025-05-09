package org.red5.net.websocket.server;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.DeploymentException;
import javax.websocket.Encoder;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;
import javax.websocket.server.ServerEndpointConfig.Configurator;

import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.websocket.WsSession;
import org.apache.tomcat.websocket.WsWebSocketContainer;
import org.apache.tomcat.websocket.pojo.PojoMethodMapping;
import org.apache.tomcat.websocket.server.Constants;
import org.apache.tomcat.websocket.server.UriTemplate;
import org.apache.tomcat.websocket.server.WsServerContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides a per class loader (i.e. per web application) instance of a ServerContainer. Web application wide defaults may be configured by setting the
 * following servlet context initialization parameters to the desired values.
 * <ul>
 * <li>{@link Constants#BINARY_BUFFER_SIZE_SERVLET_CONTEXT_INIT_PARAM}</li>
 * <li>{@link Constants#TEXT_BUFFER_SIZE_SERVLET_CONTEXT_INIT_PARAM}</li>
 * </ul>
 */
public class DefaultWsServerContainer extends WsWebSocketContainer implements ServerContainer {

    private final Logger log = LoggerFactory.getLogger(DefaultWsServerContainer.class);

    private static final StringManager sm = StringManager.getManager(WsServerContainer.class);

    private static final CloseReason AUTHENTICATED_HTTP_SESSION_CLOSED = new CloseReason(CloseCodes.VIOLATED_POLICY, "Authenticated HTTP session that has ended");

    private final ServletContext servletContext;

    private final ConcurrentMap<String, ServerEndpointConfig> configExactMatchMap = new ConcurrentHashMap<>();

    private final ConcurrentMap<Integer, SortedSet<TemplatePathMatch>> configTemplateMatchMap = new ConcurrentHashMap<>();

    private volatile boolean enforceNoAddAfterHandshake = org.apache.tomcat.websocket.Constants.STRICT_SPEC_COMPLIANCE;

    private volatile boolean addAllowed = true;

    private final ConcurrentMap<String, Set<WsSession>> authenticatedSessions = new ConcurrentHashMap<>();

    private volatile boolean endpointsRegistered;

    private volatile CopyOnWriteArraySet<String> registeredEndpointPaths = new CopyOnWriteArraySet<>();

    public DefaultWsServerContainer(ServletContext servletContext) {
        log.debug("ctor - context: {}", servletContext);
        this.servletContext = servletContext;
        setInstanceManager((InstanceManager) servletContext.getAttribute(InstanceManager.class.getName()));
        // Configure servlet context wide defaults
        String value = servletContext.getInitParameter(Constants.BINARY_BUFFER_SIZE_SERVLET_CONTEXT_INIT_PARAM);
        if (value != null) {
            setDefaultMaxBinaryMessageBufferSize(Integer.parseInt(value));
        }
        value = servletContext.getInitParameter(Constants.TEXT_BUFFER_SIZE_SERVLET_CONTEXT_INIT_PARAM);
        if (value != null) {
            setDefaultMaxTextMessageBufferSize(Integer.parseInt(value));
        }
        value = servletContext.getInitParameter(Constants.ENFORCE_NO_ADD_AFTER_HANDSHAKE_CONTEXT_INIT_PARAM);
        if (value != null) {
            setEnforceNoAddAfterHandshake(Boolean.parseBoolean(value));
        }
        /* get the websocket filter and coordinate the async setting
        <filter-name>WebSocketFilter</filter-name>
        <filter-class>org.red5.net.websocket.server.WsFilter</filter-class>
        <async-supported>false</async-supported>
         */
    }

    /**
     * Published the provided endpoint implementation at the specified path with the specified configuration. {@link #org.apache.tomcat.websocket.server.WsServerContainer(ServletContext)}
     * must be called before calling this method.
     *
     * @param sec
     *            The configuration to use when creating endpoint instances
     * @throws DeploymentException
     *             if the endpoint cannot be published as requested
     */
    @Override
    public void addEndpoint(ServerEndpointConfig sec) throws DeploymentException {
        log.debug("addEndpoint: {}", sec);
        if (enforceNoAddAfterHandshake && !addAllowed) {
            throw new DeploymentException(sm.getString("serverContainer.addNotAllowed"));
        }
        if (servletContext == null) {
            throw new DeploymentException(sm.getString("serverContainer.servletContextMissing"));
        }
        String path = sec.getPath();
        // Add method mapping to user properties
        PojoMethodMapping methodMapping = new PojoMethodMapping(sec.getEndpointClass(), sec.getDecoders(), path, null); // null for instance manager
        if (methodMapping.getOnClose() != null || methodMapping.getOnOpen() != null || methodMapping.getOnError() != null || methodMapping.hasMessageHandlers()) {
            sec.getUserProperties().put(org.apache.tomcat.websocket.pojo.Constants.POJO_METHOD_MAPPING_KEY, methodMapping);
        }
        UriTemplate uriTemplate = new UriTemplate(path);
        if (uriTemplate.hasParameters()) {
            Integer key = Integer.valueOf(uriTemplate.getSegmentCount());
            SortedSet<TemplatePathMatch> templateMatches = configTemplateMatchMap.get(key);
            if (templateMatches == null) {
                // Ensure that if concurrent threads execute this block they both end up using the same TreeSet instance
                templateMatches = new TreeSet<>(TemplatePathMatchComparator.getInstance());
                configTemplateMatchMap.putIfAbsent(key, templateMatches);
                templateMatches = configTemplateMatchMap.get(key);
            }
            if (!templateMatches.add(new TemplatePathMatch(sec, uriTemplate))) {
                // Duplicate uriTemplate;
                throw new DeploymentException(sm.getString("serverContainer.duplicatePaths", path, sec.getEndpointClass(), sec.getEndpointClass()));
            }
        } else {
            // Exact match
            ServerEndpointConfig old = configExactMatchMap.put(path, sec);
            if (old != null) {
                // Duplicate path mappings
                throw new DeploymentException(sm.getString("serverContainer.duplicatePaths", path, old.getEndpointClass(), sec.getEndpointClass()));
            }
        }
        // save a reference so we can kill any zombies
        registeredEndpointPaths.add(path);
        endpointsRegistered = true;
    }

    /**
     * Provides the equivalent of {@link #addEndpoint(ServerEndpointConfig)} for publishing plain old java objects (POJOs) that have been annotated as WebSocket endpoints.
     *
     * @param pojo
     *            The annotated POJO
     */
    @Override
    public void addEndpoint(Class<?> pojo) throws DeploymentException {
        log.debug("addEndpoint: {}", pojo);
        ServerEndpoint annotation = pojo.getAnnotation(ServerEndpoint.class);
        if (annotation == null) {
            throw new DeploymentException(sm.getString("serverContainer.missingAnnotation", pojo.getName()));
        }
        String path = annotation.value();
        // Validate encoders
        validateEncoders(annotation.encoders());
        // ServerEndpointConfig
        ServerEndpointConfig sec;
        Class<? extends Configurator> configuratorClazz = annotation.configurator();
        Configurator configurator = null;
        if (!configuratorClazz.equals(Configurator.class)) {
            try {
                configurator = annotation.configurator().getConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new DeploymentException(sm.getString("serverContainer.configuratorFail", annotation.configurator().getName(), pojo.getClass().getName()), e);
            }
        }
        sec = ServerEndpointConfig.Builder.create(pojo, path).decoders(Arrays.asList(annotation.decoders())).encoders(Arrays.asList(annotation.encoders())).subprotocols(Arrays.asList(annotation.subprotocols())).configurator(configurator).build();
        addEndpoint(sec);
    }

    boolean areEndpointsRegistered() {
        return endpointsRegistered;
    }

    /**
     * Until the WebSocket specification provides such a mechanism, this Tomcat proprietary method is provided to enable applications to programmatically
     * determine whether or not to upgrade an individual request to WebSocket.
     * <p>
     * Note: This method is not used by Tomcat but is used directly by third-party code and must not be removed.
     *
     * @param request
     *            The request object to be upgraded
     * @param response
     *            The response object to be populated with the result of the upgrade
     * @param sec
     *            The server endpoint to use to process the upgrade request
     * @param pathParams
     *            The path parameters associated with the upgrade request
     *
     * @throws ServletException
     *             If a configuration error prevents the upgrade from taking place
     * @throws IOException
     *             If an I/O error occurs during the upgrade process
     */
    public void doUpgrade(HttpServletRequest request, HttpServletResponse response, ServerEndpointConfig sec, Map<String, String> pathParams) throws ServletException, IOException {
        log.debug("doUpgrade");
        UpgradeUtil.doUpgrade(this, request, response, sec, pathParams);
    }

    public WsMappingResult findMapping(String path) {
        log.debug("findMapping: {}", path);
        // Prevent registering additional endpoints once the first attempt has been made to use one
        if (addAllowed) {
            addAllowed = false;
        }
        // Check an exact match. Simple case as there are no templates.
        ServerEndpointConfig sec = configExactMatchMap.get(path);
        log.debug("configExactMatchMap: {}", configExactMatchMap);
        if (sec != null) {
            return new WsMappingResult(sec, Collections.<String, String> emptyMap());
        }
        // No exact match. Need to look for template matches.
        UriTemplate pathUriTemplate = null;
        try {
            pathUriTemplate = new UriTemplate(path);
        } catch (DeploymentException e) {
            // Path is not valid so can't be matched to a WebSocketEndpoint
            return null;
        }
        // Number of segments has to match
        Integer key = Integer.valueOf(pathUriTemplate.getSegmentCount());
        SortedSet<TemplatePathMatch> templateMatches = configTemplateMatchMap.get(key);
        log.debug("configTemplateMatchMap - key: {} {}", key, configTemplateMatchMap);
        if (templateMatches == null) {
            // No templates with an equal number of segments so there will be no matches
            return null;
        }
        // List is in alphabetical order of normalized templates.
        // Correct match is the first one that matches.
        Map<String, String> pathParams = null;
        for (TemplatePathMatch templateMatch : templateMatches) {
            pathParams = templateMatch.getUriTemplate().match(pathUriTemplate);
            if (pathParams != null) {
                sec = templateMatch.getConfig();
                break;
            }
        }
        if (sec == null) {
            // No match
            return null;
        }
        return new WsMappingResult(sec, pathParams);
    }

    public boolean isEnforceNoAddAfterHandshake() {
        return enforceNoAddAfterHandshake;
    }

    public void setEnforceNoAddAfterHandshake(boolean enforceNoAddAfterHandshake) {
        this.enforceNoAddAfterHandshake = enforceNoAddAfterHandshake;
    }

    public Set<String> getRegisteredEndpointPaths() {
        return Collections.unmodifiableSet(registeredEndpointPaths);
    }

    @Override
    public void backgroundProcess() {
        // some comments say 1s others say 10s
        //log.debug("backgroundProcess - period: {}", getProcessPeriod());
        /*
        This method gets called once a second (this is super class content)
        backgroundProcessCount ++;
        if (backgroundProcessCount >= processPeriod) {
            backgroundProcessCount = 0;
            for (WsSession wsSession : sessions.keySet()) {
                wsSession.checkExpiration();
            }
        }
        */
        super.backgroundProcess();
    }

    /**
     * {@inheritDoc}
     *
     * Overridden to make it visible to other classes in this package.
     */
    @Override
    protected void registerSession(Object endpoint, WsSession wsSession) {
        // Server side uses the endpoint path as the key
        // Client side uses the client endpoint instance
        super.registerSession(endpoint, wsSession);
        if (wsSession.isOpen() && wsSession.getUserPrincipal() != null && wsSession.getHttpSessionId() != null) {
            registerAuthenticatedSession(wsSession, wsSession.getHttpSessionId());
            log.debug("registerSession - registerAuthenticatedSession: {}", wsSession.getId());
        }
        log.debug("registerSession: {} endpoint: {}", wsSession.getId(), endpoint);
    }

    /**
     * {@inheritDoc}
     *
     * Overridden to make it visible to other classes in this package.
     */
    @Override
    protected void unregisterSession(Object endpoint, WsSession wsSession) {
        if (wsSession.getHttpSessionId() != null) {
            unregisterAuthenticatedSession(wsSession, wsSession.getHttpSessionId());
            log.debug("unregisterSession - unregisterAuthenticatedSession: {}", wsSession.getId());
        }
        super.unregisterSession(endpoint, wsSession);
        log.debug("unregisterSession: {} endpoint: {}", wsSession.getId(), endpoint);
    }

    private void registerAuthenticatedSession(WsSession wsSession, String httpSessionId) {
        Set<WsSession> wsSessions = authenticatedSessions.get(httpSessionId);
        if (wsSessions == null) {
            wsSessions = Collections.newSetFromMap(new ConcurrentHashMap<WsSession, Boolean>());
            authenticatedSessions.putIfAbsent(httpSessionId, wsSessions);
            wsSessions = authenticatedSessions.get(httpSessionId);
        }
        wsSessions.add(wsSession);
    }

    private void unregisterAuthenticatedSession(WsSession wsSession, String httpSessionId) {
        Set<WsSession> wsSessions = authenticatedSessions.get(httpSessionId);
        // wsSessions will be null if the HTTP session has ended
        if (wsSessions != null) {
            wsSessions.remove(wsSession);
        }
    }

    public void closeAuthenticatedSession(String httpSessionId) {
        Set<WsSession> wsSessions = authenticatedSessions.remove(httpSessionId);
        if (wsSessions != null && !wsSessions.isEmpty()) {
            for (WsSession wsSession : wsSessions) {
                try {
                    wsSession.close(AUTHENTICATED_HTTP_SESSION_CLOSED);
                } catch (IOException e) {
                    // Any IOExceptions during close will have been caught and the onError method called.
                }
            }
        }
    }

    private static void validateEncoders(Class<? extends Encoder>[] encoders) throws DeploymentException {
        for (Class<? extends Encoder> encoder : encoders) {
            // Need to instantiate decoder to ensure it is valid and that deployment can be failed if it is not
            @SuppressWarnings("unused")
            Encoder instance;
            try {
                encoder.getConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new DeploymentException(sm.getString("serverContainer.encoderFail", encoder.getName()), e);
            }
        }
    }

    private static class TemplatePathMatch {

        private final ServerEndpointConfig config;

        private final UriTemplate uriTemplate;

        public TemplatePathMatch(ServerEndpointConfig config, UriTemplate uriTemplate) {
            this.config = config;
            this.uriTemplate = uriTemplate;
        }

        public ServerEndpointConfig getConfig() {
            return config;
        }

        public UriTemplate getUriTemplate() {
            return uriTemplate;
        }
    }

    /**
     * This Comparator implementation is thread-safe so only create a single instance.
     */
    private static class TemplatePathMatchComparator implements Comparator<TemplatePathMatch> {

        private static final TemplatePathMatchComparator INSTANCE = new TemplatePathMatchComparator();

        public static TemplatePathMatchComparator getInstance() {
            return INSTANCE;
        }

        private TemplatePathMatchComparator() {
            // Hide default constructor
        }

        @Override
        public int compare(TemplatePathMatch tpm1, TemplatePathMatch tpm2) {
            return tpm1.getUriTemplate().getNormalizedPath().compareTo(tpm2.getUriTemplate().getNormalizedPath());
        }
    }

}
