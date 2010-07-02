package org.jmx4perl.backend;

import org.jmx4perl.*;
import org.jmx4perl.config.*;
import org.jmx4perl.converter.StringToObjectConverter;
import org.jmx4perl.converter.json.ObjectToJsonConverter;
import org.jmx4perl.history.HistoryStore;
import org.jmx4perl.LogHandler;
import org.json.simple.JSONObject;

import javax.management.*;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.jmx4perl.config.ConfigProperty.*;

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
 * Backendmanager for dispatching to various backends based on a given
 * {@link org.jmx4perl.JmxRequest}
 *
 * @author roland
 * @since Nov 11, 2009
 */
public class BackendManager {

    private LocalRequestDispatcher localDispatcher;

    // Converter for converting various attribute object types
    // a JSON representation
    private ObjectToJsonConverter objectToJsonConverter;

    // Handling access restrictions
    private Restrictor restrictor;

    // History handler
    private HistoryStore historyStore;

    // Storage for storing debug information
    private DebugStore debugStore;

    // MBean used for configuration
    private ObjectName configMBeanName;

    // Loghandler for dispatching logs
    private LogHandler logHandler;

    // List of RequestDispatchers to consult
    private List<RequestDispatcher> requestDispatchers;

    public BackendManager(Map<ConfigProperty,String> pConfig, LogHandler pLogHandler) {


        // Central objects
        StringToObjectConverter stringToObjectConverter = new StringToObjectConverter();
        objectToJsonConverter = new ObjectToJsonConverter(stringToObjectConverter,pConfig);

        // Access restrictor
        restrictor = RestrictorFactory.buildRestrictor(pLogHandler);

        // Log handler for putting out debug
        logHandler = pLogHandler;

        // Create and remember request dispatchers
        localDispatcher = new LocalRequestDispatcher(objectToJsonConverter,
                                                     stringToObjectConverter,
                                                     restrictor);
        requestDispatchers = createRequestDispatchers(pConfig,
                                                      objectToJsonConverter,stringToObjectConverter,restrictor);
        requestDispatchers.add(localDispatcher);

        // Backendstore for remembering state
        initStores(pConfig);
        registerOwnMBeans();
    }

    // Construct configured dispatchers by reflection. Returns always
    // a list, an empty one if no request dispatcher should be created
    private List<RequestDispatcher> createRequestDispatchers(Map<ConfigProperty, String> pConfig,
                                                             ObjectToJsonConverter pObjectToJsonConverter,
                                                             StringToObjectConverter pStringToObjectConverter,
                                                             Restrictor pRestrictor) {
        String pClasses = DISPATCHER_CLASSES.getValue(pConfig);
        List<RequestDispatcher> ret = new ArrayList<RequestDispatcher>();
        if (pClasses != null && pClasses.length() > 0) {
            String[] names = pClasses.split("\\s*,\\s*");
            for (String name : names) {
                ret.add(createDispatcher(name, pObjectToJsonConverter, pStringToObjectConverter, pRestrictor, pConfig));
            }
        }
        return ret;
    }

