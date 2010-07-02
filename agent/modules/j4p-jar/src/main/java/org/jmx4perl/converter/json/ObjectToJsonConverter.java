package org.jmx4perl.converter.json;


import org.jmx4perl.config.ConfigProperty;
import org.jmx4perl.JmxRequest;
import org.jmx4perl.converter.StringToObjectConverter;
import org.jmx4perl.converter.json.simplifier.*;

import static org.jmx4perl.config.ConfigProperty.*;

import org.json.simple.JSONObject;
import javax.management.AttributeNotFoundException;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.*;

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
 * A converter which convert attribute and return values
 * into a JSON representation. It uses certain handlers for this which
 * are registered programatically in the constructor.
 *
 * Each handler gets a reference to this converter object so that it
 * can use it for a recursive solution of nested objects.
 *
 * @author roland
 * @since Apr 19, 2009
 */
public class ObjectToJsonConverter {

    // List of dedicated handlers used for delegation in serialization/deserializatin
    private List<Extractor> handlers;

    private ArrayExtractor arrayExtractor;

    // Thread-Local set in order to prevent infinite recursions
    private ThreadLocal<StackContext> stackContextLocal = new ThreadLocal<StackContext>();

    // Used for converting string to objects when setting attributes
    private StringToObjectConverter stringToObjectConverter;

    private Integer hardMaxDepth,hardMaxCollectionSize,hardMaxObjects;

    // Definition of simplifiers
    private static final String SIMPLIFIERS_DEFAULT_DEF = "/META-INF/simplifiers-default";
    private static final String SIMPLIFIERS_DEF = "META-INF/simplifiers";

    public ObjectToJsonConverter(StringToObjectConverter pStringToObjectConverter,
                                 Map<ConfigProperty,String> pConfig, Extractor... pSimplifyHandlers) {
        initLimits(pConfig);

        handlers = new ArrayList<Extractor>();

        // Collection handlers
        handlers.add(new ListExtractor());
        handlers.add(new MapExtractor());

        // Special, well known objects
        addSimplifiers(pSimplifyHandlers);

        handlers.add(new CompositeDataExtractor());
        handlers.add(new TabularDataExtractor());

        // Must be last in handlers, used default algorithm
        handlers.add(new BeanExtractor());

        arrayExtractor = new ArrayExtractor();

        stringToObjectConverter = pStringToObjectConverter;
    }


    /**
     * Convert the return value to a JSON object.
     *
     * @param pValue the value to convert
     * @param pRequest the original request
     * @param pUseValueWithPath if set, use the path given within the request to extract the inner value.
     *        Otherwise, use the path directly
     * @return the converted value
     * @throws AttributeNotFoundException if within an path an attribute could not be found
     */
    public JSONObject convertToJson(Object pValue, JmxRequest pRequest, boolean pUseValueWithPath)
            throws AttributeNotFoundException {
        Stack<String> extraStack = pUseValueWithPath ? reverseArgs(pRequest) : new Stack<String>();

        setupContext(pRequest);

        try {
            Object jsonResult = extractObject(pValue,extraStack,true);
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("value",jsonResult);
            jsonObject.put("request",pRequest.toJSON());
            return jsonObject;
        } finally {
            clearContext();
        }
    }

    /**
     * Get values for a write request. This method returns an array with two objects.
     * If no path is given (<code>pRequest.getExtraArgs() == null</code>), the returned values
     * are the new value and the old value. However, if a path is set, the returned new value
     * is the outer value (which can be set by an corresponding JMX set operation) where the
     * new value is set via the path expression. The old value is the value of the object specified
     * by the given path.
     *
     * @param pType type of the outermost object to set as returned by an MBeanInfo structure.
     * @param pCurrentValue the object of the outermost object which can be null
     * @param pRequest the initial request
     * @return object array with two elements, element 0 is the value to set (see above), element 1
     *         is the old value.
     *
     * @throws AttributeNotFoundException if no such attribute exists (as specified in the request)
     * @throws IllegalAccessException if access to MBean fails
     * @throws InvocationTargetException reflection error when setting an object's attribute
     */
    public Object[] getValues(String pType, Object pCurrentValue, JmxRequest pRequest)
            throws AttributeNotFoundException, IllegalAccessException, InvocationTargetException {
        List<String> extraArgs = pRequest.getExtraArgs();

        if (extraArgs != null && extraArgs.size() > 0) {
            if (pCurrentValue == null ) {
                throw new IllegalArgumentException(
                        "Cannot set value with path when parent object is not set");
            }

            String lastPathElement = extraArgs.remove(extraArgs.size()-1);
            Stack<String> extraStack = reverseArgs(pRequest);
            // Get the object pointed to do with path-1

            try {
                setupContext(pRequest);

                Object inner = extractObject(pCurrentValue,extraStack,false);
                // Set the attribute pointed to by the path elements
                // (depending of the parent object's type)
                Object oldValue = setObjectValue(inner,lastPathElement,pRequest.getValue());

                // We set an inner value, hence we have to return provided value itself.
                return new Object[] {
                        pCurrentValue,
                        oldValue
                };
            } finally {
                clearContext();
            }

        } else {
            // Return the objectified value
            return new Object[] {
                    stringToObjectConverter.convertFromString(pType,pRequest.getValue()),
                    pCurrentValue
            };
        }
    }

