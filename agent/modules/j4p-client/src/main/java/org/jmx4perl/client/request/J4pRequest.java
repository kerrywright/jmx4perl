package org.jmx4perl.client.request;

import java.util.List;

import org.jmx4perl.client.response.J4pResponse;
import org.json.simple.JSONObject;

/**
 * Request object abstracting a request to a j4p agent.
 *
 * @author roland
 * @since Apr 24, 2010
 */
abstract public class J4pRequest {

    J4pType type;

    private String preferredHttpMethod;

    protected J4pRequest(J4pType pType) {
        type = pType;
    }

    public J4pType getType() {
        return type;
    }


    // ==================================================================================================
    // Methods used for building up HTTP Requests and setting up the reponse
    // These methods are package visible only since are used only internally

    // Get the parts to build up a GET url (without the type as the first part)
    abstract List<String> getRequestParts();

    // Get a JSON representation of this request
    JSONObject toJson() {
        JSONObject ret = new JSONObject();
        ret.put("type",type.name());
        return ret;
    }

    /**
     * Create a response from a given JSON response
     *
     * @param pResponse http response as obtained from the Http-Request
     * @return the create response
     */
    abstract <R extends J4pResponse<? extends J4pRequest>> R createResponse(JSONObject pResponse);

    public String getPreferredHttpMethod() {
        return preferredHttpMethod;
    }

    public void setPreferredHttpMethod(String pPreferredHttpMethod) {
        preferredHttpMethod = pPreferredHttpMethod;
    }
}
