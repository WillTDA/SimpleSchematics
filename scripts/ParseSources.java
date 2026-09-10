import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;

/** Syntax validation only. This deliberately does not claim to type-check Forge APIs. */
class ParseSources {
    public static void main(String[] args)throws Exception{
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        if(compiler==null)throw new IllegalStateException("JDK 17 is required");
        var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var manager=compiler.getStandardFileManager(diagnostics,null,null);var paths=Files.walk(Path.of("src"))){
            var files=paths.filter(p->p.toString().endsWith(".java")).toList();
            var task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("--release","17","-proc:none"),null,manager.getJavaFileObjectsFromPaths(files));
            task.parse();
            for(var d:diagnostics.getDiagnostics())if(d.getKind()==Diagnostic.Kind.ERROR)throw new IllegalStateException(d.toString());
            System.out.println("PASS: parsed "+files.size()+" Java source files (syntax only)");
        }
    }
}
