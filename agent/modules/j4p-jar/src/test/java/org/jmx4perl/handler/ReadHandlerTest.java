package org.jmx4perl.handler;

import org.jmx4perl.JmxRequest;
import org.jmx4perl.JmxRequestBuilder;
import org.jmx4perl.config.AllowAllRestrictor;
import org.jmx4perl.config.Restrictor;
import org.junit.Before;
import org.junit.Test;

import javax.management.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;

import static org.easymock.classextension.EasyMock.*;
import static org.jmx4perl.JmxRequest.Type.READ;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * @author roland
 * @since Mar 6, 2010
 */
public class ReadHandlerTest {

    // handler to test
    private ReadHandler handler;

    private ObjectName testBeanName;

    @Before
    public void createHandler() throws MalformedObjectNameException {
        handler = new ReadHandler(new AllowAllRestrictor());
        testBeanName = new ObjectName("jmx4perl:type=test");
    }

    @Test
    public void singleBeanSingleAttribute() throws Exception {
        JmxRequest request = new JmxRequestBuilder(READ, testBeanName.getCanonicalName()).
                attribute("testAttribute").
                build();

        MBeanServerConnection connection = createMock(MBeanServerConnection.class);
        expect(connection.getAttribute(testBeanName,"testAttribute")).andReturn("testValue");
        replay(connection);
        Object res = handler.handleRequest(connection,request);
        verify(connection);
        assertEquals("testValue",res);
    }

    @Test
    public void singleBeanNoAttributes() throws Exception {
        JmxRequest request = new JmxRequestBuilder(READ, testBeanName.getCanonicalName()).
                attribute(null).
                build();


        MBeanServerConnection connection = createMock(MBeanServerConnection.class);
        String attrs[] = new String[] {"attr0","atrr1","attr2"};
        String vals[]  = new String[] {"val0", "val1", "val2"};
        prepareMBeanInfos(connection, testBeanName, attrs);
        for (int i=0;i<attrs.length;i++) {
            expect(connection.getAttribute(testBeanName,attrs[i])).andReturn(vals[i]);
        }
        replay(connection);

        Map res = (Map) handler.handleRequest(connection,request);
        verify(connection);
        for (int i=0;i<attrs.length;i++) {
            assertEquals(vals[i],res.get(attrs[i]));

        }
    }

    @Test
    public void singleBeanMultiAttributes() throws Exception {
        JmxRequest request = new JmxRequestBuilder(READ, testBeanName.getCanonicalName()).
                attributes(Arrays.asList("attr0","attr1")).
                build();


        MBeanServerConnection connection = createMock(MBeanServerConnection.class);
        expect(connection.getAttribute(testBeanName,"attr0")).andReturn("val0");
        expect(connection.getAttribute(testBeanName,"attr1")).andReturn("val1");
        replay(connection);

        Map res = (Map) handler.handleRequest(connection,request);
        verify(connection);
        assertEquals("val0",res.get("attr0"));
        assertEquals("val1",res.get("attr1"));
    }

    // ======================================================================================================

    @Test
    public void searchPatternNoMatch() throws Exception {
        ObjectName patternMBean = new ObjectName("bla:type=*");
        JmxRequest request = new JmxRequestBuilder(READ, patternMBean).
                attribute("mem1").
                build();
        MBeanServerConnection connection = createMock(MBeanServerConnection.class);
        expect(connection.queryNames(patternMBean,null)).andReturn(new HashSet());
        replay(connection);
        try {
            handler.handleRequest(connection,request);
            fail("Exception should be thrown");
        } catch (InstanceNotFoundException exp) {}
    }

    @Test
    public void searchPatternSingleAttribute() throws Exception {
        ObjectName patternMBean = new ObjectName("java.lang:type=*");
        JmxRequest request = new JmxRequestBuilder(READ, patternMBean).
                attribute("mem1").
                build();

        ObjectName beans[] =  {
                new ObjectName("java.lang:type=Memory"),
                new ObjectName("java.lang:type=GarbageCollection")
        };
        MBeanServerConnection connection = prepareMultiAttributeTest(patternMBean, beans);
        expect(connection.getAttribute(beans[0],"mem1")).andReturn("memval1");

        replay(connection);
        Map res = (Map) handler.handleRequest(connection, request);
        verify(connection);
        assertEquals(1,res.size());
        assertEquals("memval1",((Map) res.get("java.lang:type=Memory")).get("mem1"));
    }

