package org.jmx4perl.client;

import java.io.IOException;
import java.util.*;

import org.apache.http.HttpResponse;
import org.apache.http.impl.client.DefaultHttpClient;
import org.jmx4perl.client.request.J4pRequest;
import org.jmx4perl.client.request.J4pRequestManager;
import org.jmx4perl.client.response.J4pResponse;
import org.json.simple.*;


/**
 * Client class for accessing the j4p agent
 *
 * @author roland
 * @since Apr 24, 2010
 */
public class J4pClient extends J4pRequestManager {

    // Http client used for connecting the j4p Agent
    private DefaultHttpClient httpClient = new DefaultHttpClient();

    /**
     * Construct a new client for a given server url
     *
     * @param pJ4pServerUrl the agent URL for how to contact the server.
     */
    public J4pClient(String pJ4pServerUrl) {
        super(pJ4pServerUrl);
    }

    /**
     * Execute a single J4pRequest returning a single response.
     * The HTTP Method used is determined automatically.
     *
     * @param pRequest request to execute
     * @param <R> response type
     * @param <T> request type
     * @return the response as returned by the server
     * @throws java.io.IOException when the execution fails
     * @throws org.json.simple.parser.ParseException if parsing of the JSON answer fails
     */
    public <R extends J4pResponse<T>,T extends J4pRequest> R execute(T pRequest) throws J4pException {
        return this.<R,T>execute(pRequest,null);
    }

    /**
     * Execute a single J4pRequest which returns a single response.
     *
     * @param pRequest request to execute
     * @param pMethod method to use which should be either "GET" or "POST"
     * @param <R> response type
     * @param <T> request type
     * @return response object
     * @throws java.io.IOException when the execution fails
     * @throws org.json.simple.parser.ParseException if parsing of the JSON answer fails
     */
    public <R extends J4pResponse<T>,T extends J4pRequest> R execute(T pRequest,String pMethod) throws J4pException {
        try {
            HttpResponse response = httpClient.execute(getHttpRequest(pRequest,pMethod));
            JSONAware jsonResponse = extractJsonResponse(response);
            if (! (jsonResponse instanceof JSONObject)) {
                throw new J4pException("Invalid JSON answer for a single request (expected a map but got a " + jsonResponse.getClass() + ")");
            }
            JSONObject jsonResponseObject = (JSONObject) jsonResponse;
            J4pRemoteException exp = validate(pRequest,jsonResponseObject);
            if (exp == null) {
                return this.<R,T>extractResponse(pRequest, jsonResponseObject);
            } else {
                throw exp;
            }
        } catch (IOException e) {
            throw new J4pException("IO-Error while contacting the server: " + e,e);
        }
    }

    /**
     * Execute multiple requests at once. All given request will result in a single HTTP request where it gets
     * dispatched on the agent side. The results are given back in the same order as the arguments provided.
     *
     * @param pRequests requests to execute
     * @param <R> response type
     * @param <T> request type
     * @return list of responses, one response for each request
     * @throws J4pException when an communication error occurs
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    public <R extends J4pResponse<T>,T extends J4pRequest> List<R> execute(List<T> pRequests) throws J4pException {
        try {
            HttpResponse response = httpClient.execute(getHttpRequest(pRequests));
            JSONAware jsonResponse = extractJsonResponse(response);

            verifyJsonResponse(jsonResponse);

            return extractResponses(jsonResponse, pRequests);
        } catch (IOException e) {
            throw new J4pException("IO-Error while contacting the server: " + e,e);
        }
    }

    // Extract J4pResponses from a returned bulk JSON answer
    @SuppressWarnings("PMD.PreserveStackTrace")
    private <R extends J4pResponse<T>, T extends J4pRequest> List<R> extractResponses(JSONAware pJsonResponse, List<T> pRequests) throws J4pException {
        JSONArray responseArray = (JSONArray) pJsonResponse;
        List<R> ret = new ArrayList<R>(responseArray.size());
        J4pRemoteException remoteExceptions[] = new J4pRemoteException[responseArray.size()];
        boolean exceptionFound = false;
        for (int i = 0; i < pRequests.size(); i++) {
            T request = pRequests.get(i);
            Object jsonResp = responseArray.get(i);
            if (!(jsonResp instanceof JSONObject)) {
                throw new J4pException("Response for request Nr. " + i + " is invalid (expected a map but got " + jsonResp.getClass() + ")");
            }
            JSONObject jsonRespObject = (JSONObject) jsonResp;
            J4pRemoteException exp = validate(request,jsonRespObject);
            if (exp != null) {
                remoteExceptions[i] = exp;
                exceptionFound = true;
                ret.add(i,null);
            } else {
                ret.add(i,this.<R,T>extractResponse(request, (JSONObject) jsonResp));
            }
        }
        if (exceptionFound) {
            List partialResults = new ArrayList();
            // Merge partial results and exceptions in a single list
            for (int i = 0;i<pRequests.size();i++) {
                J4pRemoteException exp = remoteExceptions[i];
                if (exp != null) {
                    partialResults.add(exp);
                } else {
                    partialResults.add(ret.get(i));
                }
            }
            throw new J4pBulkRemoteException(partialResults);
        }
        return ret;
    }

    // Verify the returned JSON answer.
    private void verifyJsonResponse(JSONAware pJsonResponse) throws J4pException {
        if (!(pJsonResponse instanceof JSONArray)) {
            if (pJsonResponse instanceof JSONObject) {
                JSONObject errorObject = (JSONObject) pJsonResponse;
                J4pRemoteException exp = validate(null,errorObject);
                if (exp != null) {
                    throw exp;
                }
            }
            throw new J4pException("Invalid JSON answer for a bulk request (expected an array but got a " + pJsonResponse.getClass() + ")");
        }
    }

    // Validate a result object and create a remote exception in case of an error
    private <T extends J4pRequest> J4pRemoteException validate(T pRequest, JSONObject pJsonRespObject) {
        Long status = (Long) pJsonRespObject.get("status");
        if (status != 200) {
            return new J4pRemoteException(pRequest,(String) pJsonRespObject.get("error"),status.intValue(),(String) pJsonRespObject.get("stacktrace"));
        } else {
            return null;
        }
    }


    /**
     * Execute multiple requests at once. All given request will result in a single HTTP request where it gets
     * dispatched on the agent side. The results are given back in the same order as the arguments provided.
     *
     * @param pRequests requests to execute
     * @param <R> response type
     * @param <T> request type
     * @return list of responses, one response for each request
     * @throws J4pException when an communication error occurs
     */
    public <R extends J4pResponse<T>,T extends J4pRequest> List<R> execute(T ... pRequests) throws J4pException {
        return execute(Arrays.asList(pRequests));
    }
}
