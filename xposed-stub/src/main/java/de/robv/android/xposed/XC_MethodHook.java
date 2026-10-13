package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Stub del hook de método de Xposed. */
public abstract class XC_MethodHook {
    public XC_MethodHook() { }
    public XC_MethodHook(int priority) { }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable { }
    protected void afterHookedMethod(MethodHookParam param) throws Throwable { }

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        private Object result = null;
        private Throwable throwable = null;
        /** true si el método original NO debe ejecutarse (lo sustituye el hook). */
        public boolean returnEarly = false;

        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; this.returnEarly = true; }
        public Throwable getThrowable() { return throwable; }
        public void setThrowable(Throwable throwable) { this.throwable = throwable; this.returnEarly = true; }
        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }

    /** Parámetro extra usado por XposedBridge.hookAllMethods para identificar el hook. */
    public static class Unhook {
        public Member getHookedMethod() { return null; }
        public void unhook() { }
    }
}
