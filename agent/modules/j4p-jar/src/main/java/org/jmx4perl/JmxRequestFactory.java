package org.jmx4perl;

import org.jmx4perl.JmxRequest.Type;
import org.json.simple.JSONAware;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import javax.management.MalformedObjectNameException;
import java.io.IOException;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * jmx4perl - WAR Agent for exporting JMX via JSON
 *
 * Copyright (C) 2009 Roland Huß, roland@cpan.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 *
 * A commercial license is available as well. Please contact roland@cpan.org for
 * further details.
 */

/**
 * Factory for creating {@link org.jmx4perl.JmxRequest}s
 *
 * @author roland
 * @since Oct 29, 2009
 */
final public class JmxRequestFactory {

    // Pattern for detecting escaped slashes in URL encoded requests
    private static final Pattern SLASH_ESCAPE_PATTERN = Pattern.compile("^\\^?-*\\+?$");

    // private constructor for static class
    private JmxRequestFactory() { }

    /**
     *
     * Create a JMX request from a GET Request with a REST Url.
     * <p>
     * The REST-Url which gets recognized has the following format:
     * <p>
     * <pre>
     *    &lt;base_url&gt;/&lt;type&gt;/&lt;param1&gt;/&lt;param2&gt;/....
     * </pre>
     * <p>
     * where <code>base_url<code> is the URL specifying the overall servlet (including
     * the servlet context, something like "http://localhost:8080/j4p-agent"),
     * <code>type</code> the operational mode and <code>param1 .. paramN<code>
     * the provided parameters which are dependend on the <code>type<code>
     * <p>
     * The following types are recognized so far, along with there parameters:
     *
     * <ul>
     *   <li>Type: <b>read</b> ({@link Type#READ}<br/>
     *       Parameters: <code>param1<code> = MBean name, <code>param2</code> = Attribute name,
     *       <code>param3 ... paramN</code> = Inner Path.
     *       The inner path is optional and specifies a path into complex MBean attributes
     *       like collections or maps. If within collections/arrays/tabular data,
     *       <code>paramX</code> should specify
     *       a numeric index, in maps/composite data <code>paramX</code> is a used as a string
     *       key.</li>
     *   <li>Type: <b>write</b> ({@link Type#WRITE}<br/>
     *       Parameters: <code>param1</code> = MBean name, <code>param2</code> = Attribute name,
     *       <code>param3</code> = value, <code>param4 ... paramN</code> = Inner Path.
     *       The value must be URL encoded (with UTF-8 as charset), and must be convertible into
     *       a data structure</li>
     *   <li>Type: <b>exec</b> ({@link Type#EXEC}<br/>
     *       Parameters: <code>param1</code> = MBean name, <code>param2</code> = operation name,
     *       <code>param4 ... paramN</code> = arguments for the operation.
     *       The arguments must be URL encoded (with UTF-8 as charset), and must be convertable into
     *       a data structure</li>
     *    <li>Type: <b>version</b> ({@link Type#VERSION}<br/>
     *        Parameters: none
     *    <li>Type: <b>search</b> ({@link Type#SEARCH}<br/>
     *        Parameters: <code>param1</code> = MBean name pattern
     * </ul>
     * @param pPathInfo path info of HTTP request
     * @param pParameterMap HTTP Query parameters
     * @return a newly created {@link org.jmx4perl.JmxRequest}
     */
    static public JmxRequest createRequestFromUrl(String pPathInfo, Map<String,String[]> pParameterMap) {
        JmxRequest request = null;
        try {
            String pathInfo = pPathInfo;

            // If no pathinfo is given directly, we look for a query parameter named 'p'.
            // This variant is helpful, if there are problems with the server mangling
            // up the pathinfo (e.g. for security concerns, often '/','\',';' and other are not
            // allowed in encoded form within the pathinfo)
            if (pPathInfo == null || pPathInfo.length() == 0 || pathInfo.matches("^/+$")) {
                String[] vals = pParameterMap.get("p");
                if (vals != null && vals.length > 0) {
                    pathInfo = vals[0];
                }
            }

            if (pathInfo != null && pathInfo.length() > 0) {
                // Get all path elements as a reverse stack
                Stack<String> elements = extractElementsFromPath(pathInfo);
                Type type = extractType(elements.pop());

                Processor processor = PROCESSOR_MAP.get(type);
                if (processor == null) {
                    throw new UnsupportedOperationException("Type " + type + " is not supported (yet)");
                }

                // Parse request
                request = processor.process(elements);

                // Extract all additional args from the remaining path info
                request.setExtraArgs(prepareExtraArgs(elements));

                // Setup JSON representation
                extractParameters(request,pParameterMap);
                return request;
            } else {
                throw new IllegalArgumentException("No pathinfo given and no query parameter 'p'");
            }
        } catch (NoSuchElementException exp) {
            throw new IllegalArgumentException("Invalid path info " + pPathInfo,exp);
        } catch (MalformedObjectNameException e) {
            throw new IllegalArgumentException("Invalid object name. " + e.getMessage(),e);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("Internal: Illegal encoding for URL conversion: " + e,e);
        } catch (EmptyStackException exp) {
            throw new IllegalArgumentException("Invalid arguments in pathinfo " + pPathInfo + (request != null ? " for command " + request.getType() : ""),exp);
        }
    }


