package org.jmx4perl.jsr160;

import org.apache.commons.pool.KeyedPoolableObjectFactory;
import org.jmx4perl.config.ConfigProperty;
import static org.jmx4perl.config.ConfigProperty.*;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.*;

import static org.junit.Assert.*;

import org.jmx4perl.JmxRequest;
import org.junit.Test;

import javax.management.remote.JMXConnector;

import static org.easymock.classextension.EasyMock.*;

/**
 * AbeBooks Sample File
 */
public class Jsr160RequestDispatcherTest {

    @Test
    public void testOverrideConfig() {
        Map<ConfigProperty, String> pConfig = new HashMap<ConfigProperty, String>();
        pConfig.put(JSR160_POOL_IDLE_TIME, "99");
        pConfig.put(JSR160_POOL_MAX_IDLE, "98");
        pConfig.put(JSR160_POOL_MAX_SIZE, "97");
        Jsr160RequestDispatcher dispatcher = new Jsr160RequestDispatcher(null, null, null, pConfig);

        assertEquals(49, dispatcher.connectionPool.getTimeBetweenEvictionRunsMillis());
        assertEquals(99, dispatcher.connectionPool.getMinEvictableIdleTimeMillis());
        assertEquals(97, dispatcher.connectionPool.getMaxActive());
        assertEquals(98, dispatcher.connectionPool.getMaxIdle());
        assertEquals(0, dispatcher.connectionPool.getMinIdle());
    }

    @Test
    public void testCreateFirstConnection() throws Exception {
        JmxRequest.TargetConfig ConfigProperty = createNiceMock(JmxRequest.TargetConfig.class);
        JmxRequest request = createNiceMock(JmxRequest.class);
        KeyedPoolableObjectFactory factory = createMock(KeyedPoolableObjectFactory.class);
        JMXConnector connector = createNiceMock(JMXConnector.class);

        expect(request.getTargetConfig()).andReturn(ConfigProperty).atLeastOnce();
        expect(factory.makeObject(ConfigProperty)).andReturn(connector);
        factory.passivateObject(ConfigProperty, connector);
        factory.activateObject(ConfigProperty, connector);
        expect(factory.validateObject(ConfigProperty, connector)).andReturn(true);

        Jsr160RequestDispatcher dispatcher = new Jsr160RequestDispatcher(null, null, null, factory, new HashMap<ConfigProperty, String>());
        replay(ConfigProperty, request, factory, connector);

        JMXConnector con = dispatcher.getConnector(request);
        assertNotNull(con);
        verify(ConfigProperty, request, factory, connector);
    }

    @Test
    public void testCreateMultipleConnections() throws Exception {
        JmxRequest.TargetConfig ConfigProperty = createNiceMock(JmxRequest.TargetConfig.class);
        JmxRequest request = createNiceMock(JmxRequest.class);
        KeyedPoolableObjectFactory factory = createMock(KeyedPoolableObjectFactory.class);
        JMXConnector connector = createNiceMock(JMXConnector.class);
        JMXConnector connector2 = createNiceMock(JMXConnector.class);

        expect(request.getTargetConfig()).andReturn(ConfigProperty).atLeastOnce();

        expect(factory.makeObject(ConfigProperty)).andReturn(connector);
        factory.passivateObject(ConfigProperty, connector);
        factory.activateObject(ConfigProperty, connector);
        expect(factory.validateObject(ConfigProperty, connector)).andReturn(true);

        expect(factory.makeObject(ConfigProperty)).andReturn(connector2);
        factory.activateObject(ConfigProperty, connector2);
        expect(factory.validateObject(ConfigProperty, connector2)).andReturn(true);

        Jsr160RequestDispatcher dispatcher = new Jsr160RequestDispatcher(null, null, null, factory, new HashMap<ConfigProperty, String>());
        replay(ConfigProperty, request, factory, connector, connector2);

        JMXConnector con = dispatcher.getConnector(request);
        JMXConnector con2 = dispatcher.getConnector(request);
        assertNotNull(con);
        assertNotNull(con2);
        assertFalse(con == con2);
        verify(ConfigProperty, request, factory, connector, connector2);
    }

    @Test
    public void testConnectionReturn() throws Exception {
        JmxRequest.TargetConfig ConfigProperty = createNiceMock(JmxRequest.TargetConfig.class);
        JmxRequest request = createNiceMock(JmxRequest.class);
        KeyedPoolableObjectFactory factory = createMock(KeyedPoolableObjectFactory.class);
        Jsr160RequestDispatcher.JMXConnectorConfigDecorator connector = createNiceMock(Jsr160RequestDispatcher.JMXConnectorConfigDecorator.class);

        expect(request.getTargetConfig()).andReturn(ConfigProperty).atLeastOnce();
        expect(factory.makeObject(ConfigProperty)).andReturn(connector);
        factory.passivateObject(ConfigProperty, connector);
        factory.activateObject(ConfigProperty, connector);
        expect(factory.validateObject(ConfigProperty, connector)).andReturn(true);
        expect(connector.getTargetConfig()).andReturn(ConfigProperty).atLeastOnce();
        
        factory.passivateObject(ConfigProperty, connector);
        Jsr160RequestDispatcher dispatcher = new Jsr160RequestDispatcher(null, null, null, factory, new HashMap<ConfigProperty, String>());
        replay(ConfigProperty, request, factory, connector);

        JMXConnector con = dispatcher.getConnector(request);
        assertNotNull(con);
        dispatcher.releaseConnector(con);
        verify(ConfigProperty, request, factory, connector);
    }