    // =================================================================================

    private void initLimits(Map<ConfigProperty, String> pConfig) {
        // Max traversal depth
        if (pConfig != null) {
            hardMaxDepth = getNullSaveIntLimit(MAX_DEPTH.getValue(pConfig));

            // Max size of collections
            hardMaxCollectionSize = getNullSaveIntLimit(MAX_COLLECTION_SIZE.getValue(pConfig));

            // Maximum of overal objects returned by one traversal.
            hardMaxObjects = getNullSaveIntLimit(MAX_OBJECTS.getValue(pConfig));
        } else {
            hardMaxDepth = getNullSaveIntLimit(MAX_DEPTH.getDefaultValue());
            hardMaxCollectionSize = getNullSaveIntLimit(MAX_COLLECTION_SIZE.getDefaultValue());
            hardMaxObjects = getNullSaveIntLimit(MAX_OBJECTS.getDefaultValue());
        }
    }

    private Integer getNullSaveIntLimit(String pValue) {
        Integer ret = pValue != null ? Integer.parseInt(pValue) : null;
        // "0" is interpreted as no limit
        return (ret != null && ret == 0) ? null : ret;
    }

    private Stack<String> reverseArgs(JmxRequest pRequest) {
        Stack<String> extraStack = new Stack<String>();
        List<String> extraArgs = pRequest.getExtraArgs();
        if (extraArgs != null) {
            // Needs first extra argument at top of the stack
            for (int i = extraArgs.size() - 1;i >=0;i--) {
                extraStack.push(extraArgs.get(i));
            }
        }
        return extraStack;
    }


    public Object extractObject(Object pValue,Stack<String> pExtraArgs,boolean pJsonify)
            throws AttributeNotFoundException {
        StackContext stackContext = stackContextLocal.get();
        String limitReached = checkForLimits(pValue,stackContext);
        if (limitReached != null) {
            return limitReached;
        }
        try {
            stackContext.push(pValue);
            stackContext.incObjectCount();

            if (pValue == null) {
                return null;
            }

            if (pValue.getClass().isArray()) {
                // Special handling for arrays
                return arrayExtractor.extractObject(this,pValue,pExtraArgs,pJsonify);
            }
            return callHandler(pValue, pExtraArgs, pJsonify);
        } finally {
            stackContext.pop();
        }
    }

    private String checkForLimits(Object pValue, StackContext pStackContext) {
        Integer maxDepth = pStackContext.getMaxDepth();
        if (maxDepth != null && pStackContext.size() > maxDepth) {
            // We use its string representation
            return pValue.toString();
        }
        if (pValue != null && pStackContext.alreadyVisited(pValue)) {
            return "[Reference " + pValue.getClass().getName() + "@" + Integer.toHexString(pValue.hashCode()) + "]";
        }
        if (exceededMaxObjects()) {
            return "[Object limit exceeded]";
        }
        return null;
    }

    private Object callHandler(Object pValue, Stack<String> pExtraArgs, boolean pJsonify)
            throws AttributeNotFoundException {
        Class pClazz = pValue.getClass();
        for (Extractor handler : handlers) {
            if (handler.getType() != null && handler.getType().isAssignableFrom(pClazz)) {
                return handler.extractObject(this,pValue,pExtraArgs,pJsonify);
            }
        }
        throw new IllegalStateException(
                "Internal error: No handler found for class " + pClazz +
                    " (object: " + pValue + ", extraArgs: " + pExtraArgs + ")");
    }