    /**
     * Create a list of {@link JmxRequest}s from a JSON list representing jmx requests
     *
     * @param pJsonRequests JSON representation of a list of {@link org.jmx4perl.JmxRequest}
     * @return list with one or more {@link org.jmx4perl.JmxRequest}
     * @throws javax.management.MalformedObjectNameException if the MBean name within the request is invalid
     */
    public static List<JmxRequest> createRequestsFromJson(List pJsonRequests) throws MalformedObjectNameException {
        List<JmxRequest> ret = new ArrayList<JmxRequest>();
        for (Object o : pJsonRequests) {
            if (!(o instanceof Map)) {
                throw new IllegalArgumentException("Not a request within the list of requests " + pJsonRequests +
                        ". Expected map, but found: " + o);
            }
            ret.add(new JmxRequest((Map) o));
        }
        return ret;
    }

    /**
     * Create a single {@link JmxRequest}s from a JSON map representation of a request
     *
     * @param pJsonRequest JSON representation of a {@link org.jmx4perl.JmxRequest}
     * @return the created {@link org.jmx4perl.JmxRequest}
     * @throws javax.management.MalformedObjectNameException if the MBean name within the request is invalid
     */
    public static JmxRequest createSingleRequestFromJson(Map<String,?> pJsonRequest) throws MalformedObjectNameException {
        return new JmxRequest(pJsonRequest);
    }

    /*
    We need to use this special treating for slashes (i.e. to escape with '/-/') because URI encoding doesnt work
    well with HttpRequest.pathInfo() since in Tomcat/JBoss slash seems to be decoded to early so that it get messed up
    and answers with a "HTTP/1.x 400 Invalid URI: noSlash" without returning any further indications

    For the rest of unsafe chars, we use uri decoding (as anybody should do). It could be of course the case,
    that the pathinfo has been already uri decoded (dont know by heart)
     */
    private static Stack<String> extractElementsFromPath(String path) throws UnsupportedEncodingException {
        String[] elements = (path.startsWith("/") ? path.substring(1) : path).split("/+");

        Stack<String> ret = new Stack<String>();
        Stack<String> elementStack = new Stack<String>();

        for (int i=elements.length-1;i>=0;i--) {
            elementStack.push(elements[i]);
        }

        extractElements(ret,elementStack,null);
        if (ret.size() == 0) {
            throw new IllegalArgumentException("No request type given");
        }

        // Reverse stack
        Collections.reverse(ret);

        return ret;
    }


