/*
 *  Copyright 2024 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.backend.wasm.gc.vtable;

import com.carrotsearch.hppc.ObjectIntHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.teavm.common.LCATree;
import org.teavm.model.ClassReader;
import org.teavm.model.ElementModifier;
import org.teavm.model.ListableClassReaderSource;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodReference;

class WasmGCVirtualTableBuilder {
    private static final MethodReference CLONE_METHOD = new MethodReference(Object.class, "clone", Object.class);
    ListableClassReaderSource classes;
    Collection<MethodReference> methodsAtCallSites;
    Predicate<MethodReference> isVirtual;
    private Map<String, Set<MethodDescriptor>> groupedMethodsAtCallSites = new HashMap<>();
    private List<Table> tables = new ArrayList<>();
    private Map<String, Table> tableMap = new HashMap<>();
    private LCATree lcaTree;
    Map<String, WasmGCVirtualTable> result = new HashMap<>();

    void build() {
        initTables();
        buildLCA();
        initInterfaceTables();
        fillInterfaceImplementors();
        mergeTrivialInterfaces();
        liftInterfaces();
        buildInterfacesHierarchy();
        groupMethodsFromCallSites();
        moveClassesToMergedTables();
        fillTables();
        buildResult();
    }

    private void initTables() {
        for (var className : classes.getClassNames()) {
            initTable(className);
        }
    }

    private void initTable(String className) {
        var cls = classes.get(className);
        if (!cls.hasModifier(ElementModifier.INTERFACE) && !tableMap.containsKey(className)) {
            if (cls.getParent() != null) {
                initTable(cls.getParent());
            }
            var table = new Table(cls, tables.size());
            if (cls.getParent() != null) {
                table.parent = tableMap.get(cls.getParent());
            }
            tables.add(table);
            tableMap.put(className, table);
        }
    }

    private void buildLCA() {
        lcaTree = new LCATree(tables.size() + 1);
        for (var i = 0; i < tables.size(); i++) {
            var table = tables.get(i);
            var parentTable = table.cls.getParent() != null ? tableMap.get(table.cls.getParent()) : null;
            lcaTree.addNode(parentTable != null ? parentTable.index + 1 : 0);
        }
    }

    private void initInterfaceTables() {
        for (var className : classes.getClassNames()) {
            var cls = classes.get(className);
            if (cls.hasModifier(ElementModifier.INTERFACE)) {
                var table = new Table(cls, tables.size());
                tables.add(table);
                tableMap.put(className, table);
            }
        }
    }

    private void fillInterfaceImplementors() {
        for (var table : tables) {
            if (!table.cls.hasModifier(ElementModifier.INTERFACE)) {
                var visited = new HashSet<String>();
                do {
                    for (var itfName : table.cls.getInterfaces()) {
                        addImplementorToInterface(itfName, table, visited);
                    }
                    table = table.cls.getParent() != null ? tableMap.get(table.cls.getParent()) : null;
                } while (table != null);
            }
        }
    }

    private void addImplementorToInterface(String interfaceName, Table newImplementor, Set<String> visited) {
        if (!visited.add(interfaceName)) {
            return;
        }
        var itf = tableMap.get(interfaceName);
        if (!itf.commonImplementorFilled) {
            itf.commonImplementorFilled = true;
            itf.commonImplementor = newImplementor.parent;
        } else if (itf.commonImplementor != null) {
            var lcaIndex = lcaTree.lcaOf(newImplementor.index + 1, itf.commonImplementor.index + 1);
            if (lcaIndex > 0) {
                itf.commonImplementor = tables.get(lcaIndex - 1);
                if (itf.commonImplementor == newImplementor) {
                    itf.commonImplementor = newImplementor.parent;
                }
            } else {
                itf.commonImplementor = null;
            }
        }
        var cls = classes.get(interfaceName);
        if (cls != null) {
            for (var superInterface : cls.getInterfaces()) {
                addImplementorToInterface(superInterface, newImplementor, visited);
            }
        }
    }

    private void mergeTrivialInterfaces() {
        for (var table : tables) {
            if (table.cls.hasModifier(ElementModifier.INTERFACE)) {
                if (table.cls.getInterfaces().isEmpty() && table.cls.getMethods().isEmpty()
                        && table.commonImplementor != null) {
                    table.interfaceMergedIntoClass = true;
                    table.commonImplementor.merge(table);
                }
            }
        }
    }

    private void liftInterfaces() {
        for (var table : tables) {
            if (table.cls.hasModifier(ElementModifier.INTERFACE) || table.cls.getInterfaces().isEmpty()) {
                continue;
            }
            var accumulatedInterfaces = new LinkedHashSet<Table>();
            for (var itfName : table.cls.getInterfaces()) {
                var itf = tableMap.get(itfName);
                if (itf != null && !itf.interfaceMergedIntoClass) {
                    accumulatedInterfaces.add(itf);
                }
            }
            while (table != null) {
                if (table.liftedInterfaces == null) {
                    table.liftedInterfaces = new LinkedHashSet<>();
                } else {
                    accumulatedInterfaces.removeAll(table.liftedInterfaces);
                }
                if (accumulatedInterfaces.isEmpty()) {
                    break;
                }
                table.liftedInterfaces.addAll(accumulatedInterfaces);
                var parent = table.parent;
                accumulatedInterfaces.removeIf(itf -> itf.commonImplementor == parent);
                if (accumulatedInterfaces.isEmpty()) {
                    break;
                }
                table = parent;
            }
        }
        for (var table : tables) {
            if (table.liftedInterfaces != null) {
                table.liftedInterfaces.removeIf(itf -> itf.commonImplementor != table.parent);
                if (table.liftedInterfaces.isEmpty()) {
                    table.liftedInterfaces = null;
                }
            }
        }
    }

    private void buildInterfacesHierarchy() {
        for (var table : tables) {
            if (table.cls.hasModifier(ElementModifier.INTERFACE)) {
                setUpInterfaceInHierarchy(table, table.commonImplementor);
            } else if (table.liftedInterfaces != null) {
                setUpInterfaceInHierarchy(table, table.parent);
            }
        }
    }

    private void setUpInterfaceInHierarchy(Table table, Table parent) {
        if (table.visited) {
            return;
        }
        table.visited = true;
        var interfaces = new LinkedHashSet<Table>();
        if (table.liftedInterfaces != null) {
            for (var itf : table.liftedInterfaces) {
                itf = itf.resolve();
                if (itf.commonImplementor == parent && !itf.interfaceMergedIntoClass) {
                    setUpInterfaceInHierarchy(itf, parent);
                    interfaces.add(itf.resolve());
                }
            }
        } else {
            for (var itfName : table.cls.getInterfaces()) {
                var itf = tableMap.get(itfName);
                if (itf == null) {
                    continue;
                }
                itf = itf.resolve();
                if (itf.commonImplementor == parent && !itf.interfaceMergedIntoClass) {
                    setUpInterfaceInHierarchy(itf, parent);
                    interfaces.add(itf.resolve());
                }
            }
        }
        if (interfaces.isEmpty()) {
            table.parent = parent;
        } else {
            var visited = new HashSet<Table>();
            for (var itf : interfaces.toArray(new Table[0])) {
                findDirectlyImplementedInterfaces(visited, itf, parent, 1, interfaces);
            }
            var finalInterfaces = interfaces.stream()
                    .map(Table::resolve)
                    .distinct()
                    .collect(Collectors.toList());
            var singleExample = finalInterfaces.get(0);
            for (var i = 1; i < finalInterfaces.size(); i++) {
                singleExample.merge(finalInterfaces.get(i));
            }
            table.parent = singleExample;
        }
    }

    private void findDirectlyImplementedInterfaces(Set<Table> visited, Table table, Table parentTable,
            int level, Set<Table> result) {
        if (!visited.add(table)) {
            return;
        }
        if (level > 1) {
            result.remove(table);
        }
        for (var itf : table.cls.getInterfaces()) {
            var itfTable = tableMap.get(itf);
            if (itfTable == null) {
                continue;
            }
            itfTable = itfTable.resolve();
            if (itfTable.commonImplementor == parentTable && !itfTable.interfaceMergedIntoClass) {
                findDirectlyImplementedInterfaces(visited, itfTable, parentTable, level + 1, result);
            }
        }
    }

    private void groupMethodsFromCallSites() {
        for (var methodRef : methodsAtCallSites) {
            var className = mapClassName(methodRef.getClassName());
            var group = groupedMethodsAtCallSites.computeIfAbsent(className, k -> new LinkedHashSet<>());
            group.add(methodRef.getDescriptor());
        }
    }

    private String mapClassName(String name) {
        var table = tableMap.get(name);
        if (table == null) {
            return name;
        }
        return table.resolve().cls.getName();
    }

    private void moveClassesToMergedTables() {
        for (var table : tables) {
            if (table.parent != null) {
                table.parent = table.parent.resolve();
            }
            var resolvedTable = table.resolve();
            if (resolvedTable != table) {
                if (resolvedTable.mergedClasses == null) {
                    resolvedTable.mergedClasses = new ArrayList<>();
                }
                resolvedTable.mergedClasses.add(table.cls);
            }
        }
    }

    private void fillTables() {
        for (var className : classes.getClassNames()) {
            var table = tableMap.get(className);
            if (table != null) {
                fillTable(table.resolve());
            }
        }
    }

    private void fillTable(Table table) {
        if (table.filled) {
            return;
        }
        table.filled = true;
        var parent = table.parent;
        var indexes = new ObjectIntHashMap<MethodDescriptor>();
        if (parent != null) {
            fillTable(parent);
            table.entries.addAll(parent.entries);
            table.implementors.addAll(parent.implementors);
            for (var entry : table.entries) {
                indexes.put(entry.method, entry.index);
            }
            table.currentImplementors.putAll(parent.currentImplementors);
            table.interfaces.addAll(parent.interfaces);
        } else {
            table.used = true;
        }

        var classes = new ArrayList<ClassReader>();
        classes.add(table.cls);
        if (table.mergedClasses != null) {
            classes.addAll(table.mergedClasses);
        }
        for (var cls : classes) {
            for (var method : cls.getMethods()) {
                if (!method.hasModifier(ElementModifier.STATIC) && !method.hasModifier(ElementModifier.ABSTRACT)) {
                    if (method.getProgram() == null && !method.hasModifier(ElementModifier.NATIVE)) {
                        continue;
                    }
                    if (!isVirtual.test(method.getReference()) && !method.getReference().equals(CLONE_METHOD)) {
                        continue;
                    }
                    table.currentImplementors.put(method.getDescriptor(), method.getReference());
                }
            }
        }

        for (var itfName : table.cls.getInterfaces()) {
            fillFromInterfaces(itfName, table);
        }

        var group = groupedMethodsAtCallSites.get(table.cls.getName());
        if (group != null) {
            table.used = true;
            for (var method : group) {
                if (indexes.getOrDefault(method, -1) < 0) {
                    var entry = new Entry(method, table, table.entries.size());
                    table.entries.add(entry);
                    indexes.put(method, entry.index);
                    table.implementors.add(null);
                }
            }
        }
        for (var entry : indexes) {
            var implementor = table.currentImplementors.get(entry.key);
            table.implementors.set(entry.value, implementor);
        }
    }

    private void fillFromInterfaces(String itfName, Table table) {
        if (!table.interfaces.add(itfName)) {
            return;
        }
        var cls = classes.get(itfName);
        if (cls == null) {
            return;
        }
        for (var method : cls.getMethods()) {
            if (!method.hasModifier(ElementModifier.STATIC) && !method.hasModifier(ElementModifier.ABSTRACT)) {
                if (method.getProgram() == null && !method.hasModifier(ElementModifier.NATIVE)) {
                    continue;
                }
                if (!isVirtual.test(method.getReference())) {
                    continue;
                }
                if (table.currentImplementors.get(method.getDescriptor()) == null) {
                    table.currentImplementors.put(method.getDescriptor(), method.getReference());
                }
            }
        }
        for (var superItf : cls.getInterfaces()) {
            fillFromInterfaces(superItf, table);
        }
    }

    private void buildResult() {
        for (var table : tables) {
            result.put(table.cls.getName(), table.getBuildResult());
        }
    }

    private static class Table {
        boolean visited;
        boolean keep;
        Set<Table> liftedInterfaces;
        Table reference;
        Table commonImplementor;
        boolean commonImplementorFilled;
        boolean interfaceMergedIntoClass;
        final ClassReader cls;
        List<ClassReader> mergedClasses;
        int index;
        boolean filled;
        boolean used;
        Table parent;
        List<Entry> entries = new ArrayList<>();
        List<MethodReference> implementors = new ArrayList<>();
        Map<MethodDescriptor, MethodReference> currentImplementors = new HashMap<>();
        Set<String> interfaces = new HashSet<>();
        private WasmGCVirtualTable buildResult;
        private boolean building;

        Table(ClassReader cls, int index) {
            this.cls = cls;
            this.index = index;
        }

        int depth = 0;

        void merge(Table other) {
            if (++depth == 50) {
                throw new IllegalStateException();
            }
            other.reference = this;
            if (parent == null) {
                parent = other.parent;
            } else if (other.parent == null) {
                other.parent = parent;
            } else {
                parent = parent.resolve();
                other.parent = other.parent.resolve();
                if (parent == other) {
                    parent = other.parent;
                } else if (this == other.parent) {
                    other.parent = parent;
                } else if (parent != other.parent) {
                    if (!parent.cls.hasModifier(ElementModifier.INTERFACE)) {
                        merge(other.parent);
                        other.parent = parent;
                    } else if (!other.parent.cls.hasModifier(ElementModifier.INTERFACE)) {
                        merge(parent);
                        parent = other.parent;
                    } else if (parent.cls.hasModifier(ElementModifier.INTERFACE)
                            && other.parent.cls.hasModifier(ElementModifier.INTERFACE)) {
                        parent.merge(other.parent);
                    }
                }
            }
            --depth;
        }

        WasmGCVirtualTable getBuildResult() {
            if (reference != null) {
                return resolve().getBuildResult();
            }
            if (buildResult == null) {
                if (building) {
                    throw new IllegalStateException();
                }
                building = true;
                buildResult = new WasmGCVirtualTable(parent != null ? parent.getBuildResult() : null, cls.getName(),
                        used, !cls.hasModifier(ElementModifier.ABSTRACT));
                buildResult.entries = entries.stream()
                        .map(Entry::getBuildResult)
                        .collect(Collectors.toList());
                buildResult.implementors = implementors.toArray(new MethodReference[0]);
                buildResult.fakeInterfaceRepresentative = cls.hasModifier(ElementModifier.INTERFACE);
            }
            return buildResult;
        }

        private boolean resolving;

        Table resolve() {
            if (reference != null) {
                if (resolving) {
                    throw new IllegalStateException();
                }
                resolving = true;
                reference = reference.resolve();
                resolving = false;
                return reference;
            }
            return this;
        }
    }

    private static class Entry {
        MethodDescriptor method;
        Table origin;
        int index;
        private WasmGCVirtualTableEntry buildResult;

        Entry(MethodDescriptor method, Table origin, int index) {
            this.method = method;
            this.origin = origin;
            this.index = index;
        }

        WasmGCVirtualTableEntry getBuildResult() {
            if (buildResult == null) {
                buildResult = new WasmGCVirtualTableEntry(origin.getBuildResult(), method, index);
            }
            return buildResult;
        }
    }
}
