package org.jmx4perl.client.request;

import java.util.*;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.jmx4perl.client.response.J4pReadResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/**
 * A read request to get one or more attributes from
 * one or more MBeans within a single request.
 *
 * @author roland
 * @since Apr 24, 2010
 */
public class J4pReadRequest extends AbtractJ4pMBeanRequest {

    // Name of attribute to request
    private List<String> attributes;

    // Path for extracting return value
    private String path;

    /**
     * Create a READ request to request one or more attributes
     * from the remote j4p agent
     *
     * @param pObjectName Name of the MBean to request, which can be a pattern in
     *                    which case the given attributes are looked at all MBeans matched
     *                    by this pattern. If an attribute does not fit to a matched MBean it is
     *                    ignored.
     * @param pAttribute one or more attributes to request.
     */
    public J4pReadRequest(ObjectName pObjectName,String ... pAttribute) {
        super(J4pType.READ, pObjectName);
        attributes = Arrays.asList(pAttribute);
    }


    /**
     * Create a READ request to request one or more attributes
     * from the remote j4p agent
     *
     * @param pObjectName object name as sting which gets converted to a {@link javax.management.ObjectName}}
     * @param pAttribute one or more attributes to request.
     * @throws javax.management.MalformedObjectNameException when argument is not a valid object name
     */
    public J4pReadRequest(String pObjectName,String ... pAttribute) throws MalformedObjectNameException {
        this(new ObjectName(pObjectName),pAttribute);
    }


    /**
     * Get all attributes of this request
     *
     * @return attributes
     */
    public Collection<String> getAttributes() {
        return attributes;
    }

    /**
     * If this request is for a single attribute, this attribute is returned
     * by this getter.
     * @return single attribute
     * @throws IllegalArgumentException if no or more than one attribute are used when this request was
     *         constructed.
     */
    public String getAttribute() {
        if (attributes == null || !hasSingleAttribute()) {
            throw new IllegalArgumentException("More than one attribute given for this request");
        }
        return attributes.get(0);
    }

    @Override
    List<String> getRequestParts() {
        if (hasSingleAttribute()) {
            List<String> ret = super.getRequestParts();
            ret.add(getAttribute());
            if (path != null) {
                // Split up path
                ret.addAll(Arrays.asList(path.split("/")));
            }
            return ret;
        } else {
            // A GET request cant be used for multiple attribute fetching.
            return null;
        }
    }

    @Override
    JSONObject toJson() {
        JSONObject ret = super.toJson();
        if (hasSingleAttribute()) {
            ret.put("attribute",attributes.get(0));
        } else {
            JSONArray attrs = new JSONArray();
            attrs.addAll(attributes);
            ret.put("attribute",attrs);
        }
        if (path != null) {
            ret.put("path",path);
        }
        return ret;
    }

    @Override
    J4pReadResponse createResponse(JSONObject pResponse) {
        return new J4pReadResponse(this,pResponse);
    }

    public boolean hasSingleAttribute() {
        return attributes != null && attributes.size() == 1;
    }

    /**
     * Get the path for extracting parts of the return value
     * @return path used for extracting
     */
    public String getPath() {
        return path;
    }

    /**
     * Set the path for diving into the return value
     *
     * @param pPath path to set
     */
    public void setPath(String pPath) {
        path = pPath;
    }


}
