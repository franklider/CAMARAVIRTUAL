package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Set;

/** Stub de XposedBridge. La implementación real la provee el framework Xposed en ejecución. */
public final class XposedBridge {
    private XposedBridge() { }

    /** Hookea un método/constructor específico. Devuelve el conjunto de unhooks. */
    public static Set<XC_MethodHook.Unhook> hookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException("Stub: la implementación real la inyecta LSPosed/LSPatch");
    }

    /** Hookea TODOS los métodos con ese nombre de la clase dada (cualquier firma). */
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        throw new UnsupportedOperationException("Stub: la implementación real la inyecta LSPosed/LSPatch");
    }

    /** Hookea todos los constructores de la clase dada. */
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        throw new UnsupportedOperationException("Stub: la implementación real la inyecta LSPosed/LSPatch");
    }

    /** Registra el módulo para recibir callbacks (necesario con LSPatch en modo integrado). */
    public static void log(String text) { }
    public static void log(Throwable t) { }
}
