package top.canyie.pine.utils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Helper to look up class members while being able to see hidden API members.
 * <p>
 * Strategy: try the normal reflection fast path first; if the member cannot be found
 * (on Android P+ a hidden member is simply filtered out, which surfaces as
 * {@link NoSuchMethodException}/{@link NoSuchFieldException}), fall back to
 * {@link PinePass} (pure-Java, Property route) which returns the full member list
 * including restricted ones.
 * <p>
 * If {@link PinePass} itself is unavailable or fails (e.g. future Android versions
 * tighten the Property route), we fall back to the normal reflection behavior,
 * so nothing crashes, only hidden members become invisible again.
 */
@SuppressWarnings("WeakerAccess")
public final class HiddenApiCompat {
    private HiddenApiCompat() {
    }

    /**
     * Get all declared methods (including hidden ones when possible).
     */
    public static Method[] getDeclaredMethods(Class<?> clazz) {
        try {
            return PinePass.getDeclaredMethods(clazz).toArray(new Method[0]);
        } catch (Throwable t) {
            return clazz.getDeclaredMethods();
        }
    }

    /**
     * Get all declared fields (including hidden ones when possible).
     */
    public static Field[] getDeclaredFields(Class<?> clazz) {
        try {
            return PinePass.getDeclaredFields(clazz).toArray(new Field[0]);
        } catch (Throwable t) {
            return clazz.getDeclaredFields();
        }
    }

    /**
     * Get all declared constructors (including hidden ones when possible).
     */
    public static Constructor<?>[] getDeclaredConstructors(Class<?> clazz) {
        try {
            return PinePass.getDeclaredConstructors(clazz).toArray(new Constructor<?>[0]);
        } catch (Throwable t) {
            return clazz.getDeclaredConstructors();
        }
    }

    /**
     * Find a declared method by name and parameter types, including hidden ones.
     *
     * @throws NoSuchMethodException if no such method exists
     */
    public static Method findDeclaredMethod(Class<?> clazz, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        try {
            return clazz.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            for (Method method : getDeclaredMethods(clazz)) {
                if (!method.getName().equals(name)) continue;
                Class<?>[] actual = method.getParameterTypes();
                if (actual.length != parameterTypes.length) continue;
                boolean match = true;
                for (int i = 0; i < parameterTypes.length; i++) {
                    if (parameterTypes[i] != actual[i]) {
                        match = false;
                        break;
                    }
                }
                if (match) return method;
            }
            throw e;
        }
    }

    /**
     * Find a declared field by name, including hidden ones.
     *
     * @throws NoSuchFieldException if no such field exists
     */
    public static Field findDeclaredField(Class<?> clazz, String name) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            for (Field field : getDeclaredFields(clazz)) {
                if (field.getName().equals(name)) return field;
            }
            throw e;
        }
    }

    /**
     * Find a declared constructor by parameter types, including hidden ones.
     *
     * @throws NoSuchMethodException if no such constructor exists
     */
    public static Constructor<?> findDeclaredConstructor(Class<?> clazz, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        try {
            return clazz.getDeclaredConstructor(parameterTypes);
        } catch (NoSuchMethodException e) {
            for (Constructor<?> constructor : getDeclaredConstructors(clazz)) {
                Class<?>[] actual = constructor.getParameterTypes();
                if (actual.length != parameterTypes.length) continue;
                boolean match = true;
                for (int i = 0; i < parameterTypes.length; i++) {
                    if (parameterTypes[i] != actual[i]) {
                        match = false;
                        break;
                    }
                }
                if (match) return constructor;
            }
            throw e;
        }
    }
}
