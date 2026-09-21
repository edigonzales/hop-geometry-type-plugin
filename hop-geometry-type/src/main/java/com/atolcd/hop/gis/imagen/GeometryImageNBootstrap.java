package com.atolcd.hop.gis.imagen;

import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.extension.ExtensionPoint;
import org.apache.hop.core.extension.IExtensionPoint;
import org.apache.hop.core.logging.ILogChannel;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.row.value.ValueMetaPluginType;
import org.apache.hop.core.variables.IVariables;
import org.eclipse.imagen.ImageN;

/** Initializes ImageN's cached service allowlist before GUI or pipeline threads first use it. */
@ExtensionPoint(
    id = "GeometryImageNBootstrap",
    extensionPointId = "HopEnvironmentAfterInit",
    description = "Initialize ImageN with the shared Geometry runtime services",
    classLoaderGroup = "sogeo-geometry")
public final class GeometryImageNBootstrap implements IExtensionPoint<PluginRegistry> {
  @Override
  public void callExtensionPoint(
      ILogChannel log, IVariables variables, PluginRegistry registry) throws HopException {
    var geometry = registry.findPluginWithId(ValueMetaPluginType.class, "43663879");
    if (geometry == null) {
      throw new HopException("Cannot initialize ImageN: Geometry value type is missing");
    }
    // Ensure the canonical runtime URLs are present, regardless of extension-point load order.
    ClassLoader loader = registry.getClassLoader(geometry);
    initialize(loader);
  }

  static void initialize(ClassLoader loader) {
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    try {
      thread.setContextClassLoader(loader);
      // ImageN uses ServiceLoader with the context loader and caches the result on first use.
      ImageN.getDefaultInstance();
    } finally {
      thread.setContextClassLoader(previous);
    }
  }
}
