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

package org.kantega.reststop.development;

import org.apache.maven.shared.invoker.*;
import org.apache.velocity.app.VelocityEngine;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.kantega.reststop.api.Reststop;
import org.kantega.reststop.classloaderutils.PluginInfo;
import org.kantega.reststop.pluginutils.PluginUtils;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 *
 */
public class RedeployFilter implements Filter {

    private final DevelopmentClassLoaderProvider provider;
    private final Reststop reststop;
    private volatile boolean testing = false;

    private final Object runTestsMonitor = new Object();
    private final VelocityEngine velocityEngine;
    private final boolean shouldRunTests;
    private final List<PluginInfo> pluginInfos;

    public RedeployFilter(DevelopmentClassLoaderProvider provider, Reststop reststop, VelocityEngine velocityEngine, boolean shouldRunTests, List<PluginInfo> infos) {
        this.provider = provider;
        this.reststop = reststop;
        this.velocityEngine = velocityEngine;
        this.shouldRunTests = shouldRunTests;
        pluginInfos = infos;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) servletRequest;
        HttpServletResponse resp = (HttpServletResponse) servletResponse;

        if (testing || req.getServletPath().startsWith("/assets")) {
            filterChain.doFilter(req, resp);
            return;
        }


        List<DevelopmentClassloader> staleClassLoaders = new ArrayList<>();


        synchronized (this) {
            staleClassLoaders.addAll(provider.findStaleClassLoaders());

            if (staleClassLoaders.isEmpty()) {
                filterChain.doFilter(servletRequest, servletResponse);
                return;
            }

            for (DevelopmentClassloader classloader : staleClassLoaders) {
                try {

                    for (PluginInfo info : pluginInfos) {
                        if (info.getPluginId().equals(classloader.getPluginInfo().getPluginId())) {
                            classloader = redployArtifactAndRefreshClassloader(info, classloader);
                        }
                    }

                    classloader.compileSources();
                    classloader.copySourceResources();
                    classloader.compileJavaTests();
                    classloader.copyTestResources();

                } catch (JavaCompilationException e) {
                    new ErrorReporter(velocityEngine, classloader.getBasedir()).addCompilationException(e).render(req, resp);
                    return;
                }

            }

            List<DevelopmentClassloader> newClassLoaders = new ArrayList<>();


            for (DevelopmentClassloader classloader : staleClassLoaders) {

                try {
                    newClassLoaders.add(provider.redeploy(classloader.getPluginInfo().getPluginId(), classloader));
                } catch (Exception e) {
                    classloader.setFailed(true);
                    new ErrorReporter(velocityEngine, classloader.getBasedir()).pluginLoadFailed(e, classloader).render(req, resp);
                    return;
                }


            }

            if (shouldRunTests) {
                Map<String, DevelopmentClassloader> testLoaders = new LinkedHashMap<>();

                for (DevelopmentClassloader classloader : newClassLoaders) {
                    testLoaders.put(classloader.getPluginInfo().getPluginId(), classloader);
                }

                for (DevelopmentClassloader classloader : provider.getClassloaders().values()) {

                    if (!testLoaders.containsKey(classloader.getPluginInfo().getPluginId())) {
                        boolean stale = classloader.isStaleTests();
                        if (stale) {
                            classloader.compileJavaTests();
                            classloader.copyTestResources();
                        }
                        if (stale || classloader.hasFailingTests()) {
                            testLoaders.put(classloader.getPluginInfo().getPluginId(), classloader);
                        }

                    }
                }

                for (DevelopmentClassloader classloader : testLoaders.values()) {
                    try {
                        synchronized (runTestsMonitor) {
                            if (!this.testing) {
                                try {
                                    this.testing = true;
                                    List<Class> testClasses = classloader.getTestClasses();
                                    if (testClasses.size() > 0) {
                                        Class[] objects = testClasses.toArray(new Class[testClasses.size()]);
                                        ClassLoader loader = Thread.currentThread().getContextClassLoader();
                                        Thread.currentThread().setContextClassLoader(testClasses.get(0).getClassLoader());
                                        try {


                                            Result result = new JUnitCore().run(objects);
                                            if (result.getFailureCount() > 0) {
                                                classloader.testsFailed();
                                                throw new TestFailureException(result.getFailures());
                                            } else {
                                                classloader.testsPassed();
                                            }
                                        } finally {
                                            Thread.currentThread().setContextClassLoader(loader);
                                        }
                                    }
                                } finally {
                                    this.testing = false;
                                }
                            }
                        }


                    } catch (TestFailureException e) {
                        new ErrorReporter(velocityEngine, classloader.getBasedir()).addTestFailulreException(e).render(req, resp);
                        return;
                    }

                }
            }

        }
        if (!staleClassLoaders.isEmpty()) {
            reststop.newFilterChain(filterChain).doFilter(servletRequest, servletResponse);
        } else {
            filterChain.doFilter(servletRequest, servletResponse);
        }
    }

    private DevelopmentClassloader redployArtifactAndRefreshClassloader(PluginInfo pluginInfo, DevelopmentClassloader classloader) {
        Date repoDate = pluginInfo.getSourcePomLastModified();

        File pomFile = new File(classloader.getBasedir(), "pom.xml");
        if (repoDate != null && pomFile.exists()) {
            Date sourceDate = new Date(pomFile.lastModified());
            if (sourceDate.after(repoDate)) {
                redeployArtifact(pomFile);
                PluginUtils pluginUtils = new PluginUtils();
                pluginUtils.refreshPluginInfos(pluginInfos);

                classloader = new DevelopmentClassloader(classloader, pluginInfo);
            }
        }
        return classloader;
    }

    private synchronized void redeployArtifact(File pomFile) {
        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(pomFile);
        request.setGoals(Arrays.asList("clean", "install"));

        Invoker invoker = new DefaultInvoker();
        try {
            invoker.execute(request);
        } catch (MavenInvocationException e1) {
            throw new RuntimeException(e1);
        }
    }

    @Override
    public void destroy() {

    }

}
