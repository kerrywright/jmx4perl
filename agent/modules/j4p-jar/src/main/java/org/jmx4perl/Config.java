package org.jmx4perl;

import java.util.HashMap;
import java.util.Map;

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
 * @author roland
 * @since Jan 1, 2010
 */
public enum Config {

    // Maximum number of history entries to keep
    HISTORY_MAX_ENTRIES("historyMaxEntries","10"),

    // Whether debug is switched on or not
    DEBUG("debug","false"),

    // Maximum number of debug entries to hold
    DEBUG_MAX_ENTRIES("debugMaxEntries","100"),

    // Dispatcher to use
    DISPATCHER_CLASSES("dispatcherClasses"),

    // Maximum traversal depth for serialization of complex objects.
    MAX_DEPTH("maxDepth",null),

    // Maximum size of collections returned during serialization.
    // If larger, the collection is truncated
    MAX_COLLECTION_SIZE("maxCollectionSize",null),

    // Maximum number of objects returned by serialization
    MAX_OBJECTS("maxObjects",null),

    // Context used for agent, used e.g. in the OSGi activator
    // (but not for the servlet, this is done in web.xml)
    AGENT_CONTEXT("agentContext","/j4p"),

    // User and password for authentication purposes.
    USER("user"),
    PASSWORD("password"),

    // Runtime configuration (i.e. must come in with a request)
    // for ignoring errors during JMX operations and JSON serialization.
    // This works only for certain operations like pattern reads.
    IGNORE_ERRORS("ignoreErrors"),

    // Maximum number of JSR160 remote connections to open to a single
    // remote MBean server
    // Default: 3
    JSR160_POOL_MAX_SIZE("jsr160MaxSize", "3"),

    // Maximum number of idle connections to leave in the pool for a
    // single remote MBean server
    // Default: 1
    JSR160_POOL_MAX_IDLE("jsr160MaxIdle", "1"),

    // The amount of time for a JSR160 connection to remain idle in
    // the connection pool before being eligible for eviction.
    // Also defines the frequency of the pool cleanup thread, which
    // will be 50% of this value, ensuring the worst case time that
    // an idle object will be in the pool is 150% of this value.
    // Default: 3s
    JSR160_POOL_IDLE_TIME("jsr160MaxIdle", "30000"),

    // The amount of time to wait on getting a pooled JSR160 connection
    // before throwing an exception
    // Default: 10s
    JSR160_POOL_MAX_WAIT_TIME("jsr160MaxWait", "10000");

    private String key;
    private String defaultValue;
    private static Map<String, Config> keyByName;

    Config(String pValue) {
        this(pValue,null);
    }

    Config(String pValue, String pDefault) {
        key = pValue;
        defaultValue = pDefault;
    }

    @Override
    public String toString() {
        return key;
    }

    public static Config getByKey(String pKeyS) {
        if (keyByName == null) {
            synchronized (Config.class) {
                keyByName = new HashMap<String, Config>();
                for (Config ck : Config.values()) {
                    keyByName.put(ck.getKeyValue(),ck);
                }
            }
        }
        return keyByName.get(pKeyS);
    }

    public String getKeyValue() {
        return key;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    // Extract value from map, including a default value if
    // value is not set
    public String getValue(Map<Config, String> pConfig) {
        String value = pConfig.get(this);
        if (value == null) {
            value = this.getDefaultValue();
        }
        return value;
    }

    // Extract value from map, including a default value if
    // value is not set and return it as an integer
    public int getIntValue(Map<Config, String> pConfig)
    {
        return Integer.parseInt(getValue(pConfig));
    }

    // Extract config options from a given map
    public static Map<Config,String> extractConfig(Map<String,String> pMap) {
        Map<Config,String> ret = new HashMap<Config, String>();
        for (Config c : Config.values()) {
            String value = pMap.get(c.getKeyValue());
            if (value != null) {
                ret.put(c,value);
            }
        }
        return ret;
    }
}
