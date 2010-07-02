package org.jmx4perl.history;

import org.jmx4perl.JmxRequest;
import org.jmx4perl.JmxRequestBuilder;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.Before;
import org.junit.Test;

import javax.management.MalformedObjectNameException;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.jmx4perl.JmxRequest.Type.*;


/**
 * Unit test for history functionality
 *
 * @author roland
 * @since Mar 9, 2010
 */
public class HistoryStoreTest {

    private HistoryStore store;


    @Before
    public void initStore() {
        store = new HistoryStore(10);
    }

    @Test
    public void invalidHistoryKey() throws MalformedObjectNameException {
        JmxRequest req =
                new JmxRequestBuilder(SEARCH,"test:type=search")
                        .build();
        try {
            new HistoryKey(req);
            fail("Invalid operation");
        } catch (IllegalArgumentException exp) {
            // expected
        }

        req = new JmxRequestBuilder(EXEC,"test:type=read")
                .build();
        try {
            new HistoryKey(req);
            fail("No operation name");
        } catch (IllegalArgumentException exp) {}

        req = new JmxRequestBuilder(READ,"test:type=*")
                .attribute("bla")
                .build();
        try {
            new HistoryKey(req);
            fail("No pattern allowed");
        } catch (IllegalArgumentException exp) {}

        req = new JmxRequestBuilder(READ,"test:type=bla")
                .attributes("bla","bla2")
                .build();
        try {
            new HistoryKey(req);
            fail("No multiple attributes allowed");
        } catch (IllegalArgumentException exp) {}
    }

    @Test
    public void configure() throws MalformedObjectNameException {
        JmxRequest req =
                new JmxRequestBuilder(EXEC,"test:type=exec")
                        .operation("op")
                        .build();
        store.configure(new HistoryKey(req),2);
        assertEquals("2 history entries",2,updateNTimesAsList(req,3).size());
        store.configure(new HistoryKey(req),4);
        assertEquals("4 history entries",4,updateNTimesAsList(req,5).size());
        store.configure(new HistoryKey(req),12);
        assertEquals("10 history entries (max. for store)",10,updateNTimesAsList(req,10).size());
        store.setGlobalMaxEntries(20);
        assertEquals("Read max entries",20,store.getGlobalMaxEntries());
        assertEquals("20 history entries (max. for store)",20,updateNTimesAsList(req,30).size());
        store.reset();
        store.configure(new HistoryKey(req),20);
        /** 5 fresh updates yield 4 history entries returned (and 5 stored) */
        assertEquals("4 history entries after reset",4,updateNTimesAsList(req,5).size());
        store.configure(new HistoryKey(req),0);
        assertEquals("History disabled",null,updateNTimesAsList(req,12));

    }

    @Test
    public void singleAttributeRead() throws Exception {
        JmxRequest req =
                new JmxRequestBuilder(READ,"test:type=read")
                        .attribute("attr")
                        .build();
        store.configure(new HistoryKey(req),3);
        /** 3 fresh updates yield 2 history entries returned (and 3 stored) */
        assertEquals("2 history entries",2,updateNTimesAsList(req,3,"42").size());

    }

    @Test
    public void singleAttributeWrite() throws Exception {
        JmxRequest req =
                new JmxRequestBuilder(WRITE,"test:type=write")
                        .attribute("attr")
                        .value("val1")
                        .build();
        store.configure(new HistoryKey(req),5);
        assertEquals("4 history entries",3,updateNTimesAsList(req,4).size());
    }
    @Test
    public void singleAttributeAsListRead() throws Exception {
        JmxRequest req =
                new JmxRequestBuilder(READ,"test:type=read")
                        .attributes("attr")
                        .build();
        store.configure(new HistoryKey(req),5);
        JSONArray res = updateNTimesAsList(req,4,"42");
        assertEquals("4 history entries",3,res.size());
    }

    @Test
    public void noAttributesRead() throws Exception {
        String mbean = "test:type=read";
        JmxRequest req =
                new JmxRequestBuilder(READ,mbean)
                        .build();
        store.configure(new HistoryKey(mbean,"attr1",null,null),4);
        store.configure(new HistoryKey(mbean,"attr2",null,null),5);
        Map value = new HashMap();
        value.put("attr1","val1");
        value.put("attr2","val2");
        JSONObject history = updateNTimesAsMap(req,5,value);
        assertEquals("Attr1 has 3 entries",4,((List) history.get("attr1")).size());
        assertEquals("Attr2 has 4 entries",4,((List) history.get("attr2")).size());
    }

    @Test
    public void multipleAttributeRead() throws Exception {
        String mbean = "test:type=read";
        JmxRequest req =
                new JmxRequestBuilder(READ,mbean)
                        .attributes("attr1","attr2")
                        .build();
        store.configure(new HistoryKey(mbean,"attr1",null,null),3);
        store.configure(new HistoryKey(mbean,"attr2",null,null),5);
        /** 5 fresh updates yield 2 history entries returned (and 3 stored) */
        Map value = new HashMap();
        value.put("attr1","val1");
        value.put("attr2","val2");
        JSONObject history = updateNTimesAsMap(req,5,value);
        assertEquals("Attr1 has 3 entries",3,((List) history.get("attr1")).size());
        assertEquals("Attr2 has 4 entries",4,((List) history.get("attr2")).size());
    }


    @Test
    public void patternAttributeRead() throws Exception {
        JmxRequest req =
                new JmxRequestBuilder(READ,"test:type=*")
                        .attributes("attr1","attr2")
                        .build();
        store.configure(new HistoryKey("test:type=read","attr1",null,null),3);
        store.configure(new HistoryKey("test:type=write","attr2",null,null),5);
        /** 5 fresh updates yield 2 history entries returned (and 3 stored) */
        Map mBeanMap = new HashMap();
        Map attr1Map = new HashMap();
        mBeanMap.put("test:type=read",attr1Map);
        attr1Map.put("attr1","val1");
        Map attr2Map = new HashMap();
        mBeanMap.put("test:type=write",attr2Map);
        attr2Map.put("attr2","val2");
        JSONObject history = updateNTimesAsMap(req,4,mBeanMap);
        assertEquals("History has 2 entries",2,history.size());
        assertEquals("bean1 has 1 entry",1,((Map) history.get("test:type=read")).size());
        assertEquals("bean1 has 1 entry",1,((Map) history.get("test:type=write")).size());
        assertEquals("attr1 has 3 history entries",3,((List) ((Map) history.get("test:type=read")).get("attr1")).size());
        assertEquals("attr2 has 3 history entries",3,((List) ((Map) history.get("test:type=write")).get("attr2")).size());
    }


    private JSONArray updateNTimesAsList(JmxRequest pReq, int pNr,Object ... pValue) {
        return (JSONArray) updateNTimes(pReq, pNr,pValue);
    }

    private JSONObject updateNTimesAsMap(JmxRequest pReq, int pNr,Object ... pValue) {
        return (JSONObject) updateNTimes(pReq, pNr,pValue);
    }
    private Object updateNTimes(JmxRequest pReq, int pNr,Object ... pValue) {
        JSONObject res = new JSONObject();
        if (pValue != null && pValue.length > 0) {
            res.put("value",pValue[0]);
        }
        for (int i=0;i<pNr;i++) {
            store.updateAndAdd(pReq,res);
        }
        return res.get("history");
    }


}
