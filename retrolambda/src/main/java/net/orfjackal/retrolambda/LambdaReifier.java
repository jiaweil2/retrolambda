// Copyright © 2013-2014 Esko Luontola <www.orfjackal.net>
// This software is released under the Apache License 2.0.
// The license text is at http://www.apache.org/licenses/LICENSE-2.0

package net.orfjackal.retrolambda;

import org.objectweb.asm.*;

import java.lang.invoke.*;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.*;

import static org.objectweb.asm.Opcodes.*;

public class LambdaReifier {

    // These globals are used for communicating with the Java agent which
    // is spying on the LambdaMetafactory's dynamically generated bytecode.
    // We expect only one class being processed at a time, so it should
    // be an error if these collections contain more than one element.
    private static final BlockingDeque<Class<?>> currentInvoker = new LinkedBlockingDeque<>(1);
    private static final BlockingDeque<Type> currentInvokedType = new LinkedBlockingDeque<>(1);
    private static final BlockingDeque<String> currentLambdaClass = new LinkedBlockingDeque<>(1);

    public static LambdaFactoryMethod reifyLambdaClass(Class<?> invoker, String invokedName, Type invokedType, Handle bsm, Object[] bsmArgs) {
        try {
            setInvoker(invoker);
            setInvokedType(invokedType);

            // Causes the lambda class to be loaded. Retrolambda's Java agent
            // will detect it, save it to a file and tell us (via the globals
            // in this class) that what the name of the lambda class was.
            callBootstrapMethod(invoker, invokedName, invokedType, bsm, bsmArgs);

            return getLambdaFactoryMethod();

        } catch (Throwable t) {
            throw new RuntimeException(t);
        } finally {
            resetGlobals();
        }
    }

    public static void setInvoker(Class<?> invoker) {
        currentInvoker.push(invoker);
    }

    private static void setInvokedType(Type invokedType) {
        currentInvokedType.push(invokedType);
    }

    public static void setLambdaClass(String lambdaClass) {
        currentLambdaClass.push(lambdaClass);
    }

    public static LambdaFactoryMethod getLambdaFactoryMethod() {
        String lambdaClass = currentLambdaClass.getFirst();
        Type invokedType = currentInvokedType.getFirst();
        Class<?> invoker = currentInvoker.getFirst();
        return new LambdaFactoryMethod(lambdaClass, invokedType, invoker);
    }

    private static void resetGlobals() {
        currentInvoker.clear();
        currentInvokedType.clear();
        currentLambdaClass.clear();
    }

    private static CallSite callBootstrapMethod(Class<?> invoker, String invokedName, Type invokedType, Handle bsm, Object[] bsmArgs) throws Throwable {
        ClassLoader cl = invoker.getClassLoader();
        MethodHandles.Lookup caller = getLookup(invoker);

        List<Object> args = new ArrayList<>();
        args.add(caller);
        args.add(invokedName);
        args.add(toMethodType(invokedType, cl));
        for (Object arg : bsmArgs) {
            args.add(asmToJdkType(arg, cl, caller));
        }

        MethodHandle bootstrapMethod = toMethodHandle(bsm, cl, caller);
        return (CallSite) bootstrapMethod.invokeWithArguments(args);
    }

    private static MethodHandles.Lookup getLookup(Class<?> targetClass) throws Exception {
        Constructor<MethodHandles.Lookup> ctor = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class);
        ctor.setAccessible(true);
        return ctor.newInstance(targetClass);
    }

    private static Object asmToJdkType(Object arg, ClassLoader classLoader, MethodHandles.Lookup caller) throws Exception {
        if (arg instanceof Type) {
            return toMethodType((Type) arg, classLoader);
        } else if (arg instanceof Handle) {
            return toMethodHandle((Handle) arg, classLoader, caller);
        } else {
            return arg;
        }
    }

    private static MethodType toMethodType(Type type, ClassLoader classLoader) {
        return MethodType.fromMethodDescriptorString(type.getInternalName(), classLoader);
    }

    private static MethodHandle toMethodHandle(Handle handle, ClassLoader classLoader, MethodHandles.Lookup lookup) throws Exception {
        MethodType type = MethodType.fromMethodDescriptorString(handle.getDesc(), classLoader);
        Class<?> owner = classLoader.loadClass(handle.getOwner().replace('/', '.'));

        switch (handle.getTag()) {
            case H_INVOKESTATIC:
                return lookup.findStatic(owner, handle.getName(), type);

            case H_INVOKEVIRTUAL:
            case H_INVOKEINTERFACE:
                return lookup.findVirtual(owner, handle.getName(), type);

            case H_INVOKESPECIAL:
                return lookup.findSpecial(owner, handle.getName(), type, owner);

            case H_NEWINVOKESPECIAL:
                return lookup.findConstructor(owner, type);

            default:
                throw new AssertionError("Unexpected handle type: " + handle);
        }
    }
}
