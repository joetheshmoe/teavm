/*
 *  Copyright 2025 Alexey Andreev.
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
package org.teavm.backend.wasm.transformation.gc;

import org.teavm.backend.wasm.BaseWasmFunctionRepository;
import org.teavm.backend.wasm.generate.gc.classes.WasmGCClassInfo;
import org.teavm.backend.wasm.generate.gc.classes.WasmGCClassInfoProvider;
import org.teavm.backend.wasm.model.WasmArray;
import org.teavm.backend.wasm.model.WasmFunction;
import org.teavm.backend.wasm.model.WasmLocal;
import org.teavm.backend.wasm.model.WasmStructure;
import org.teavm.backend.wasm.model.WasmType;
import org.teavm.backend.wasm.model.expression.WasmCall;
import org.teavm.backend.wasm.model.expression.WasmCast;
import org.teavm.backend.wasm.model.expression.WasmExpression;
import org.teavm.backend.wasm.model.expression.WasmGetLocal;
import org.teavm.backend.wasm.model.expression.WasmNullConstant;
import org.teavm.backend.wasm.model.expression.WasmPush;
import org.teavm.backend.wasm.model.expression.WasmStructGet;
import org.teavm.backend.wasm.model.expression.WasmStructNew;
import org.teavm.model.MethodReference;
import org.teavm.model.ValueType;
import org.teavm.runtime.Fiber;

class CoroutineFunctions {
    private BaseWasmFunctionRepository functions;
    private WasmGCClassInfoProvider classInfoProvider;

    private WasmFunction pushIntCache;
    private WasmFunction pushLongCache;
    private WasmFunction pushFloatCache;
    private WasmFunction pushDoubleCache;
    private WasmFunction pushObjectCache;

    private WasmFunction popIntCache;
    private WasmFunction popLongCache;
    private WasmFunction popFloatCache;
    private WasmFunction popDoubleCache;
    private WasmFunction popObjectCache;

    private WasmFunction isResumingCache;
    private WasmFunction isSuspendingCache;

    private WasmFunction currentFiberCache;

    private WasmStructure objectStructureCache;

    CoroutineFunctions(BaseWasmFunctionRepository functions, WasmGCClassInfoProvider classInfoProvider) {
        this.functions = functions;
        this.classInfoProvider = classInfoProvider;
    }

    WasmFunction pushInt() {
        if (pushIntCache == null) {
            pushIntCache = functions.forInstanceMethod(new MethodReference(Fiber.class, "push", int.class,
                    void.class));
        }
        return pushIntCache;
    }

    WasmFunction pushLong() {
        if (pushLongCache == null) {
            pushLongCache = functions.forInstanceMethod(new MethodReference(Fiber.class, "push", long.class,
                    void.class));
        }
        return pushLongCache;
    }

    WasmFunction pushFloat() {
        if (pushFloatCache == null) {
            pushFloatCache = functions.forInstanceMethod(new MethodReference(Fiber.class, "push", float.class,
                    void.class));
        }
        return pushFloatCache;
    }

    WasmFunction pushDouble() {
        if (pushDoubleCache == null) {
            pushDoubleCache = functions.forInstanceMethod(new MethodReference(Fiber.class, "push", double.class,
                    void.class));
        }
        return pushDoubleCache;
    }

    WasmFunction pushObject() {
        if (pushObjectCache == null) {
            pushObjectCache = functions.forInstanceMethod(new MethodReference(Fiber.class, "push", Object.class,
                    void.class));
        }
        return pushObjectCache;
    }

    WasmFunction popInt() {
        if (popIntCache == null) {
            popIntCache = functions.forInstanceMethod(new MethodReference(Fiber.class, "popInt", int.class));
        }
        return popIntCache;
    }

    WasmFunction popLong() {
        if (popLongCache == null) {
            popLongCache = functions.forInstanceMethod(new MethodReference(Fiber.class, "popLong", long.class));
        }
        return popLongCache;
    }

    WasmFunction popFloat() {
        if (popFloatCache == null) {
            popFloatCache = functions.forInstanceMethod(new MethodReference(Fiber.class, "popFloat", float.class));
        }
        return popFloatCache;
    }

    WasmFunction popDouble() {
        if (popDoubleCache == null) {
            popDoubleCache = functions.forInstanceMethod(new MethodReference(Fiber.class, "popDouble", double.class));
        }
        return popDoubleCache;
    }

    WasmFunction popObject() {
        if (popObjectCache == null) {
            popObjectCache = functions.forInstanceMethod(new MethodReference(Fiber.class, "popObject", Object.class));
        }
        return popObjectCache;
    }

    WasmFunction isResuming() {
        if (isResumingCache == null) {
            isResumingCache = functions.forInstanceMethod(new MethodReference(Fiber.class, "isResuming",
                    boolean.class));
        }
        return isResumingCache;
    }

    WasmFunction isSuspending() {
        if (isSuspendingCache == null) {
            isSuspendingCache = functions.forInstanceMethod(new MethodReference(Fiber.class, "isSuspending",
                    boolean.class));
        }
        return isSuspendingCache;
    }

    WasmFunction currentFiber() {
        if (currentFiberCache == null) {
            currentFiberCache = functions.forStaticMethod(new MethodReference(Fiber.class, "current", Fiber.class));
        }
        return currentFiberCache;
    }

    WasmStructure objectStructure() {
        if (objectStructureCache == null) {
            objectStructureCache = classInfoProvider.getClassInfo("java.lang.Object").getStructure();
        }
        return objectStructureCache;
    }

    WasmExpression restoreValue(WasmType type, WasmLocal fiberLocal) {
        if (type instanceof WasmType.Number) {
            switch (((WasmType.Number) type).number) {
                case INT32:
                    return new WasmCall(popInt(), new WasmGetLocal(fiberLocal));
                case INT64:
                    return new WasmCall(popLong(), new WasmGetLocal(fiberLocal));
                case FLOAT32:
                    return new WasmPush(new WasmCall(popFloat(), new WasmGetLocal(fiberLocal)));
                case FLOAT64:
                    new WasmPush(new WasmCall(popDouble(), new WasmGetLocal(fiberLocal)));
            }
            throw new IllegalArgumentException();
        } else if (type instanceof WasmType.Reference) {
            var refType = (WasmType.Reference) type;
            WasmExpression obj = new WasmCall(popObject(), new WasmGetLocal(fiberLocal));
            if (type instanceof WasmType.CompositeReference) {
                var composite = ((WasmType.CompositeReference) type).composite;
                if (composite instanceof WasmArray) {
                    var array = (WasmArray) composite;
                    var arrayElement = array.getElementType().asUnpackedType();
                    var classInfo = arrayClassInfo(arrayElement);
                    var arrayStruct = classInfo.getStructure();
                    obj = new WasmCast(obj, arrayStruct.getNonNullReference());
                    obj = new WasmStructGet(arrayStruct, obj, WasmGCClassInfoProvider.ARRAY_DATA_FIELD_OFFSET);
                }
            }
            return new WasmCast(obj, refType);
        } else {
            throw new IllegalArgumentException();
        }
    }

    WasmExpression saveValue(WasmType type, WasmLocal fiberLocal, WasmExpression value) {
        if (type instanceof WasmType.Number) {
            switch (((WasmType.Number) type).number) {
                case INT32:
                    return new WasmCall(pushInt(), new WasmGetLocal(fiberLocal), value);
                case INT64:
                    return new WasmCall(pushLong(), new WasmGetLocal(fiberLocal), value);
                case FLOAT32:
                    return new WasmCall(pushFloat(), new WasmGetLocal(fiberLocal), value);
                case FLOAT64:
                    return new WasmCall(pushDouble(), new WasmGetLocal(fiberLocal), value);
            }
            throw new IllegalArgumentException();
        } else {
            if (type instanceof WasmType.CompositeReference) {
                var composite = ((WasmType.CompositeReference) type).composite;
                if (composite instanceof WasmArray) {
                    var array = (WasmArray) composite;
                    var arrayElement = array.getElementType().asUnpackedType();
                    var classInfo = arrayClassInfo(arrayElement);
                    var arrayStruct = classInfo.getStructure();
                    var wrapper = new WasmStructNew(arrayStruct);
                    wrapper.getInitializers().add(new WasmNullConstant(
                            classInfo.getVirtualTableStructure().getReference()));
                    wrapper.getInitializers().add(new WasmNullConstant(WasmType.Reference.EQ));
                    value = new WasmCast(value, array.getNonNullReference());
                    wrapper.getInitializers().add(value);
                    value = wrapper;
                }
            }
            return new WasmCall(pushObject(), new WasmGetLocal(fiberLocal), value);
        }
    }

    private WasmGCClassInfo arrayClassInfo(WasmType arrayElement) {
        if (arrayElement instanceof WasmType.Number) {
            switch (((WasmType.Number) arrayElement).number) {
                case INT32:
                    return classInfoProvider.getClassInfo(ValueType.arrayOf(ValueType.INTEGER));
                case INT64:
                    return classInfoProvider.getClassInfo(ValueType.arrayOf(ValueType.LONG));
                case FLOAT32:
                    return classInfoProvider.getClassInfo(ValueType.arrayOf(ValueType.FLOAT));
                case FLOAT64:
                    return classInfoProvider.getClassInfo(ValueType.arrayOf(ValueType.DOUBLE));
            }
        }
        return classInfoProvider.getClassInfo(ValueType.arrayOf(ValueType.object("java.lang.Object")));
    }
}
