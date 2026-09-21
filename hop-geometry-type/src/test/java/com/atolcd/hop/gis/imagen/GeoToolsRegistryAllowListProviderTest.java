package com.atolcd.hop.gis.imagen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.eclipse.imagen.spi.RegistryAllowListProvider;
import org.junit.jupiter.api.Test;

class GeoToolsRegistryAllowListProviderTest {
  @Test
  void discoversExactGeoToolsRegistryEntries() {
    var provider = ServiceLoader.load(RegistryAllowListProvider.class).stream()
        .map(ServiceLoader.Provider::get)
        .filter(GeoToolsRegistryAllowListProvider.class::isInstance)
        .findFirst().orElseThrow();
    assertThat(provider.getAllowedRegistryClasses()).containsExactlyInAnyOrder(
        "org.geotools.image.palette.ColorReductionDescriptor",
        "org.geotools.image.palette.ColorInversionDescriptor",
        "org.geotools.image.palette.ColorReductionCRIF",
        "org.geotools.image.palette.ColorInversionCRIF");
  }

  @Test
  void registersDescriptorsAndFactoriesInFreshRuntime() throws Exception {
    List<URL> urls = new ArrayList<>();
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      urls.add(new File(entry).toURI().toURL());
    }
    List<String> rejected = new ArrayList<>();
    Logger logger = Logger.getLogger("org.eclipse.imagen.RegistryFileParser");
    Handler handler = new Handler() {
      public void publish(LogRecord record) {
        if (record.getMessage().contains("Rejected registry class")) {
          rejected.add(record.getMessage());
        }
      }
      public void flush() {}
      public void close() {}
    };
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    logger.addHandler(handler);
    try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
      // Start with a context loader that cannot see plugin services, as Hop's main thread does.
      Thread.currentThread().setContextClassLoader(ClassLoader.getPlatformClassLoader());
      Class<?> bootstrap = loader.loadClass("com.atolcd.hop.gis.imagen.GeometryImageNBootstrap");
      var initialize = bootstrap.getDeclaredMethod("initialize", ClassLoader.class);
      initialize.setAccessible(true);
      initialize.invoke(null, loader);
      assertThat(Thread.currentThread().getContextClassLoader())
          .isSameAs(ClassLoader.getPlatformClassLoader());
      Class<?> imagen = loader.loadClass("org.eclipse.imagen.ImageN");
      Object instance = imagen.getMethod("getDefaultInstance").invoke(null);
      Object registry = imagen.getMethod("getOperationRegistry").invoke(instance);
      Class<?> registryClass = loader.loadClass("org.eclipse.imagen.OperationRegistry");
      for (String operation : List.of("ColorReduction", "ColorInversion")) {
        Object descriptor = registryClass.getMethod("getDescriptor", String.class, String.class)
            .invoke(registry, "rendered", "org.geotools." + operation);
        Object factory = registryClass.getMethod("getFactory", String.class, String.class)
            .invoke(registry, "rendered", "org.geotools." + operation);
        assertThat(descriptor).isNotNull();
        assertThat(descriptor.getClass().getName())
            .isEqualTo("org.geotools.image.palette." + operation + "Descriptor");
        assertThat(factory).isNotNull();
        assertThat(factory.getClass().getName())
            .isEqualTo("org.geotools.image.palette." + operation + "CRIF");
      }
      assertThat(rejected).isEmpty();
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
      logger.removeHandler(handler);
    }
  }
}
