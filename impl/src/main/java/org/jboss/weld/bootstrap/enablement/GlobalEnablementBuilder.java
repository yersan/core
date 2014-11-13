/*
 * JBoss, Home of Professional Open Source
 * Copyright 2013, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.weld.bootstrap.enablement;

import static com.google.common.collect.Lists.transform;
import static org.jboss.weld.util.reflection.Reflections.cast;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.jboss.weld.bootstrap.BeanDeployment;
import org.jboss.weld.bootstrap.api.helpers.AbstractBootstrapService;
import org.jboss.weld.bootstrap.spi.BeansXml;
import org.jboss.weld.bootstrap.spi.Metadata;
import org.jboss.weld.exceptions.DeploymentException;
import org.jboss.weld.logging.BootstrapLogger;
import org.jboss.weld.logging.MessageCallback;
import org.jboss.weld.logging.ValidatorLogger;
import org.jboss.weld.resources.spi.ResourceLoader;
import org.jboss.weld.resources.spi.ResourceLoadingException;
import org.jboss.weld.util.Preconditions;
import org.jboss.weld.util.collections.ListView;
import org.jboss.weld.util.collections.ViewProvider;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/**
 * This service gathers globally enabled interceptors, decorators and alternatives and builds a list of each.
 *
 * @author Jozef Hartinger
 *
 */
public class GlobalEnablementBuilder extends AbstractBootstrapService {

    private static class Item implements Comparable<Item> {

        private final Class<?> javaClass;

        private final Integer priority;

        private Item(Class<?> javaClass) {
            this(javaClass, null);
        }

        private Item(Class<?> javaClass, Integer priority) {
            Preconditions.checkArgumentNotNull(javaClass, "javaClass");
            this.javaClass = javaClass;
            this.priority = priority;
        }

        @Override
        public int compareTo(Item o) {
            if (priority.equals(o.priority)) {
                /*
                 * The spec does not specify what happens if two records have the same priority. Instead of giving random
                 * results, we compare the records based on their class name lexicographically.
                 */
                return javaClass.getName().compareTo(o.javaClass.getName());
            }
            return priority - o.priority;
        }

        @Override
        public int hashCode() {
            return javaClass.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof Item) {
                Item that = (Item) obj;
                return Objects.equal(javaClass, that.javaClass);
            }
            return false;
        }

