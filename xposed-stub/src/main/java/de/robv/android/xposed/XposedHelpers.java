package de.robv.android.xposed;

/** Stub de utilidades Xposed para encontrar clases, campos y métodos por reflexión. */
public final class XposedHelpers {
    private XposedHelpers() { }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException("Stub");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            String className, ClassLoader classLoader, String methodName,
            Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("Stub");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("Stub");
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(
            String className, ClassLoader classLoader, Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("Stub");
    }

    public static Object getObjectField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("Stub");
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        throw new UnsupportedOperationException("Stub");
    }
}