    @Test
    public void searchPatternNoAttribute() throws Exception {
        ObjectName patternMBean = new ObjectName("java.lang:type=*");
        JmxRequest[] requests = new JmxRequest[] {
                new JmxRequestBuilder(READ, patternMBean).
                        attribute(null).
                        build(),
                new JmxRequestBuilder(READ, patternMBean).
                        // A single null element is enough to denote "all"
                        attributes(Arrays.asList("bla",null)).
                        build()
        };

        for (JmxRequest request : requests) {
            ObjectName beans[] =  {
                    new ObjectName("java.lang:type=Memory"),
                    new ObjectName("java.lang:type=GarbageCollection")
            };
            MBeanServerConnection connection = prepareMultiAttributeTest(patternMBean, beans);
            expect(connection.getAttribute(beans[0],"mem0")).andReturn("memval0");
            expect(connection.getAttribute(beans[0],"mem1")).andReturn("memval1");
            expect(connection.getAttribute(beans[0],"common")).andReturn("commonVal0");
            expect(connection.getAttribute(beans[1],"gc0")).andReturn("gcval0");
            expect(connection.getAttribute(beans[1],"gc1")).andReturn("gcval1");
            expect(connection.getAttribute(beans[1],"gc3")).andReturn("gcval3");
            expect(connection.getAttribute(beans[1],"common")).andReturn("commonVal1");
            replay(connection);

            Map res = (Map) handler.handleRequest(connection, request);

            assertEquals("memval0",((Map) res.get("java.lang:type=Memory")).get("mem0"));
            assertEquals("memval1",((Map) res.get("java.lang:type=Memory")).get("mem1"));
            assertEquals("commonVal0",((Map) res.get("java.lang:type=Memory")).get("common"));
            assertEquals("gcval0",((Map) res.get("java.lang:type=GarbageCollection")).get("gc0"));
            assertEquals("gcval1",((Map) res.get("java.lang:type=GarbageCollection")).get("gc1"));
            assertEquals("gcval3",((Map) res.get("java.lang:type=GarbageCollection")).get("gc3"));
            assertEquals("commonVal1",((Map) res.get("java.lang:type=GarbageCollection")).get("common"));

            verify(connection);
        }
    }

    @Test
    public void searchPatternNoAttributesFound() throws Exception {
        ObjectName patternMBean = new ObjectName("java.lang:type=*");
        JmxRequest request = new JmxRequestBuilder(READ, patternMBean).
                attribute(null).
                build();
        ObjectName beans[] =  {
                new ObjectName("java.lang:type=Memory"),
                new ObjectName("java.lang:type=GarbageCollection")
        };
        MBeanServerConnection connection = createMock(MBeanServerConnection.class);
        expect(connection.queryNames(patternMBean,null)).andReturn(new HashSet(Arrays.asList(beans)));
        prepareMBeanInfos(connection,beans[0],new String[0]);
        prepareMBeanInfos(connection,beans[1],new String[] { "gc0" });
        expect(connection.getAttribute(beans[1],"gc0")).andReturn("gcval0");
        replay(connection);

        Map res = (Map) handler.handleRequest(connection, request);

        // Only a single entry fetched
        assertEquals(res.size(),1);
        verify(connection);
    }



    @Test
    public void searchPatternNoMatchingAttribute() throws Exception {
        ObjectName patternMBean = new ObjectName("java.lang:type=*");
        JmxRequest request = new JmxRequestBuilder(READ, patternMBean).
                attribute("blub").
                build();

        ObjectName beans[] =  {
                new ObjectName("java.lang:type=Memory"),
                new ObjectName("java.lang:type=GarbageCollection")
        };
        MBeanServerConnection connection = prepareMultiAttributeTest(patternMBean, beans);
        replay(connection);
        try {
            handler.handleRequest(connection, request);
        } catch (IllegalArgumentException exp) {
            // expected
        }
        verify(connection);
    }

    @Test
    public void searchPatternMultiAttributes1() throws Exception {
        ObjectName patternMBean = new ObjectName("java.lang:type=*");
        JmxRequest request = new JmxRequestBuilder(READ, patternMBean).
                attributes(Arrays.asList("mem0","gc3")).
                build();

        ObjectName beans[] =  {
                new ObjectName("java.lang:type=Memory"),
                new ObjectName("java.lang:type=GarbageCollection")
        };
        MBeanServerConnection connection = prepareMultiAttributeTest(patternMBean, beans);
        expect(connection.getAttribute(beans[0],"mem0")).andReturn("memval0");
        expect(connection.getAttribute(beans[1],"gc3")).andReturn("gcval3");

        replay(connection);
        Map res = (Map) handler.handleRequest(connection, request);
        verify(connection);
        assertEquals(2,res.size());
        assertEquals("memval0",((Map) res.get("java.lang:type=Memory")).get("mem0"));
        assertEquals("gcval3",((Map) res.get("java.lang:type=GarbageCollection")).get("gc3"));
    }


