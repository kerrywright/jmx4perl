package org.jmx4perl.jvmagent.jdk6;

import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import org.jmx4perl.config.ConfigProperty;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

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
 * A JVM level agent using the JDK6 HTTP Server {@link com.sun.net.httpserver.HttpServer}
 *
 * Beside the configuration defined in {@link ConfigProperty}, this agent honors the following
 * additional configuration keys:
 *
 * <ul>
 *  <li><strong>host</strong> : Host address to bind to
 *  <li><strong>port</strong> : Port to listen on
 *  <li><strong>backlog</strong> : max. nr of requests queued up before they get rejected
 *  <li><strong>config</strong> : path to a properties file containing configuration
 * </ul>
 *
 * Configuration will be also looked up from a properties file found in the class path as
 * <code>/j4p-agent.properties</code>
 *
 * All configurations will be merged in the following order with the later taking precedence:
 *
 * <ul>
 *   <li>Default properties from <code>/j4p-agent.properties<code>
 *   <li>Configuration from a config file (if given)
 *   <li>Options given on the command line in the form
 *       <code>-javaagent:agent.jar=key1=value1,key2=value2...</code>
 * </ul>
 * @author roland
 * @since Mar 3, 2010
 */
public final class JvmAgentJdk6 {

    private static final int DEFAULT_PORT = 8778;
    private static final int DEFAULT_BACKLOG = 10;
    private static final String J4P_CONTEXT = "/j4p/";

    private JvmAgentJdk6() {}

    /**
     * Entry point for the agent
     *
     * @param agentArgs arguments as given on the command line
     */
    @SuppressWarnings("PMD.SystemPrintln")
    public static void premain(String agentArgs) {
        try {
            Map<String,String> agentConfig = parseArgs(agentArgs);
            final HttpServer server = createServer(agentConfig);

            final Map<ConfigProperty,String> j4pConfig = ConfigProperty.extractConfig(agentConfig);
            final String contextPath = getContextPath(j4pConfig);

            HttpContext context = server.createContext(contextPath,new J4pHttpHandler(j4pConfig));
            if (j4pConfig.containsKey(ConfigProperty.USER)) {
                context.setAuthenticator(getAuthentiator(j4pConfig));
            }
            if (agentConfig.containsKey("executor")) {
                server.setExecutor(getExecutor(agentConfig));
            }
            startServer(server, contextPath);
        } catch (IOException e) {
            System.err.println("j4p: Cannot create HTTP-Server: " + e);
        }
    }

    private static HttpServer createServer(Map<String, String> pConfig) throws IOException {
        int port = DEFAULT_PORT;
        if (pConfig.get("port") != null) {
            port = Integer.parseInt(pConfig.get("port"));
        }
        InetAddress address;
        if (pConfig.get("host") != null) {
            address = InetAddress.getByName(pConfig.get("host"));
        } else {
            address = InetAddress.getLocalHost();
        }
        int backLog = DEFAULT_BACKLOG;
        if (pConfig.get("backlog") != null) {
            backLog = Integer.parseInt(pConfig.get("backlog"));
        }
        if (!pConfig.containsKey(ConfigProperty.AGENT_CONTEXT.getKeyValue())) {
            pConfig.put(ConfigProperty.AGENT_CONTEXT.getKeyValue(),J4P_CONTEXT);
        }
        InetSocketAddress socketAddress = new InetSocketAddress(address,port);
        return HttpServer.create(socketAddress,backLog);
    }

    @SuppressWarnings("PMD.SystemPrintln")
    private static void startServer(final HttpServer pServer, final String pContextPath) {
        ThreadGroup threadGroup = new ThreadGroup("j4p");
        threadGroup.setDaemon(false);
        // Starting server in an own thread group with a fixed name
        // so that the cleanup thread can recognize it.
        Thread starterThread = new Thread(threadGroup,new Runnable() {
            @Override
            public void run() {
                pServer.start();
                InetSocketAddress addr = pServer.getAddress();
                System.out.println("j4p: Agent URL http://" + addr.getAddress().getCanonicalHostName() + ":" +
                        addr.getPort() + pContextPath);

            }
        });
        starterThread.start();
        Thread cleaner = new CleanUpThread(pServer,threadGroup);
        cleaner.start();
    }