    private static void extractElements(Stack<String> ret, Stack<String> pElementStack,StringBuffer previousBuffer)
            throws UnsupportedEncodingException {
        if (pElementStack.isEmpty()) {
            if (previousBuffer != null && previousBuffer.length() > 0) {
                ret.push(decode(previousBuffer.toString()));
            }
            return;
        }
        String element = pElementStack.pop();
        Matcher matcher = SLASH_ESCAPE_PATTERN.matcher(element);
        if (matcher.matches()) {
            if (ret.isEmpty()) {
                return;
            }
            StringBuffer val;

            // Special escape at the beginning indicates that this element belongs
            // to the next one
            if (element.substring(0,1).equals("^")) {
                val = new StringBuffer();
            } else if (previousBuffer == null) {
                val = new StringBuffer(ret.pop());
            } else {
                val = previousBuffer;
            }
            // Append appropriate nr of slashes
            for (int j=0;j<element.length();j++) {
                val.append("/");
            }
            // Special escape at the end indicates that this is the last element in the path
            if (!element.substring(element.length()-1,element.length()).equals("+")) {
                if (!pElementStack.isEmpty()) {
                    val.append(decode(pElementStack.pop()));
                }
                extractElements(ret,pElementStack,val);
                return;
            } else {
                ret.push(decode(val.toString()));
                extractElements(ret,pElementStack,null);
                return;
            }
        }
        if (previousBuffer != null) {
            ret.push(decode(previousBuffer.toString()));
        }
        ret.push(decode(element));
        extractElements(ret,pElementStack,null);
    }

    private static String decode(String s) {
        return s;
        //return URLDecoder.decode(s,"UTF-8");

    }

    private static Type extractType(String pTypeS) {
        for (Type t : Type.values()) {
            if (t.getValue().equals(pTypeS)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Invalid request type '" + pTypeS + "'");
    }

    private static List<String> prepareExtraArgs(Stack<String> pElements) {
        List<String> ret = new ArrayList<String>();
        while (!pElements.isEmpty()) {
            String element = pElements.pop();
            // Check for escapes
            while (element.endsWith("\\") && !pElements.isEmpty()) {
                element = element.substring(0,element.length() - 1) + "/" + pElements.pop();
            }
            ret.add(element);
        }
        return ret;
    }

    private static void extractParameters(JmxRequest pRequest,Map<String,String[]> pParameterMap) {
        if (pParameterMap != null) {
            for (Map.Entry<String,String[]> entry : pParameterMap.entrySet()) {
                String values[] = entry.getValue();
                if (values != null && values.length > 0) {
                    pRequest.setProcessingConfig(entry.getKey(),values[0]);
                }
            }
        }
    }


    // ==================================================================================
    // Dedicated parser for the various operations. They are installed as static processors.

    private interface Processor {
        JmxRequest process(Stack<String> e)
                throws MalformedObjectNameException;
    }

    private static final Map<Type,Processor> PROCESSOR_MAP;


    static {
        PROCESSOR_MAP = new HashMap<Type, Processor>();
        PROCESSOR_MAP.put(Type.READ,new Processor() {
            public JmxRequest process(Stack<String> e) throws MalformedObjectNameException {
                JmxRequest req = new JmxRequest(Type.READ,e.pop());
                if (!e.isEmpty()) {
                    req.setAttributeName(e.pop());
                }
                return req;
            }
        });
        PROCESSOR_MAP.put(Type.WRITE,new Processor() {

            public JmxRequest process(Stack<String> e) throws MalformedObjectNameException {
                JmxRequest req = new JmxRequest(Type.WRITE,e.pop());
                req.setAttributeName(e.pop());
                req.setValue(e.pop());
                return req;
            }
        });
        PROCESSOR_MAP.put(Type.EXEC,new Processor() {
            public JmxRequest process(Stack<String> e) throws MalformedObjectNameException {
                JmxRequest req = new JmxRequest(Type.EXEC,e.pop());
                req.setOperation(e.pop());
                return req;
            }
        });
        PROCESSOR_MAP.put(Type.LIST,new Processor() {
            public JmxRequest process(Stack<String> e) throws MalformedObjectNameException {
                return new JmxRequest(Type.LIST);
            }
        });
        PROCESSOR_MAP.put(Type.VERSION,new Processor() {
            public JmxRequest process(Stack<String> e) throws MalformedObjectNameException {
                return new JmxRequest(Type.VERSION);
            }
        });

        PROCESSOR_MAP.put(Type.SEARCH,new Processor() {
            public JmxRequest process(Stack<String> e) throws MalformedObjectNameException {
                return new JmxRequest(Type.SEARCH,e.pop());
            }
        });
    }

}