    @Test
    public void searchPatternMultiAttributes3() throws Exception {
        ObjectName patternMBean = new ObjectName("java.lang:type=*");
        JmxRequest request = new JmxRequestBuilder(READ, patternMBean).
                attributes(Arrays.asList("bla")).
                build();

        ObjectName beans[] =  {
                new ObjectName("java.lang:type=Memory"),
                new ObjectName("java.lang:type=GarbageCollection")
        };
        MBeanServerConnection connection = prepareMultiAttributeTest(patternMBean, beans);
        replay(connection);

        try {
            Map res = (Map) handler.handleRequest(connection, request);
            fail("Request should fail since attribute name doesn't match any MBean's attribute");
        } catch (IllegalArgumentException exp) {
            // Expect this since no MBean matches the given attribute
        }
    }

    @Test
    public void searchPatternMultiAttributes4() throws Exception {
        ObjectName patternMBean = new ObjectName("java.lang:type=*");
        JmxRequest request = new JmxRequestBuilder(READ, patternMBean).
                attributes(Arrays.asList("common")).
                build();

        ObjectName beans[] =  {
                new ObjectName("java.lang:type=Memory"),
                new ObjectName("java.lang:type=GarbageCollection")
        };
        MBeanServerConnection connection = prepareMultiAttributeTest(patternMBean, beans);
        expect(connection.getAttribute(beans[0],"common")).andReturn("com1");
        expect(connection.getAttribute(beans[1],"common")).andReturn("com2");

        replay(connection);
        Map res = (Map) handler.handleRequest(connection, request);
        verify(connection);
        assertEquals(2,res.size());
        assertEquals("com1",((Map) res.get("java.lang:type=Memory")).get("common"));
        assertEquals("com2",((Map) res.get("java.lang:type=GarbageCollection")).get("common"));
    }

    private MBeanServerConnection prepareMultiAttributeTest(ObjectName pPatternMBean, ObjectName[] pBeans)
            throws IOException, MBeanException, AttributeNotFoundException, InstanceNotFoundException, ReflectionException, IntrospectionException {
        MBeanServerConnection connection = createMock(MBeanServerConnection.class);
        String params[][] = {
                new String[] {"mem0","mem1","common" },
                new String[] {"gc0","gc1","gc3","common"},
        };
        expect(connection.queryNames(pPatternMBean,null)).andReturn(new HashSet(Arrays.asList(pBeans)));
        for (int i=0;i< pBeans.length;i++) {
            prepareMBeanInfos(connection, pBeans[i],params[i]);
        }
        return connection;
    }


    // ==============================================================================================================

    @Test
    public void restrictAccess() throws Exception {
        Restrictor restrictor = createMock(Restrictor.class);
        expect(restrictor.isAttributeReadAllowed(testBeanName,"attr")).andReturn(false);
        handler = new ReadHandler(restrictor);

        JmxRequest request = new JmxRequestBuilder(READ, testBeanName).
                attribute("attr").
                build();
        MBeanServerConnection connection = createMock(MBeanServerConnection.class);
        replay(restrictor,connection);
        try {
            handler.handleRequest(connection,request);
            fail("Restrictor should forbid access");
        } catch (SecurityException exp) {}
        verify(restrictor,connection);
    }

    // ==============================================================================================================

    private MBeanAttributeInfo[] prepareMBeanInfos(MBeanServerConnection pConnection, ObjectName pObjectName, String pAttrs[])
            throws MBeanException, AttributeNotFoundException, InstanceNotFoundException, ReflectionException, IOException, IntrospectionException {
        MBeanInfo mBeanInfo = createMock(MBeanInfo.class);
        expect(pConnection.getMBeanInfo(pObjectName)).andReturn(mBeanInfo);
        MBeanAttributeInfo[] infos = new MBeanAttributeInfo[pAttrs.length];
        for (int i=0;i<pAttrs.length;i++) {
            infos[i] = createMock(MBeanAttributeInfo.class);
            expect(infos[i].getName()).andReturn(pAttrs[i]);
        }
        expect(mBeanInfo.getAttributes()).andReturn(infos);
        replay(mBeanInfo);
        for (int j=0;j<infos.length;j++) {
            replay(infos[j]);
        }
        return infos;
    }


}