        @Override
        public String toString() {
            return "[Class=" + javaClass + ", priority=" + priority + "]";
        }
    }

    private static class ItemViewProvider implements ViewProvider<Item, Class<?>> {

        private static ItemViewProvider ITEM_VIEW_PROVIDER = new ItemViewProvider();

        @Override
        public Class<?> toView(Item item) {
            return item.javaClass;
        }

        @Override
        public Item fromView(Class<?> javaClass) {
            return new Item(javaClass);
        }
    }

    private abstract static class AbstractEnablementListView extends ListView<Item, Class<?>> {

        @Override
        protected ViewProvider<Item, Class<?>> getViewProvider() {
            return ItemViewProvider.ITEM_VIEW_PROVIDER;
        }
    }

    private final List<Item> alternatives = Collections.synchronizedList(new ArrayList<Item>());
    private final List<Item> interceptors = Collections.synchronizedList(new ArrayList<Item>());
    private final List<Item> decorators = Collections.synchronizedList(new ArrayList<Item>());

    private volatile Map<Class<?>, Integer> cachedAlternativeMap;
    private volatile boolean sorted;

    private void addItem(List<Item> list, Class<?> javaClass, int priority) {
        list.add(new Item(javaClass, priority));
    }

    public void addAlternative(Class<?> javaClass, int priority) {
        addItem(alternatives, javaClass, priority);
    }

    public void addInterceptor(Class<?> javaClass, int priority) {
        addItem(interceptors, javaClass, priority);
    }

    public void addDecorator(Class<?> javaClass, int priority) {
        addItem(decorators, javaClass, priority);
    }

    public List<Class<?>> getAlternativeList() {
        initialize();
        return new AbstractEnablementListView() {
            @Override
            protected List<Item> getDelegate() {
                return alternatives;
            }
        };
    }

    public List<Class<?>> getInterceptorList() {
        initialize();
        return new AbstractEnablementListView() {
            @Override
            protected List<Item> getDelegate() {
                return interceptors;
            }
        };
    }

    public List<Class<?>> getDecoratorList() {
        initialize();
        return new AbstractEnablementListView() {
            @Override
            protected List<Item> getDelegate() {
                return decorators;
            }
        };
    }

    /*
     * cachedAlternativeMap is accessed from a single thread only and the result is safely propagated. Therefore, there is no need to synchronize access to
     * cachedAlternativeMap.
     */
    private Map<Class<?>, Integer> getGlobalAlternativeMap() {
        if (cachedAlternativeMap == null) {
            Map<Class<?>, Integer> map = new HashMap<Class<?>, Integer>();
            for (ListIterator<Item> iterator = alternatives.listIterator(); iterator.hasNext();) {
                Item item = iterator.next();
                map.put(item.javaClass, iterator.previousIndex());
            }
            cachedAlternativeMap = ImmutableMap.copyOf(map);
        }
        return cachedAlternativeMap;
    }

    private void initialize() {
        if (!sorted) {
            Collections.sort(alternatives);
            Collections.sort(interceptors);
            Collections.sort(decorators);
            sorted = true;
        }
    }

    public ModuleEnablement createModuleEnablement(BeanDeployment deployment) {

        ClassLoader loader = new ClassLoader(deployment.getBeanManager().getServices().get(ResourceLoader.class));

        BeansXml beansXml = deployment.getBeanDeploymentArchive().getBeansXml();

        ImmutableList.Builder<Class<?>> moduleInterceptorsBuilder = ImmutableList.<Class<?>> builder();
        ImmutableList.Builder<Class<?>> moduleDecoratorsBuilder = ImmutableList.<Class<?>> builder();

        Set<Class<?>> alternativeClasses = null;
        Set<Class<? extends Annotation>> alternativeStereotypes = null;

        moduleInterceptorsBuilder.addAll(getInterceptorList());
        moduleDecoratorsBuilder.addAll(getDecoratorList());

        if (beansXml != null) {

            List<Class<?>> localInterceptors = transform(checkForDuplicates(beansXml.getEnabledInterceptors(), ValidatorLogger.INTERCEPTOR_SPECIFIED_TWICE), loader);
            moduleInterceptorsBuilder.addAll(localInterceptors);

            List<Class<?>> localDecorators = transform(checkForDuplicates(beansXml.getEnabledDecorators(), ValidatorLogger.DECORATOR_SPECIFIED_TWICE), loader);
            moduleDecoratorsBuilder.addAll(localDecorators);

            alternativeClasses = ImmutableSet.copyOf(transform(checkForDuplicates(beansXml.getEnabledAlternativeClasses(), ValidatorLogger.ALTERNATIVE_CLASS_SPECIFIED_MULTIPLE_TIMES), loader));
            alternativeStereotypes = cast(ImmutableSet.copyOf(transform(checkForDuplicates(beansXml.getEnabledAlternativeStereotypes(), ValidatorLogger.ALTERNATIVE_STEREOTYPE_SPECIFIED_MULTIPLE_TIMES), loader)));

        } else {
            alternativeClasses = Collections.emptySet();
            alternativeStereotypes = Collections.emptySet();
        }

        Map<Class<?>, Integer> globalAlternatives = getGlobalAlternativeMap();

        return new ModuleEnablement(moduleInterceptorsBuilder.build(), moduleDecoratorsBuilder.build(), globalAlternatives, alternativeClasses, alternativeStereotypes);
    }

    private static <T> List<Metadata<T>> checkForDuplicates(List<Metadata<T>> list, MessageCallback<DeploymentException> messageCallback) {
        Map<T, Metadata<T>> map = new HashMap<T, Metadata<T>>();
        for (Metadata<T> item : list) {
            Metadata<T> previousOccurrence = map.put(item.getValue(), item);
            if (previousOccurrence != null) {
                throw messageCallback.construct(item.getValue(), item, previousOccurrence);
            }
        }
        return list;
    }

    private static class ClassLoader implements Function<Metadata<String>, Class<?>> {

        private final ResourceLoader resourceLoader;

        public ClassLoader(ResourceLoader resourceLoader) {
            this.resourceLoader = resourceLoader;
        }

        @Override
        public Class<?> apply(Metadata<String> from) {
            try {
                return resourceLoader.classForName(from.getValue());
            } catch (ResourceLoadingException e) {
                throw BootstrapLogger.LOG.errorLoadingBeansXmlEntry(from.getValue(), from.getLocation(), e.getCause());
            } catch (Exception e) {
                throw BootstrapLogger.LOG.errorLoadingBeansXmlEntry(from.getValue(), from.getLocation(), e);
            }
        }
    }

    @Override
    public void cleanupAfterBoot() {
        alternatives.clear();
        interceptors.clear();
        decorators.clear();
    }

    @Override
    public String toString() {
        return "GlobalEnablementBuilder [alternatives=" + alternatives + ", interceptors=" + interceptors + ", decorators=" + decorators + "]";
    }
}
