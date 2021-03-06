/*
 * Copyright 2015 Kantega AS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kantega.reststop.jetty;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.kantega.reststop.api.*;
import org.kantega.reststop.servlets.ReststopInitializer;

import javax.annotation.PreDestroy;
import javax.servlet.DispatcherType;
import javax.servlet.ServletContext;
import java.util.EnumSet;

/**
 *
 */
@Plugin
public class JettyPlugin {

    @Export final ServletBuilder servletBuilder;
    @Export final ServletContext servletContext;
    private final Server server;

    public JettyPlugin(ReststopPluginManager pluginManager, @Config(defaultValue = "8080") int jettyPort)throws Exception {

        server = new Server(jettyPort);

        ServletContextHandler handler = new ServletContextHandler();


        handler.addFilter(new FilterHolder(new ReststopInitializer.PluginDelegatingFilter(pluginManager)), "/*", EnumSet.of(DispatcherType.REQUEST));
        server.setHandler(handler);

        try {
            server.start();
        } catch (Exception e) {
            server.stop();
            throw e;
        }

        servletContext =  handler.getServletContext();

        ReststopInitializer.DefaultServletBuilder defaultServletBuilder = new ReststopInitializer.DefaultServletBuilder(servletContext);
        defaultServletBuilder.setManager(pluginManager);
        servletBuilder = defaultServletBuilder;
    }

    @PreDestroy
    public void stop() throws Exception {
        if(server != null && !server.isStopped()) {
            server.stop();
        }
    }
}
