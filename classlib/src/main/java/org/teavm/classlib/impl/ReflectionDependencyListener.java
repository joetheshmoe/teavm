/*
 *  Copyright 2016 Alexey Andreev.
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
package org.teavm.classlib.impl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.teavm.classlib.ReflectionContext;
import org.teavm.classlib.ReflectionSupplier;
import org.teavm.classlib.impl.reflection.ClassList;
import org.teavm.classlib.impl.reflection.FieldInfo;
import org.teavm.classlib.impl.reflection.MethodInfo;
import org.teavm.dependency.AbstractDependencyListener;
import org.teavm.dependency.DependencyAgent;
import org.teavm.dependency.DependencyConsumer;
import org.teavm.dependency.DependencyNode;
import org.teavm.dependency.DependencyType;
import org.teavm.dependency.FieldDependency;
import org.teavm.dependency.MethodDependency;
import org.teavm.model.AccessLevel;
import org.teavm.model.CallLocation;
import org.teavm.model.ClassReader;
import org.teavm.model.ClassReaderSource;
import org.teavm.model.ElementModifier;
import org.teavm.model.FieldReader;
import org.teavm.model.FieldReference;
import org.teavm.model.MemberReader;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodReader;
import org.teavm.model.MethodReference;
import org.teavm.model.ValueType;

public class ReflectionDependencyListener extends AbstractDependencyListener {
    private List<ReflectionSupplier> reflectionSuppliers;
    private MethodReference fieldGet = new MethodReference(Field.class, "getWithoutCheck", Object.class, Object.class);
    private MethodReference fieldSet = new MethodReference(Field.class, "setWithoutCheck", Object.class, Object.class,
            void.class);
    private MethodReference newInstance = new MethodReference(Constructor.class, "newInstance", Object[].class,
            Object.class);
    private MethodReference invokeMethod = new MethodReference(Method.class, "invoke", Object.class, Object[].class,
            Object.class);
    private MethodReference getFields = new MethodReference(Class.class, "getDeclaredFields", Field[].class);
    private MethodReference getConstructors = new MethodReference(Class.class, "getDeclaredConstructors",
            Constructor[].class);
    private MethodReference getMethods = new MethodReference(Class.class, "getDeclaredMethods",
            Method[].class);
    private MethodReference forName = new MethodReference(Class.class, "forName", String.class, Boolean.class,
            ClassLoader.class, Class.class);
    private MethodReference classNewInstance = new MethodReference(Class.class, "newInstance", Object.class);
    private MethodReference forNameShort = new MethodReference(Class.class, "forName", String.class, Class.class);
    private MethodReference fieldGetType = new MethodReference(Field.class, "getType", Class.class);
    private MethodReference fieldGetName = new MethodReference(Field.class, "getName", String.class);
    private MethodReference methodGetReturnType = new MethodReference(Method.class, "getReturnType", Class.class);
    private MethodReference methodGetParameterTypes = new MethodReference(Method.class, "getParameterTypes",
            Class[].class);
    private MethodReference constructorGetParameterTypes = new MethodReference(Constructor.class, "getParameterTypes",
            Class[].class);
    private Map<String, Set<String>> accessibleFieldCache = new LinkedHashMap<>();
    private Map<String, Set<MethodDescriptor>> accessibleMethodCache = new LinkedHashMap<>();
    private Set<String> classesWithReflectableFields = new LinkedHashSet<>();
    private Set<String> classesWithReflectableMethods = new LinkedHashSet<>();
    private DependencyNode allClasses;
    private DependencyNode typesInReflectableSignaturesNode;
    private Set<MethodReference> virtualMethods = new HashSet<>();
    private Set<MethodReference> virtualCallSites = new HashSet<>();
    private Set<String> classesFoundByName = new HashSet<>();

    private boolean getReached;
    private boolean setReached;
    private boolean callReached;

    public ReflectionDependencyListener(List<ReflectionSupplier> reflectionSuppliers) {
        this.reflectionSuppliers = reflectionSuppliers;
    }

    public boolean isVirtual(MethodReference methodRef) {
        return virtualMethods.contains(methodRef);
    }

    public Collection<MethodReference> getVirtualCallSites() {
        return virtualCallSites;
    }

    @Override
    public void started(DependencyAgent agent) {
        allClasses = agent.createNode();
        typesInReflectableSignaturesNode = agent.createNode();

        var constructorParamTypes = agent.linkField(new FieldReference(Constructor.class.getName(), "parameterTypes"))
                .getValue();
        constructorParamTypes.getArrayItem().propagate(agent.getType("java.lang.Class"));
        typesInReflectableSignaturesNode.connect(constructorParamTypes.getArrayItem().getClassValueNode());

        var methodParamTypes = agent.linkField(new FieldReference(Method.class.getName(), "parameterTypes"))
                .getValue();
        methodParamTypes.getArrayItem().propagate(agent.getType("java.lang.Class"));
        typesInReflectableSignaturesNode.connect(methodParamTypes.getArrayItem().getClassValueNode());

        agent.linkMethod(new MethodReference(FieldInfo.class, "name", String.class)).getResult()
                .propagate(agent.getType("java.lang.String"));
        agent.linkMethod(new MethodReference(FieldInfo.class, "type", Class.class)).getResult()
                .propagate(agent.getType("java.lang.Class"));

        agent.linkMethod(new MethodReference(MethodInfo.class, "name", String.class)).getResult()
                .propagate(agent.getType("java.lang.String"));
        agent.linkMethod(new MethodReference(MethodInfo.class, "returnType", Class.class)).getResult()
                .propagate(agent.getType("java.lang.Class"));
        agent.linkMethod(new MethodReference(ClassList.class, "get", int.class, Class.class)).getResult()
                .propagate(agent.getType("java.lang.Class"));

        var context = new ReflectionContextImpl(agent);
        for (var reflectionSupplier : reflectionSuppliers) {
            for (var className : reflectionSupplier.getClassesFoundByName(context)) {
                if (classesFoundByName.add(className)) {
                    agent.linkClass(className);
                }
            }
        }
        if (!classesFoundByName.isEmpty()) {
            var getName = agent.linkMethod(new MethodReference(Class.class, "getName", String.class));
            getName.getVariable(0).propagate(agent.getType("java.lang.Class"));
            for (var className : classesFoundByName) {
                getName.getVariable(0).getClassValueNode().propagate(agent.getType(className));
            }
        }
    }

    public boolean isGetReached() {
        return getReached;
    }

    public boolean isSetReached() {
        return setReached;
    }

    public boolean isCallReached() {
        return callReached;
    }

    public Set<String> getClassesWithReflectableFields() {
        return classesWithReflectableFields;
    }

    public Set<String> getClassesWithReflectableMethods() {
        return classesWithReflectableMethods;
    }

    public Set<String> getAccessibleFields(String className) {
        return accessibleFieldCache.get(className);
    }

    public Set<MethodDescriptor> getAccessibleMethods(String className) {
        return accessibleMethodCache.get(className);
    }

    @Override
    public void classReached(DependencyAgent agent, String className) {
        allClasses.propagate(agent.getType(className));
    }

    @Override
    public void methodReached(DependencyAgent agent, MethodDependency method) {
        if (method.getReference().equals(fieldGet)) {
            handleFieldGet(agent, method);
        } else if (method.getReference().equals(fieldSet)) {
            handleFieldSet(agent, method);
        } else if (method.getReference().equals(newInstance)) {
            handleNewInstance(agent, method);
        } else if (method.getReference().equals(classNewInstance)) {
            handleClassNewInstance(agent, method);
        } else if (method.getReference().equals(invokeMethod)) {
            handleInvoke(agent, method);
        } else if (method.getReference().equals(getFields)) {
            method.getVariable(0).getClassValueNode().addConsumer(type -> {
                if (!type.getName().startsWith("[")) {
                    classesWithReflectableFields.add(type.getName());

                    ClassReader cls = agent.getClassSource().get(type.getName());
                    if (cls != null) {
                        var skipPrivates = shouldSkipPrivates(cls);
                        for (FieldReader field : cls.getFields()) {
                            if (skipPrivates) {
                                if (field.getLevel() == AccessLevel.PRIVATE
                                        || field.getLevel() == AccessLevel.PACKAGE_PRIVATE) {
                                    continue;
                                }
                            }
                            linkType(agent, field.getType());
                        }
                    }
                }
            });
        } else if (method.getReference().equals(getConstructors) || method.getReference().equals(getMethods)) {
            method.getVariable(0).getClassValueNode().addConsumer(type -> {
                if (!type.getName().startsWith("[")) {
                    classesWithReflectableMethods.add(type.getName());

                    ClassReader cls = agent.getClassSource().get(type.getName());
                    if (cls != null) {
                        var skipPrivates = shouldSkipPrivates(cls);
                        for (MethodReader reflectableMethod : cls.getMethods()) {
                            if (skipPrivates) {
                                if (reflectableMethod.getLevel() == AccessLevel.PRIVATE
                                        || reflectableMethod.getLevel() == AccessLevel.PACKAGE_PRIVATE) {
                                    continue;
                                }
                            }
                            linkType(agent, reflectableMethod.getResultType());
                            for (ValueType param : reflectableMethod.getParameterTypes()) {
                                linkType(agent, param);
                            }
                        }
                    }
                }
            });
        } else if (method.getReference().equals(forName) || method.getReference().equals(forNameShort)) {
            method.getResult().propagate(agent.getType("java.lang.Class"));
            for (var className : classesFoundByName) {
                method.getResult().getClassValueNode().propagate(agent.getType(className));
            }
        } else if (method.getReference().equals(fieldGetType) || method.getReference().equals(methodGetReturnType)) {
            method.getResult().propagate(agent.getType("java.lang.Class"));
            typesInReflectableSignaturesNode.connect(method.getResult().getClassValueNode());
        } else if (method.getReference().equals(fieldGetName)) {
            method.getResult().propagate(agent.getType("java.lang.String"));
        } else if (method.getReference().equals(methodGetParameterTypes)
                || method.getReference().equals(constructorGetParameterTypes)) {
            method.getResult().propagate(agent.getType("[Ljava/lang/Class;"));
            method.getResult().getArrayItem().propagate(agent.getType("java.lang.Class"));
            typesInReflectableSignaturesNode.connect(method.getResult().getArrayItem().getClassValueNode());
        }
    }

    public static boolean shouldSkipPrivates(ClassReader cls) {
        return cls.getName().equals("java.lang.Object") || cls.getName().equals("java.lang.Class");
    }

    private void handleFieldGet(DependencyAgent agent, MethodDependency method) {
        CallLocation location = new CallLocation(method.getReference());
        DependencyNode classValueNode = agent.linkMethod(getFields)
                .addLocation(location)
                .getVariable(0).getClassValueNode();
        getReached = true;
        classValueNode.addConsumer(reflectedType -> {
            if (reflectedType.getName().startsWith("[")) {
                return;
            }
            Set<String> accessibleFields = getAccessibleFields(agent, reflectedType.getName());
            ClassReader cls = agent.getClassSource().get(reflectedType.getName());
            for (String fieldName : accessibleFields) {
                FieldReader field = cls.getField(fieldName);
                FieldDependency fieldDep = agent.linkField(field.getReference())
                        .addLocation(location);
                propagateGet(agent, field.getType(), fieldDep.getValue(), method.getResult(), location);
                linkClassIfNecessary(agent, field, location);
            }
        });
    }

    private void handleFieldSet(DependencyAgent agent, MethodDependency method) {
        CallLocation location = new CallLocation(method.getReference());
        DependencyNode classValueNode = agent.linkMethod(getFields)
                .addLocation(location)
                .getVariable(0).getClassValueNode();
        setReached = true;
        classValueNode.addConsumer(reflectedType -> {
            if (reflectedType.getName().startsWith("[")) {
                return;
            }

            Set<String> accessibleFields = getAccessibleFields(agent, reflectedType.getName());
            ClassReader cls = agent.getClassSource().get(reflectedType.getName());
            for (String fieldName : accessibleFields) {
                FieldReader field = cls.getField(fieldName);
                FieldDependency fieldDep = agent.linkField(field.getReference()).addLocation(location);
                propagateSet(agent, field.getType(), method.getVariable(2), fieldDep.getValue(), location);
                linkClassIfNecessary(agent, field, location);
            }
        });
    }

    private void handleNewInstance(DependencyAgent agent, MethodDependency method) {
        CallLocation location = new CallLocation(method.getReference());

        DependencyNode classValueNode = agent.linkMethod(getConstructors)
                .addLocation(location)
                .getVariable(0).getClassValueNode();
        callReached = true;
        classValueNode.addConsumer(reflectedType -> {
            if (reflectedType.getName().startsWith("[") || reflectedType.getName().startsWith("~")) {
                return;
            }

            ClassReader cls = agent.getClassSource().get(reflectedType.getName());
            if (cls == null || cls.hasModifier(ElementModifier.ABSTRACT)
                    || cls.hasModifier(ElementModifier.INTERFACE)) {
                return;
            }

            Set<MethodDescriptor> accessibleMethods = getAccessibleMethods(agent, reflectedType.getName());

            for (MethodDescriptor methodDescriptor : accessibleMethods) {
                if (!methodDescriptor.getName().equals("<init>")) {
                    continue;
                }
                MethodReader calledMethod = cls.getMethod(methodDescriptor);
                MethodDependency calledMethodDep = agent.linkMethod(calledMethod.getReference()).addLocation(location);
                calledMethodDep.use();
                for (int i = 0; i < calledMethod.parameterCount(); ++i) {
                    propagateSet(agent, methodDescriptor.parameterType(i), method.getVariable(1).getArrayItem(),
                            calledMethodDep.getVariable(i + 1), location);
                }
                calledMethodDep.getVariable(0).propagate(reflectedType);
                linkClassIfNecessary(agent, calledMethod, location);
            }

            method.getResult().propagate(reflectedType);
        });
    }

    private void handleClassNewInstance(DependencyAgent agent, MethodDependency method) {
        var location = new CallLocation(method.getReference());

        var classValueNode = method.getVariable(0).getClassValueNode();
        classValueNode.addConsumer(reflectedType -> {
            if (reflectedType.getName().startsWith("[") || reflectedType.getName().startsWith("~")) {
                return;
            }

            ClassReader cls = agent.getClassSource().get(reflectedType.getName());
            if (cls == null || cls.hasModifier(ElementModifier.ABSTRACT)
                    || cls.hasModifier(ElementModifier.INTERFACE)) {
                return;
            }

            var constructor = cls.getMethod(new MethodDescriptor("<init>", void.class));
            if (constructor == null || constructor.getProgram() == null) {
                return;
            }
            var constructorDep = agent.linkMethod(constructor.getReference()).addLocation(location);
            constructorDep.getVariable(0).propagate(reflectedType);
            constructorDep.use();

            method.getResult().propagate(reflectedType);
        });
    }

    private void handleInvoke(DependencyAgent agent, MethodDependency method) {
        CallLocation location = new CallLocation(method.getReference());
        DependencyNode classValueNode = agent.linkMethod(getMethods)
                .addLocation(location)
                .getVariable(0).getClassValueNode();
        callReached = true;
        classValueNode.addConsumer(reflectedType -> {
            if (reflectedType.getName().startsWith("[")) {
                return;
            }

            Set<MethodDescriptor> accessibleMethods = getAccessibleMethods(agent, reflectedType.getName());
            ClassReader cls = agent.getClassSource().get(reflectedType.getName());
            for (MethodDescriptor methodDescriptor : accessibleMethods) {
                if (methodDescriptor.getName().equals("<init>")) {
                    continue;
                }
                MethodReader calledMethod = cls.getMethod(methodDescriptor);
                if (calledMethod.hasModifier(ElementModifier.STATIC)
                        || calledMethod.hasModifier(ElementModifier.FINAL)
                        || calledMethod.getLevel() == AccessLevel.PRIVATE) {
                    var calledMethodDep = agent.linkMethod(calledMethod.getReference()).addLocation(location);
                    calledMethodDep.use();
                    for (int i = 0; i < calledMethod.parameterCount(); ++i) {
                        propagateSet(agent, methodDescriptor.parameterType(i), method.getVariable(2).getArrayItem(),
                                calledMethodDep.getVariable(i + 1), location);
                    }
                    propagateSet(agent, ValueType.object(reflectedType.getName()), method.getVariable(1),
                            calledMethodDep.getVariable(0), location);
                    propagateGet(agent, calledMethod.getResultType(), calledMethodDep.getResult(),
                            method.getResult(), location);
                    linkClassIfNecessary(agent, calledMethod, location);
                } else {
                    virtualCallSites.add(calledMethod.getReference());
                    method.getVariable(1).addConsumer(new VirtualCallConsumer(agent, calledMethod.getOwnerName(),
                            calledMethod.getDescriptor(), method, location));
                }
            }
        });
    }

    private class VirtualCallConsumer implements DependencyConsumer {
        private final DependencyAgent agent;
        private final String classFilter;
        private final MethodDescriptor methodDesc;
        private final MethodDependency invokeDep;
        private final CallLocation location;

        private Set<DependencyType> knownTypes = new HashSet<>();

        VirtualCallConsumer(DependencyAgent agent, String classFilter, MethodDescriptor methodDesc,
                MethodDependency invokeDep, CallLocation location) {
            this.agent = agent;
            this.classFilter = classFilter;
            this.methodDesc = methodDesc;
            this.invokeDep = invokeDep;
            this.location = location;
        }

        @Override
        public void consume(DependencyType type) {
            if (!knownTypes.add(type)) {
                return;
            }

            var className = type.getName();

            if (className.startsWith("[")) {
                className = "java.lang.Object";
            }

            if (!agent.getClassHierarchy().isSuperType(classFilter, className, false)) {
                return;
            }

            var methodDep = agent.linkMethod(className, methodDesc);
            methodDep.addLocation(location);

            if (!methodDep.isMissing()) {
                methodDep.use();
                virtualMethods.add(methodDep.getReference());
                methodDep.getVariable(0).propagate(type);
                for (var i = 0; i < methodDesc.parameterCount(); ++i) {
                    propagateSet(agent, methodDesc.parameterType(i), invokeDep.getVariable(2).getArrayItem(),
                            methodDep.getVariable(i + 1), location);
                }
                if (methodDep.getResult() != null) {
                    propagateGet(agent, methodDesc.getResultType(), methodDep.getResult(),
                            invokeDep.getResult(), location);
                }
            }
        }
    }

    private void linkType(DependencyAgent agent, ValueType type) {
        if (type instanceof ValueType.Object) {
            typesInReflectableSignaturesNode.propagate(agent.getType(((ValueType.Object) type).getClassName()));
        } else if (type instanceof ValueType.Array) {
            typesInReflectableSignaturesNode.propagate(agent.getType(type.toString()));
        } else {
            typesInReflectableSignaturesNode.propagate(agent.getType("~" + type));
        }
    }

    private void linkClassIfNecessary(DependencyAgent agent, MemberReader member, CallLocation location) {
        if (member.hasModifier(ElementModifier.STATIC)) {
            agent.linkClass(member.getOwnerName()).initClass(location);
        }
    }

    private Set<String> getAccessibleFields(DependencyAgent agent, String className) {
        return accessibleFieldCache.computeIfAbsent(className, key -> gatherAccessibleFields(agent, key));
    }

    private Set<MethodDescriptor> getAccessibleMethods(DependencyAgent agent, String className) {
        return accessibleMethodCache.computeIfAbsent(className, key -> gatherAccessibleMethods(agent, key));
    }

    private Set<String> gatherAccessibleFields(DependencyAgent agent, String className) {
        ReflectionContextImpl context = new ReflectionContextImpl(agent);
        Set<String> fields = new LinkedHashSet<>();
        for (ReflectionSupplier supplier : reflectionSuppliers) {
            fields.addAll(supplier.getAccessibleFields(context, className));
        }
        return fields;
    }

    private Set<MethodDescriptor> gatherAccessibleMethods(DependencyAgent agent, String className) {
        ReflectionContextImpl context = new ReflectionContextImpl(agent);
        Set<MethodDescriptor> methods = new LinkedHashSet<>();
        for (ReflectionSupplier supplier : reflectionSuppliers) {
            methods.addAll(supplier.getAccessibleMethods(context, className));
        }
        return methods;
    }

    private void propagateGet(DependencyAgent agent, ValueType type, DependencyNode sourceNode,
            DependencyNode targetNode, CallLocation location) {
        if (type instanceof ValueType.Primitive) {
            MethodReference boxMethod;
            switch (((ValueType.Primitive) type).getKind()) {
                case BOOLEAN:
                    boxMethod = new MethodReference(Boolean.class, "valueOf", boolean.class, Boolean.class);
                    break;
                case BYTE:
                    boxMethod = new MethodReference(Byte.class, "valueOf", byte.class, Byte.class);
                    break;
                case SHORT:
                    boxMethod = new MethodReference(Short.class, "valueOf", short.class, Short.class);
                    break;
                case CHARACTER:
                    boxMethod = new MethodReference(Character.class, "valueOf", char.class, Character.class);
                    break;
                case INTEGER:
                    boxMethod = new MethodReference(Integer.class, "valueOf", int.class, Integer.class);
                    break;
                case LONG:
                    boxMethod = new MethodReference(Long.class, "valueOf", long.class, Long.class);
                    break;
                case FLOAT:
                    boxMethod = new MethodReference(Float.class, "valueOf", float.class, Float.class);
                    break;
                case DOUBLE:
                    boxMethod = new MethodReference(Double.class, "valueOf", double.class, Double.class);
                    break;
                default:
                    throw new AssertionError(type.toString());
            }
            MethodDependency boxMethodDep = agent.linkMethod(boxMethod).addLocation(location);
            boxMethodDep.use();
            boxMethodDep.getResult().connect(targetNode);
        } else if (type instanceof ValueType.Array || type instanceof ValueType.Object) {
            sourceNode.connect(targetNode);
        }
    }

    private void propagateSet(DependencyAgent agent, ValueType type, DependencyNode sourceNode,
            DependencyNode targetNode, CallLocation location) {
        if (type instanceof ValueType.Primitive) {
            MethodReference unboxMethod;
            switch (((ValueType.Primitive) type).getKind()) {
                case BOOLEAN:
                    unboxMethod = new MethodReference(Boolean.class, "booleanValue", boolean.class);
                    break;
                case BYTE:
                    unboxMethod = new MethodReference(Byte.class, "byteValue", byte.class);
                    break;
                case SHORT:
                    unboxMethod = new MethodReference(Short.class, "shortValue", short.class);
                    break;
                case CHARACTER:
                    unboxMethod = new MethodReference(Character.class, "charValue", char.class);
                    break;
                case INTEGER:
                    unboxMethod = new MethodReference(Integer.class, "intValue", int.class);
                    break;
                case LONG:
                    unboxMethod = new MethodReference(Long.class, "longValue", long.class);
                    break;
                case FLOAT:
                    unboxMethod = new MethodReference(Float.class, "floatValue", float.class);
                    break;
                case DOUBLE:
                    unboxMethod = new MethodReference(Double.class, "doubleOf", double.class);
                    break;
                default:
                    throw new AssertionError(type.toString());
            }
            MethodDependency unboxMethodDep = agent.linkMethod(unboxMethod).addLocation(location);
            unboxMethodDep.propagate(0, unboxMethod.getClassName());
            unboxMethodDep.use();
            sourceNode.connect(unboxMethodDep.getResult());
        } else if (type instanceof ValueType.Array || type instanceof ValueType.Object) {
            sourceNode.connect(targetNode);
        }
    }

    private static class ReflectionContextImpl implements ReflectionContext {
        private DependencyAgent agent;

        public ReflectionContextImpl(DependencyAgent agent) {
            this.agent = agent;
        }

        @Override
        public ClassLoader getClassLoader() {
            return agent.getClassLoader();
        }

        @Override
        public ClassReaderSource getClassSource() {
            return agent.getClassSource();
        }
    }
}
