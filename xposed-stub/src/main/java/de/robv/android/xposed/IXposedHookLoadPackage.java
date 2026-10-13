package de.robv.android.xposed;

import android.app.Application;

/**
 * Stub de la interfaz principal de un módulo Xposed.
 * La implementación real la inyecta LSPosed (root) o LSPatch (sin root) en el proceso objetivo.
 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;

    class XC_LoadPackage {
        public static class LoadPackageParam {
            public String packageName;
            public String processName;
            public ClassLoader classLoader;
            public Application app;
            public boolean isFirstPackage;
        }
    }
}
