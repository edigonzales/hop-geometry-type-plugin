package com.atolcd.hop.gis.geometry.codec;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeoJsonIsolatedRuntimeTest {
  @Test void coldRuntimeWorksWithoutVisibleIndriyaServices(@TempDir Path temp) throws Exception {
    Path classpath = temp.resolve("classpath.txt"), output = temp.resolve("probe.log");
    Files.writeString(classpath,System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")));
    String launcher = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",
        System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),
        "-DEPSG-HSQL.directory="+temp.resolve("epsg"),"-cp",launcher,
        ColdJsonProbe.class.getName(),classpath.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
    if (!process.waitFor(45,TimeUnit.SECONDS)) { process.destroyForcibly();throw new AssertionError("Cold JSON-FG runtime timed out"); }
    assertThat(process.exitValue()).withFailMessage(Files.readString(output)).isZero();
    assertThat(Files.readString(output)).contains("Cold JSON-FG runtime passed");
  }

  /** Reflection-only entry point: no plugin types are visible to its outer loader. */
  public static final class ColdJsonProbe {
    public static void main(String[] args) throws Exception {
      URL[] urls = Arrays.stream(Files.readString(Path.of(args[0])).split(File.pathSeparator)).map(path -> {
        try { return new File(path).toURI().toURL(); }
        catch (Exception e) { throw new IllegalArgumentException(e); }
      }).toArray(URL[]::new);
      ClassLoader original = Thread.currentThread().getContextClassLoader();
      try (ServiceHidingClassLoader loader = new ServiceHidingClassLoader(urls)) {
        Class<?> codecClass = loader.loadClass("com.atolcd.hop.gis.geometry.codec.GeometryCodec");
        Class<?> limitsClass = loader.loadClass("com.atolcd.hop.gis.geometry.codec.GeometryReadLimits");
        Class<?> optionsClass = loader.loadClass("com.atolcd.hop.gis.geometry.codec.GeoJsonReadOptions");
        Object limits = limitsClass.getMethod("defaults").invoke(null);
        Object codec = codecClass.getConstructor(limitsClass).newInstance(limits);
        Object result = codecClass.getMethod("readGeoJson",String.class,optionsClass).invoke(codec,
            "{\"type\":\"Point\",\"coordRefSys\":\"EPSG:4326\",\"conformsTo\":[\"http://www.opengis.net/spec/json-fg-1/1.0/conf/core\"],\"coordinates\":[47,7]}",null);
        Object geometry = result.getClass().getMethod("geometry").invoke(result);
        double x = (double)geometry.getClass().getMethod("getX").invoke(geometry);
        double y = (double)geometry.getClass().getMethod("getY").invoke(geometry);
        if (x != 7 || y != 47) throw new AssertionError("Authority axes were not mapped to XY");
        Object numbers = loader.loadClass("tech.units.indriya.function.Calculus").getMethod("currentNumberSystem").invoke(null);
        if (!loader.hidden || !numbers.getClass().getName().endsWith("DefaultNumberSystem"))
          throw new AssertionError("Indriya fallback was not exercised");
        if (Thread.currentThread().getContextClassLoader()!=original) throw new AssertionError("TCCL was not restored");
      }
      System.out.println("Cold JSON-FG runtime passed");
    }
  }
  private static final class ServiceHidingClassLoader extends URLClassLoader {
    private boolean hidden;
    private ServiceHidingClassLoader(URL[] urls) { super(urls,ClassLoader.getPlatformClassLoader()); }
    @Override public URL getResource(String name) {
      if (name.equals("META-INF/services/tech.units.indriya.spi.NumberSystem")) { hidden=true;return null; }
      return super.getResource(name);
    }
    @Override public Enumeration<URL> getResources(String name) throws IOException {
      if (name.equals("META-INF/services/tech.units.indriya.spi.NumberSystem")) { hidden=true;return Collections.emptyEnumeration(); }
      return super.getResources(name);
    }
  }
}