    // Create a single dispatcher
    private RequestDispatcher createDispatcher(String pDispatcherClass, ObjectToJsonConverter pObjectToJsonConverter, StringToObjectConverter pStringToObjectConverter, Restrictor pRestrictor, Map<ConfigProperty, String> pConfig) {
        try {
            Class clazz = this.getClass().getClassLoader().loadClass(pDispatcherClass);
            try {
                Constructor constructor = clazz.getConstructor(ObjectToJsonConverter.class,
                                                               StringToObjectConverter.class,
                                                               Restrictor.class,
                                                               Map.class);
                return (RequestDispatcher)
                                constructor.newInstance(pObjectToJsonConverter,
                                                    pStringToObjectConverter,
                                                    pRestrictor,
                                                    pConfig);
            } catch (NoSuchMethodException nsme) {
                Constructor constructor = clazz.getConstructor(ObjectToJsonConverter.class,
                                                               StringToObjectConverter.class,
                                                               Restrictor.class);
                return (RequestDispatcher)
                            constructor.newInstance(pObjectToJsonConverter,
                                                    pStringToObjectConverter,
                                                    pRestrictor);
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Couldn't load class " + pDispatcherClass + ": " + e,e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Class " + pDispatcherClass + " has invalid constructor: " + e,e);
        } catch (IllegalAccessException e) {
        throw new IllegalArgumentException("Constructor of " + pDispatcherClass + " couldn't be accessed: " + e,e);
        } catch (InvocationTargetException e) {
            throw new IllegalArgumentException(e);
        } catch (InstantiationException e) {
            throw new IllegalArgumentException(pDispatcherClass + " couldn't be instantiated: " + e,e);
        }
    }

    /**
     * Handle a single JMXRequest. The response status is set to 200 if the request
     * was successful
     *
     * @param pJmxReq request to perform
     * @return the already converted answer.
     * @throws InstanceNotFoundException
     * @throws AttributeNotFoundException
     * @throws ReflectionException
     * @throws MBeanException
     */
    public JSONObject handleRequest(JmxRequest pJmxReq) throws InstanceNotFoundException, AttributeNotFoundException,
            ReflectionException, MBeanException, IOException {

        boolean debug = isDebug() && !"debugInfo".equals(pJmxReq.getOperation());

        long time = 0;
        if (debug) {
            time = System.currentTimeMillis();
        }
        JSONObject json = callRequestDispatcher(pJmxReq);

        // Update global history store
        historyStore.updateAndAdd(pJmxReq,json);
        if (debug) {
            debug("Execution time: " + (System.currentTimeMillis() - time) + " ms");
            debug("Response: " + json);
        }
        // Ok, we did it ...
        json.put("status",200 /* success */);
        return json;
    }

    // call the an appropriate request dispatcher
    private JSONObject callRequestDispatcher(JmxRequest pJmxReq)
            throws InstanceNotFoundException, AttributeNotFoundException, ReflectionException, MBeanException, IOException {
        Object retValue = null;
        boolean useValueWithPath = false;
        boolean found = false;
        for (RequestDispatcher dispatcher : requestDispatchers) {
            if (dispatcher.canHandle(pJmxReq)) {
                retValue = dispatcher.dispatchRequest(pJmxReq);
                useValueWithPath = dispatcher.useReturnValueWithPath(pJmxReq);
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalStateException("Internal error: No dispatcher found for handling " + pJmxReq);
        }
        return objectToJsonConverter.convertToJson(retValue,pJmxReq,useValueWithPath);
    }

    // init various application wide stores for handling history and debug output.
    private void initStores(Map<ConfigProperty, String> pConfig) {
        int maxEntries;
        try {
            maxEntries = Integer.parseInt(HISTORY_MAX_ENTRIES.getValue(pConfig));
        } catch (NumberFormatException exp) {
            maxEntries = Integer.parseInt(HISTORY_MAX_ENTRIES.getDefaultValue());
        }

        String doDebug = DEBUG.getValue(pConfig);
        boolean debug = false;
        if (doDebug != null && Boolean.valueOf(doDebug)) {
            debug = true;
        }

        int maxDebugEntries;
        try {
            maxDebugEntries = Integer.parseInt(DEBUG_MAX_ENTRIES.getValue(pConfig));
        } catch (NumberFormatException exp) {
            maxDebugEntries = Integer.parseInt(DEBUG_MAX_ENTRIES.getDefaultValue());
        }

        historyStore = new HistoryStore(maxEntries);
        debugStore = new DebugStore(maxDebugEntries,debug);
    }

    private void registerOwnMBeans() {
        try {
            configMBeanName = localDispatcher.registerConfigMBean(historyStore,debugStore);
        } catch (NotCompliantMBeanException e) {
            error("Error registering config MBean: " + e,e);
        } catch (MBeanRegistrationException e) {
            error("Cannot register MBean: " + e,e);
        } catch (MalformedObjectNameException e) {
            error("Invalid name for config MBean: " + e,e);
        } catch (InstanceAlreadyExistsException e) {
            error("Config MBean already exists: " + e,e);
        }
    }

    // Remove MBeans again.
    public void unregisterOwnMBeans() {
        if (configMBeanName != null) {
            try {
                localDispatcher.unregisterLocalMBean(configMBeanName);
            } catch (MalformedObjectNameException e) {
                // wont happen
                error("Invalid name for config MBean: " + e,e);
            } catch (InstanceNotFoundException e) {
                error("No Mbean registered with name " + configMBeanName + ": " + e,e);
            } catch (MBeanRegistrationException e) {
                error("Cannot unregister MBean: " + e,e);
            }
        } else {
            error("Internal Problem: No ConfigMBean name !",null);
        }
    }


    public boolean isRemoteAccessAllowed(String pRemoteHost, String pRemoteAddr) {
        return restrictor.isRemoteAccessAllowed(pRemoteHost,pRemoteAddr);
    }

    public void info(String msg) {
        logHandler.info(msg);
        if (debugStore != null) {
            debugStore.log(msg);
        }
    }

    public void debug(String msg) {
        logHandler.debug(msg);
        if (debugStore != null) {
            debugStore.log(msg);
        }
    }

    public final void error(String message, Throwable t) {
        logHandler.error(message,t);
        if (debugStore != null) {
            debugStore.log(message, t);
        }
    }

    public boolean isDebug() {
        return debugStore != null && debugStore.isDebug();
    }

    /**
     * Set the log handler used for log handling
     *
     * @param pLogHandler log handler to use
     */
    public void setLogHandler(LogHandler pLogHandler) {
        logHandler = pLogHandler;
    }


}
