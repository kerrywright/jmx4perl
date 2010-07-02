package org.jmx4perl.jsr160;

import org.apache.commons.pool.KeyedPoolableObjectFactory;
import org.apache.commons.pool.impl.GenericKeyedObjectPool;
import org.apache.commons.pool.impl.GenericObjectPool;
import org.jmx4perl.Config;
import static org.jmx4perl.Config.*;
import org.jmx4perl.JmxRequest;
import org.jmx4perl.backend.RequestDispatcher;
import org.jmx4perl.config.Restrictor;
import org.jmx4perl.converter.StringToObjectConverter;
import org.jmx4perl.converter.json.ObjectToJsonConverter;
import org.jmx4perl.handler.JsonRequestHandler;
import org.jmx4perl.handler.RequestHandlerManager;

import javax.management.*;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.naming.Context;
import javax.security.auth.Subject;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Dispatcher for calling JSR-160 connectors
 *
 * @author roland, kwright
 * @since Nov 11, 2009
 */
public class Jsr160RequestDispatcher implements RequestDispatcher {

    private RequestHandlerManager requestHandlerManager;
    GenericKeyedObjectPool connectionPool;
    private ConcurrentHashMap<JmxRequest.TargetConfig, Object> poolsCreated = new ConcurrentHashMap<JmxRequest.TargetConfig, Object>();

    public Jsr160RequestDispatcher(ObjectToJsonConverter objectToJsonConverter,
                                   StringToObjectConverter stringToObjectConverter,
                                   Restrictor restrictor,
                                   Map<Config, String> pConfig) {
        this(objectToJsonConverter, stringToObjectConverter, restrictor, new Jsr160ConnectionFactory(), pConfig);
    }
    
    public Jsr160RequestDispatcher(ObjectToJsonConverter objectToJsonConverter,
                                   StringToObjectConverter stringToObjectConverter,
                                   Restrictor restrictor,
                                   KeyedPoolableObjectFactory connectorFactory,
                                   Map<Config, String> pConfig) {
        requestHandlerManager = new RequestHandlerManager(
                objectToJsonConverter, stringToObjectConverter, restrictor);
        connectionPool = new GenericKeyedObjectPool(connectorFactory);
        connectionPool.setMaxActive(JSR160_POOL_MAX_SIZE.getIntValue(pConfig));
        connectionPool.setMaxIdle(JSR160_POOL_MAX_IDLE.getIntValue(pConfig));
        connectionPool.setMinEvictableIdleTimeMillis(JSR160_POOL_IDLE_TIME.getIntValue(pConfig)); // 30s
        connectionPool.setTestOnBorrow(true);
        connectionPool.setTimeBetweenEvictionRunsMillis(JSR160_POOL_IDLE_TIME.getIntValue(pConfig) / 2); // 15s
        connectionPool.setMaxWait(JSR160_POOL_MAX_WAIT_TIME.getIntValue(pConfig)); // 10s
        connectionPool.setWhenExhaustedAction(GenericObjectPool.WHEN_EXHAUSTED_BLOCK);
    }

    /**
     * Call a remote connector based on the connection information contained in
     * the request.
     *
     * @param pJmxReq the request to dispatch
     * @return result object
     * @throws InstanceNotFoundException
     * @throws AttributeNotFoundException
     * @throws ReflectionException
     * @throws MBeanException
     * @throws IOException
     */
    public Object dispatchRequest(JmxRequest pJmxReq)
            throws InstanceNotFoundException, AttributeNotFoundException, ReflectionException, MBeanException, IOException {

        JsonRequestHandler handler = requestHandlerManager.getRequestHandler(pJmxReq.getType());
        JMXConnector connector = getConnector(pJmxReq);
        try {
            MBeanServerConnection connection = connector.getMBeanServerConnection();
            if (handler.handleAllServersAtOnce()) {
                // There is no way to get remotely all MBeanServers ...
                return handler.handleRequest(new HashSet<MBeanServerConnection>(Arrays.asList(connection)),pJmxReq);
            } else {
                return handler.handleRequest(connection,pJmxReq);
            }
        } finally {
            releaseConnector(connector);
        }
    }