    // returns the old value
    private Object setObjectValue(Object pInner, String pAttribute, String pValue)
            throws IllegalAccessException, InvocationTargetException {

        // Call various handlers depending on the type of the inner object, as is extract Object

        Class clazz = pInner.getClass();
        if (clazz.isArray()) {
            return arrayExtractor.setObjectValue(stringToObjectConverter,pInner,pAttribute,pValue);
        }
        for (Extractor handler : handlers) {
            if (handler.getType() != null && handler.getType().isAssignableFrom(clazz) && handler.canSetValue()) {
                return handler.setObjectValue(stringToObjectConverter,pInner,pAttribute,pValue);
            }
        }

        throw new IllegalStateException(
                "Internal error: No handler found for class " + clazz + " for getting object value." +
                        " (object: " + pInner + ", attribute: " + pAttribute + ", value: " + pValue + ")");


       }

    // Used for testing only. Hence final and package local
    final ThreadLocal<StackContext> getStackContextLocal() {
        return stackContextLocal;
    }



    // Check whether JSR77 classes are available
    // Not used for the moment, but left here for reference
    /*
    private boolean knowsAboutJsr77() {
        try {
            Class.forName("javax.management.j2ee.statistics.Stats");
            // This is for Weblogic 9, which seems to have "Stats" but not the rest
            Class.forName("javax.management.j2ee.statistics.JMSStats");
            return true;
        } catch (ClassNotFoundException exp) {
            return false;
        }
    }
    */

    // =============================================================================
    // Handler interface for dedicated handler for serializing/deserializing


    // =============================================================================

    int getCollectionLength(int originalLength) {
        ObjectToJsonConverter.StackContext ctx = stackContextLocal.get();
        Integer maxSize = ctx.getMaxCollectionSize();
        if (maxSize != null && originalLength > maxSize) {
            return maxSize;
        } else {
            return originalLength;
        }
    }

    /**
     * Get the fault handler used for dealing with exceptions during value extraction.
     *
     * @return the fault handler
     */
    public JmxRequest.ValueFaultHandler getValueFaultHandler() {
        ObjectToJsonConverter.StackContext ctx = stackContextLocal.get();
        return ctx.getValueFaultHandler();
    }


    boolean exceededMaxObjects() {
        ObjectToJsonConverter.StackContext ctx = stackContextLocal.get();
        return ctx.getMaxObjects() != null && ctx.getObjectCount() > ctx.getMaxObjects();
    }

    void clearContext() {
        stackContextLocal.remove();
    }

    void setupContext(JmxRequest pRequest) {
        Integer maxDepth = getLimit(pRequest.getProcessingConfigAsInt(ConfigProperty.MAX_DEPTH),hardMaxDepth);
        Integer maxCollectionSize = getLimit(pRequest.getProcessingConfigAsInt(ConfigProperty.MAX_COLLECTION_SIZE),hardMaxCollectionSize);
        Integer maxObjects = getLimit(pRequest.getProcessingConfigAsInt(ConfigProperty.MAX_OBJECTS),hardMaxObjects);

        setupContext(maxDepth, maxCollectionSize, maxObjects, pRequest.getValueFaultHandler());
    }

    void setupContext(Integer pMaxDepth, Integer pMaxCollectionSize, Integer pMaxObjects,
                      JmxRequest.ValueFaultHandler pValueFaultHandler) {
        StackContext stackContext = new StackContext(pMaxDepth,pMaxCollectionSize,pMaxObjects,pValueFaultHandler);
        stackContextLocal.set(stackContext);
    }

    private Integer getLimit(Integer pReqValue, Integer pHardLimit) {
        if (pReqValue == null) {
            return pHardLimit;
        }
        if (pHardLimit != null) {
            return pReqValue > pHardLimit ? pHardLimit : pReqValue;
        } else {
            return pReqValue;
        }
    }


    // Simplifiers are added either explicitely or by reflection from a subpackage
    private void addSimplifiers(Extractor[] pSimplifyHandlers) {
        if (pSimplifyHandlers != null && pSimplifyHandlers.length > 0) {
            handlers.addAll(Arrays.asList(pSimplifyHandlers));
        } else {
            // Add all
            addSimplifiersFromDescriptor();
        }
    }

    // Read in default and custom defintions for simpifiers and add them to
    // our handlers
    private void addSimplifiersFromDescriptor() {
        Map<String,Extractor> extractorMap = new HashMap<String,Extractor>();
        List<Extractor> extractors = new LinkedList<Extractor>();

        readSimplifierDefinitions(extractorMap, extractors, SIMPLIFIERS_DEFAULT_DEF);
        readSimplifierDefinitions(extractorMap, extractors, SIMPLIFIERS_DEF);
        handlers.addAll(extractors);
    }

