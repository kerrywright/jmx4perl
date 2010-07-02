package org.jmx4perl.client.request;

import java.io.IOException;
import java.util.Date;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.jmx4perl.client.J4pException;
import org.jmx4perl.client.response.J4pReadResponse;
import org.jmx4perl.client.response.J4pWriteResponse;
import org.json.simple.parser.ParseException;
import org.junit.Test;

import static junit.framework.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;

/**
 * Integration test for writing attributes
 *
 * @author roland
 * @since Jun 5, 2010
 */
public class J4pWriteIntegrationTest extends AbstractJ4pIntegrationTest {

    @Test
    public void simple() throws MalformedObjectNameException, J4pException {
        checkWrite("IntValue",null,42);
    }

    @Test
    public void withPath() throws MalformedObjectNameException, J4pException {
        checkWrite("ComplexNestedValue","Blub/1/numbers/0","13");
    }

    @Test
    public void withBeanPath() throws MalformedObjectNameException, J4pException {
        checkWrite("Bean","value","41");
    }

    @Test
    public void nullValue() throws MalformedObjectNameException, J4pException {
        checkWrite("Bean","name",null);
    }

    @Test
    public void emptyString() throws MalformedObjectNameException, J4pException {
        checkWrite("Bean","name","");
    }

    @Test
    public void access() throws MalformedObjectNameException {
        J4pWriteRequest req = new J4pWriteRequest("jmx4perl.it:type=attribute","List","bla");
        req.setPath("0");
        assertEquals(req.getPath(),"0");
        assertEquals(req.getAttribute(),"List");
        assertEquals(req.getObjectName(),new ObjectName("jmx4perl.it:type=attribute"));
        assertEquals(req.getValue(),"bla");
        assertEquals(req.getType(),J4pType.WRITE);
    }

    private void checkWrite(String pAttribute,String pPath,Object pValue) throws MalformedObjectNameException, J4pException {
        for (String method : new String[] { "GET", "POST" }) {
            reset();
            J4pReadRequest readReq = new J4pReadRequest("jmx4perl.it:type=attribute",pAttribute);
            if (pPath != null) {
                readReq.setPath(pPath);
            }
            J4pReadResponse readResp = j4pClient.execute(readReq,method);
            String oldValue = readResp.getValue();
            assertNotNull("Old value must not be null",oldValue);

            J4pWriteRequest req = new J4pWriteRequest("jmx4perl.it:type=attribute",pAttribute,pValue,pPath);
            J4pWriteResponse resp = j4pClient.execute(req,method);
            assertEquals("Old value should be returned",oldValue,resp.getValue());

            readResp = j4pClient.execute(readReq);
            assertEquals("New value should be set",pValue != null ? pValue.toString() : null,readResp.getValue());
        }
    }


    private void reset() throws MalformedObjectNameException, J4pException {
        j4pClient.execute(new J4pExecRequest("jmx4perl.it:type=attribute","reset"));
    }

}