    /**
     * Get a JMXConnector from the pool
     * @param pJmxReq JMX Request
     * @return A JMXConnector from the pool
     * @throws IOException
     */
    JMXConnector getConnector(JmxRequest pJmxReq) throws IOException {
        try {
            if (poolsCreated.putIfAbsent(pJmxReq.getTargetConfig(), new Object()) == null) {
                connectionPool.addObject(pJmxReq.getTargetConfig());
            }
            return (JMXConnector) connectionPool.borrowObject(pJmxReq.getTargetConfig());
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            e.printStackTrace();
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Release the connector.
     * If it is one from the connection pool, return it. Otherwise close the connection
     *
     * @param pConnector the connection to return
     * @throws IOException
     */
    void releaseConnector(JMXConnector pConnector) throws IOException {
        if (!JMXConnectorConfigDecorator.class.isInstance(pConnector)) {
            throw new IllegalArgumentException("Attempted to return a connection that was not created by this dispatcher");
        }
        try {
            connectionPool.returnObject(((JMXConnectorConfigDecorator)pConnector).getTargetConfig(), pConnector);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /**
     * @param pJmxRequest request to check
     * @return true if this request has a targetConfig
     */
    public boolean canHandle(JmxRequest pJmxRequest) {
        return pJmxRequest.getTargetConfig() != null;
    }

    /**
     * KeyedPoolableObjectFactory implementation for managing the lifecycle of Jsr160 remote JMX connections.
     */
    private static class Jsr160ConnectionFactory implements KeyedPoolableObjectFactory {

        /**
         * Initialize the environment parameters to pass in requests. Uses the "user" and "password" default parameters
         * as the javax.naming.Context credentials.
         *
         * @param pEnv the default environment properties
         * @return a new Map of properties suitable for JSR160 usage
         */
        private Map<String,Object> prepareEnv(Map<String, Object> pEnv) {
            if (pEnv == null || pEnv.size() == 0) {
                return pEnv;
            }
            Map<String,Object> ret = new HashMap<String, Object>(pEnv);
            String user = (String) ret.remove("user");
            String password  = (String) ret.remove("password");
            if (user != null && password != null) {
                ret.put(Context.SECURITY_PRINCIPAL, user);
                ret.put(Context.SECURITY_CREDENTIALS, password);
                ret.put("jmx.remote.credentials",new String[] { user, password });
            }
            return ret;
        }

        /**
         * Construct a new RMIConnector to be used in the pool
         *
         * @param key the TargetConfig object that will be used as the key into the Keyed Pool
         * @return a new, connected, JMXConnector
         * @throws Exception
         */
        public Object makeObject(Object key) throws Exception {
            JmxRequest.TargetConfig targetConfig = (JmxRequest.TargetConfig) key;
            if (targetConfig == null) {
                throw new IllegalArgumentException("No proxy configuration in request " + targetConfig);
            }
            String urlS = targetConfig.getUrl();
            JMXServiceURL url = new JMXServiceURL(urlS);
            Map<String,Object> env = prepareEnv(targetConfig.getEnv());
            JMXConnector ret = JMXConnectorFactory.newJMXConnector(url,env);
            ret.connect();
            return new JMXConnectorConfigDecorator(ret, targetConfig);
        }

        /**
         * Closes the JMXConnector before it is removed from the pool
         *
         * @param key the key under which this object lives in the pool
         * @param obj the JMXConnector to be closed
         * @throws Exception
         */
        public void destroyObject(Object key, Object obj) throws Exception {
            ((JMXConnector)obj).close();
        }

        /**
         * Tests a JMXConnector to ensure that it is still connected to the remote server
         *
         * @param key the key this JMXConnector lives under in the pool
         * @param obj the JMXConnector
         * @return true if the remote MBeanServer is responding
         */
        public boolean validateObject(Object key, Object obj) {
            try {
                ((JMXConnector)obj).getMBeanServerConnection().getDefaultDomain();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        public void activateObject(Object key, Object obj) throws Exception {
            // do nothing
        }

        public void passivateObject(Object key, Object obj) throws Exception {
            // do nothing
        }
    }

    /**
     * Wrapper class for a standard JMXConnector to allow the reverse lookup of a connector's TargetConfig. Needed when
     * releaseConnector is called
     */
    static class JMXConnectorConfigDecorator implements JMXConnector {
        private final JMXConnector connector;
        private final JmxRequest.TargetConfig config;

        private JMXConnectorConfigDecorator(JMXConnector connector, JmxRequest.TargetConfig config) {
            this.connector = connector;
            this.config = config;
        }

        public void connect() throws IOException {
            connector.connect();
        }

        public void connect(Map<String, ?> env) throws IOException {
            connector.connect(env);
        }

        public MBeanServerConnection getMBeanServerConnection() throws IOException {
            return connector.getMBeanServerConnection();
        }

        public MBeanServerConnection getMBeanServerConnection(Subject delegationSubject) throws IOException {
            return connector.getMBeanServerConnection(delegationSubject);
        }

        public void close() throws IOException {
            connector.close();
        }

        public void addConnectionNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) {
            connector.addConnectionNotificationListener(listener, filter, handback);
        }

        public void removeConnectionNotificationListener(NotificationListener listener) throws ListenerNotFoundException {
            connector.removeConnectionNotificationListener(listener);
        }

        public void removeConnectionNotificationListener(NotificationListener l, NotificationFilter f, Object handback) throws ListenerNotFoundException {
            connector.removeConnectionNotificationListener(l, f, handback);
        }

        public String getConnectionId() throws IOException {
            return connector.getConnectionId();
        }

        public JmxRequest.TargetConfig getTargetConfig() {
            return this.config;
        }
    }
}