    private void readSimplifierDefinitions(Map<String, Extractor> pExtractorMap, List<Extractor> pExtractors, String pDefinition) {
        try {
            Enumeration<URL> resUrls = Thread.currentThread().getContextClassLoader().getResources(pDefinition);
            if (!resUrls.hasMoreElements()) {
                // Try to use this class classloader
                URL res = getClass().getResource(pDefinition);
                Vector vec = new Vector();
                resUrls = res != null ? new Vector<URL>(Arrays.asList(res)).elements() : new Vector<URL>().elements();
            }
            while (resUrls.hasMoreElements()) {
                readSimplifierDefinitionFromUrl(pExtractorMap, pExtractors, resUrls.nextElement());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load extractor from " + pDefinition + ": " + e,e);
        }
    }

    private void readSimplifierDefinitionFromUrl(Map<String, Extractor> pExtractorMap, List<Extractor> pExtractors, URL pUrl) {
        String line = null;
        Exception error = null;
        try {
            LineNumberReader reader = new LineNumberReader(new InputStreamReader(pUrl.openStream()));
            line = reader.readLine();
            while (line != null) {
                createOrRemoveSimplifier(pExtractorMap, pExtractors, line);
                line = reader.readLine();
            }
        } catch (ClassNotFoundException e) {
            error = e;
        } catch (InstantiationException e) {
            error = e;
        } catch (IllegalAccessException e) {
            error = e;
        } catch (ClassCastException e) {
            error = e;
        } catch (IOException e) {
            error = e;
        } finally {
            if (error != null) {
                throw new IllegalStateException("Cannot load extractor " + line + " defined in " +
                        pUrl + " : " + error + ". Aborting",error);
            }
        }
    }

    private void createOrRemoveSimplifier(Map<String, Extractor> pExtractorMap, List<Extractor> pExtractors, String pLine)
            throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        if (pLine.length() > 0) {
            if (pLine.startsWith("!")) {
                Extractor ext = pExtractorMap.remove(pLine.substring(1));
                if (ext != null) {
                    pExtractors.remove(ext);
                }
            } else {
                Class clazz = Thread.currentThread().getContextClassLoader().loadClass(pLine);
                Extractor ext = (Extractor) clazz.newInstance();
                pExtractorMap.put(pLine,ext);
                pExtractors.add(ext);
            }
        }
    }

    // =============================================================================
    // Context used for detecting call loops and the like

    private static final Set<Class> SIMPLE_TYPES = new HashSet<Class>(Arrays.asList(
            String.class,
            Number.class,
            Long.class,
            Integer.class,
            Boolean.class,
            Date.class
    ));

    static class StackContext {

        private Set objectsInCallStack = new HashSet();
        private Stack callStack = new Stack();
        private Integer maxDepth;
        private Integer maxCollectionSize;
        private Integer maxObjects;

        private int objectCount = 0;
        private JmxRequest.ValueFaultHandler valueFaultHandler;

        public StackContext(Integer pMaxDepth, Integer pMaxCollectionSize, Integer pMaxObjects, JmxRequest.ValueFaultHandler pValueFaultHandler) {
            maxDepth = pMaxDepth;
            maxCollectionSize = pMaxCollectionSize;
            maxObjects = pMaxObjects;
            valueFaultHandler = pValueFaultHandler;
        }

        void push(Object object) {
            callStack.push(object);

            if (object != null && !SIMPLE_TYPES.contains(object.getClass())) {
                objectsInCallStack.add(object);
            }
        }

        Object pop() {
            Object ret = callStack.pop();
            if (ret != null && !SIMPLE_TYPES.contains(ret.getClass())) {
                objectsInCallStack.remove(ret);
            }
            return ret;
        }

        boolean alreadyVisited(Object object) {
            return objectsInCallStack.contains(object);
        }

        int stackLevel() {
            return callStack.size();
        }

        public int size() {
            return objectsInCallStack.size();
        }

        public void setMaxDepth(Integer pMaxDepth) {
            maxDepth = pMaxDepth;
        }

        public Integer getMaxDepth() {
            return maxDepth;
        }


        public Integer getMaxCollectionSize() {
            return maxCollectionSize;
        }

        public void incObjectCount() {
            objectCount++;
        }

        public int getObjectCount() {
            return objectCount;
        }

        public Integer getMaxObjects() {
            return maxObjects;
        }

        public JmxRequest.ValueFaultHandler getValueFaultHandler() {
            return valueFaultHandler;
        }
    }



}