    @Test
    public void testCreateConnectionsOnMultipleTargetConfigs() throws Exception {
        JmxRequest.TargetConfig config1 = createNiceMock(JmxRequest.TargetConfig.class);
        JmxRequest request1 = createNiceMock(JmxRequest.class);
        JMXConnector connector1 = createNiceMock(JMXConnector.class);
        JmxRequest.TargetConfig config2 = createNiceMock(JmxRequest.TargetConfig.class);
        JmxRequest request2 = createNiceMock(JmxRequest.class);
        JMXConnector connector2 = createNiceMock(JMXConnector.class);
        KeyedPoolableObjectFactory factory = createMock(KeyedPoolableObjectFactory.class);

        expect(request1.getTargetConfig()).andReturn(config1).atLeastOnce();

        expect(factory.makeObject(config1)).andReturn(connector1);
        factory.passivateObject(config1, connector1);
        factory.activateObject(config1, connector1);
        expect(factory.validateObject(config1, connector1)).andReturn(true);

        expect(request2.getTargetConfig()).andReturn(config2).atLeastOnce();

        expect(factory.makeObject(config2)).andReturn(connector2);
        factory.passivateObject(config2, connector2);
        factory.activateObject(config2, connector2);
        expect(factory.validateObject(config2, connector2)).andReturn(true);

        Jsr160RequestDispatcher dispatcher = new Jsr160RequestDispatcher(null, null, null, factory, new HashMap<ConfigProperty, String>());
        replay(config1, request1, config2, request2, factory, connector1, connector2);

        JMXConnector con = dispatcher.getConnector(request1);
        JMXConnector con2 = dispatcher.getConnector(request2);
        assertNotNull(con);
        assertNotNull(con2);
        assertFalse(con == con2);
        verify(config1, config2, request1, request2, factory, connector1, connector2);
    }

    @Test
    public void testPoolEmpty() throws Exception{
        JmxRequest.TargetConfig ConfigProperty = createNiceMock(JmxRequest.TargetConfig.class);
        JmxRequest request = createNiceMock(JmxRequest.class);
        KeyedPoolableObjectFactory factory = createMock(KeyedPoolableObjectFactory.class);
        JMXConnector connector = createNiceMock(JMXConnector.class);

        expect(request.getTargetConfig()).andReturn(ConfigProperty).atLeastOnce();
        expect(factory.makeObject(ConfigProperty)).andReturn(connector);
        factory.passivateObject(ConfigProperty, connector);
        factory.activateObject(ConfigProperty, connector);
        expect(factory.validateObject(ConfigProperty, connector)).andReturn(true);

        HashMap<ConfigProperty, String> pConfig = new HashMap<ConfigProperty, String>();
        pConfig.put(JSR160_POOL_MAX_SIZE, "1");
        pConfig.put(JSR160_POOL_MAX_WAIT_TIME, "100");

        Jsr160RequestDispatcher dispatcher = new Jsr160RequestDispatcher(null, null, null, factory, pConfig);
        replay(ConfigProperty, request, factory, connector);

        JMXConnector con = dispatcher.getConnector(request);
        assertNotNull(con);
        boolean exception = false;
        try {
            dispatcher.getConnector(request);
        } catch (NoSuchElementException e) {
            exception = true;
        }
        assertTrue(exception);
        verify(ConfigProperty, request, factory, connector);
    }

    @Test
    public void testBlockOnBorrow() throws Exception{
        JmxRequest.TargetConfig ConfigProperty = createNiceMock(JmxRequest.TargetConfig.class);
        final JmxRequest request = createNiceMock(JmxRequest.class);
        KeyedPoolableObjectFactory factory = createMock(KeyedPoolableObjectFactory.class);
        Jsr160RequestDispatcher.JMXConnectorConfigDecorator connector = createNiceMock(Jsr160RequestDispatcher.JMXConnectorConfigDecorator.class);

        expect(request.getTargetConfig()).andReturn(ConfigProperty).atLeastOnce();
        expect(factory.makeObject(ConfigProperty)).andReturn(connector);
        factory.passivateObject(ConfigProperty, connector);
        factory.activateObject(ConfigProperty, connector);
        expectLastCall().atLeastOnce();
        expect(factory.validateObject(ConfigProperty, connector)).andReturn(true).atLeastOnce();

        expect(connector.getTargetConfig()).andReturn(ConfigProperty).atLeastOnce();
        factory.passivateObject(ConfigProperty, connector);
        expectLastCall().atLeastOnce();

        HashMap<ConfigProperty, String> pConfig = new HashMap<ConfigProperty, String>();
        pConfig.put(JSR160_POOL_MAX_SIZE, "1");
        pConfig.put(JSR160_POOL_MAX_IDLE, "1");
        pConfig.put(JSR160_POOL_IDLE_TIME, "100000");
        pConfig.put(JSR160_POOL_MAX_WAIT_TIME, "100000");

        final Jsr160RequestDispatcher dispatcher = new Jsr160RequestDispatcher(null, null, null, factory, pConfig);
        replay(ConfigProperty, request, factory, connector);

        JMXConnector con = dispatcher.getConnector(request);
        assertNotNull(con);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Callable<JMXConnector> callable = new Callable<JMXConnector>(){
            public JMXConnector call() throws Exception {
                return dispatcher.getConnector(request);
            }
        };

        boolean exception = false;
        Future<JMXConnector> future = executor.submit(callable);
        try {
            future.get(100, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            exception = true;
        }

        assertTrue(exception);
        dispatcher.releaseConnector(con);

        JMXConnector con2 = future.get(100, TimeUnit.MILLISECONDS);
        assertTrue(con == con2);
        verify(ConfigProperty, request, factory, connector);
    }
}
