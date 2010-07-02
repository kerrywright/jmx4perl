package org.jmx4perl.client.response;

import java.util.Date;

import org.jmx4perl.client.request.J4pRequest;
import org.jmx4perl.client.request.J4pType;
import org.json.simple.JSONObject;

/**
 * Representation of a j4p Response as sent by the
 * j4p agent.
 *
 * @author roland
 * @since Apr 24, 2010
 */
public abstract class J4pResponse<T extends J4pRequest> {

    // JSON representation of the returned response
    private JSONObject jsonResponse;

    // request which lead to this response
    protected T request;

    // timestamp of this response
    private Date requestDate;

    protected J4pResponse(T pRequest, JSONObject pJsonResponse) {
        request = pRequest;
        jsonResponse = pJsonResponse;
        Long timestamp = (Long) jsonResponse.get("timestamp");
        requestDate = timestamp != null ? new Date(timestamp) : new Date();
    }

    /**
     * Get the request associated with this response
     * @return the request
     */
    public T getRequest() {
        return request;
    }

    /**
     * Get the request/response type
     *
     * @return type
     */
    public J4pType getType() {
        return request.getType();
    }

    /**
     * Date when the request was processed
     *
     * @return request date
     */
    public Date getRequestDate() {
        return (Date) requestDate.clone();
    }

    /**
     * Get the value of this response
     *
     * @return json representation of answer
     */
    public <V> V getValue() {
        return (V) jsonResponse.get("value");
    }
}
