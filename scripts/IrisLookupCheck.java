// Checks that ShaderPackCompat can still find the shader mod API it reflects on.
//
// Not part of the Gradle build. Point it at a real Oculus or Iris jar:
//
//   javac -d out scripts/IrisLookupCheck.java
//   java -cp "out;oculus-mc1.20.1-1.8.0.jar" IrisLookupCheck
//
// It asserts the class name, that getInstance is static and no-arg, and that
// isShaderPackInUse is an instance method returning boolean. The point is to
// catch a release that moves or renames any of that, because the mod asks for
// it by name and would otherwise quietly fall back to its own shader forever.

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Mirrors ShaderPackCompat.look() against a real Oculus jar on the classpath. */
public class IrisLookupCheck {
    private static final String[] CANDIDATES = {
            "net.irisshaders.iris.api.v0.IrisApi",
            "net.coderbot.iris.api.v0.IrisApi"
    };

    public static void main(String[] args) {
        int found = 0;
        for (String name : CANDIDATES) {
            try {
                Class<?> type = Class.forName(name, false, IrisLookupCheck.class.getClassLoader());
                Method getInstance = type.getMethod("getInstance");
                Method inUse = type.getMethod("isShaderPackInUse");
                check("getInstance is static", Modifier.isStatic(getInstance.getModifiers()));
                check("getInstance takes no arguments", getInstance.getParameterCount() == 0);
                check("getInstance returns the api type", type.isAssignableFrom(getInstance.getReturnType()));
                check("isShaderPackInUse takes no arguments", inUse.getParameterCount() == 0);
                check("isShaderPackInUse returns boolean", inUse.getReturnType() == boolean.class);
                check("isShaderPackInUse is not static", !Modifier.isStatic(inUse.getModifiers()));
                System.out.println("resolved " + name);
                found++;
            } catch (ClassNotFoundException e) {
                System.out.println("absent (handled): " + name);
            } catch (NoSuchMethodException e) {
                throw new AssertionError("present but missing a method: " + name + " -> " + e.getMessage());
            }
        }
        if (found == 0) {
            throw new AssertionError("no candidate resolved against this jar");
        }
        System.out.println("OK: " + found + " candidate(s) resolved, every signature as expected");
    }

    private static void check(String what, boolean ok) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        System.out.println("  ok: " + what);
    }
}
