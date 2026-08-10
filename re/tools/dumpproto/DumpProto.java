// DumpProto — enumerate every protobuf FileDescriptor reachable from the
// proto jars bundled with the Augment IntelliJ plugin.
//
// Usage:
//   javac DumpProto.java
//   java -cp .:<protojars> DumpProto <jarDir> [classPrefixFilter] > out/file-descriptors.txt
//
// It loads every *.class entry of every jar under <jarDir> via one
// URLClassLoader, and for any class exposing static Descriptors.FileDescriptor
// getDescriptor() (i.e. generated proto outer classes), prints its text-format
// FileDescriptorProto. This is the canonical wire schema: field numbers, types,
// and enum values — used to hand-write or generate Go connect/grpc stubs.
import com.google.protobuf.Descriptors;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class DumpProto {
  public static void main(String[] args) throws Exception {
    File jarsDir = new File(args[0]);
    String filter = args.length > 1 ? args[1] : "";
    File outDir = args.length > 2 ? new File(args[2]) : new File("descriptors");
    outDir.mkdirs();

    List<File> jars = new ArrayList<>();
    File[] files = jarsDir.listFiles();
    if (files == null) { System.err.println("no jar dir"); System.exit(2); }
    for (File f : files) if (f.getName().endsWith(".jar")) jars.add(f);

    List<URL> urls = new ArrayList<>();
    for (File j : jars) urls.add(j.toURI().toURL());
    try (URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), DumpProto.class.getClassLoader())) {
      List<String> outer = new ArrayList<>();
      for (File j : jars) {
        try (JarFile jf = new JarFile(j)) {
          Enumeration<JarEntry> en = jf.entries();
          while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            if (!n.endsWith(".class")) continue;
            String cn = n.replace('/', '.').replace(".class", "");
            if (cn.contains(".$")) continue;
            boolean isOuter = cn.endsWith("OuterClass");
            if (!isOuter) {
              if (filter.isEmpty()) continue;
              if (!cn.startsWith(filter) && !(filter + ".").startsWith(cn)) continue;
            }
            outer.add(cn);
          }
        }
      }
      Collections.sort(outer);
      List<Descriptors.FileDescriptor> seen = new ArrayList<>();
      for (String cn : outer) {
        if (!filter.isEmpty() && !cn.contains(filter)) continue;
        try {
          Class<?> c = Class.forName(cn, false, cl);
          Method m = c.getMethod("getDescriptor");
          Object d = m.invoke(null);
          if (d instanceof Descriptors.FileDescriptor) {
            Descriptors.FileDescriptor fd = (Descriptors.FileDescriptor) d;
            String fname = fd.getName();
            String safe = fname.replace('/', '_').replace('.', '_') + ".txt";
            java.nio.file.Files.write(new File(outDir, safe).toPath(),
                fd.toProto().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            seen.add(fd);
            System.out.println(fname + "\t" + cn);
          }
        } catch (Throwable t) {
          // not a proto outer class; skip quietly
        }
      }
      System.out.println("--- " + seen.size() + " file descriptors written ---");
    }
  }
}