    private static String getContextPath(Map<ConfigProperty, String> pJ4pConfig) {
        String context = pJ4pConfig.get(ConfigProperty.AGENT_CONTEXT);
        if (context == null) {
            context = ConfigProperty.AGENT_CONTEXT.getDefaultValue();
        }
        if (!context.endsWith("/")) {
            context += "/";
        }
        return context;
    }

    @SuppressWarnings("PMD.SystemPrintln")
    private static Map<String, String> parseArgs(String pAgentArgs) {
        Map<String,String> ret = new HashMap<String, String>();
        if (pAgentArgs != null && pAgentArgs.length() > 0) {
            for (String arg : pAgentArgs.split(",")) {
                String[] prop = arg.split("=");
                if (prop == null || prop.length != 2) {
                    System.err.println("j4p: Invalid option '" + arg + "'. Ignoring");
                } else {
                    ret.put(prop[0],prop[1]);
                }
            }
        }
        Map<String,String> config = getDefaultConfig();
        if (ret.containsKey("config")) {
            Map<String,String> userConfig = readConfig(ret.get("config"));
            config.putAll(userConfig);
            config.putAll(ret);
            return config;
        } else {
            config.putAll(ret);
            return config;
        }
    }

    @SuppressWarnings("PMD.SystemPrintln")
    private static Map<String, String> readConfig(String pFilename) {
        File file = new File(pFilename);
        try {
            InputStream is = new FileInputStream(file);
            return readPropertiesFromInputStream(is,pFilename);
        } catch (FileNotFoundException e) {
            System.err.println("j4p: Configuration file " + pFilename + " does not exist");
            return new HashMap<String, String>();
        }
    }

    private static Map<String, String> getDefaultConfig() {
        InputStream is =
                Thread.currentThread().getContextClassLoader().getResourceAsStream("j4p-agent.properties");
        return readPropertiesFromInputStream(is,"j4p-agent.properties");
    }

    @SuppressWarnings("PMD.SystemPrintln")
    private static Map<String, String> readPropertiesFromInputStream(InputStream pIs,String pLabel) {
        Map ret = new HashMap<String, String>();
        if (pIs == null) {
            return ret;
        }
        Properties props = new Properties();
        try {
            props.load(pIs);
            ret.putAll(props);
        } catch (IOException e) {
            System.err.println("j4p: Cannot load default properties " + pLabel + " : " + e);
        }
        return ret;
    }

    @SuppressWarnings("PMD.SystemPrintln")
    private static Executor getExecutor(Map<String,String> pConfig) {
        String executor = pConfig.get("executor");
        if ("fixed".equalsIgnoreCase(executor)) {
            String nrS = pConfig.get("threadNr");
            int threads = 5;
            if (nrS != null) {
                threads = Integer.parseInt(nrS);
            }
            return Executors.newFixedThreadPool(threads);
        } else if ("cached".equalsIgnoreCase(executor)) {
            return Executors.newCachedThreadPool();
        } else {
            if (!"single".equalsIgnoreCase(executor)) {
                System.err.println("j4p: Unknown executor '" + executor + "'. Using a single thread");
            }
            return Executors.newSingleThreadExecutor();
        }
    }


    private static Authenticator getAuthentiator(Map<ConfigProperty, String> pJ4pConfig) {
        final String user = pJ4pConfig.get(ConfigProperty.USER);
        final String password = pJ4pConfig.get(ConfigProperty.PASSWORD);
        if (user == null || password == null) {
            throw new SecurityException("No user and/or password given: user = " + user +
                    ", password = " + (password != null ? "(set)" : "null"));
        }
        return new BasicAuthenticator("j4p") {
            @Override
            public boolean checkCredentials(String pUserGiven, String pPasswordGiven) {
                return user.equals(pUserGiven) && password.equals(pPasswordGiven);
            }
        };
    }
}
