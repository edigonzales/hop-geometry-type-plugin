package com.atolcd.hop.gis.imagen;

import java.util.Set;
import org.eclipse.imagen.spi.RegistryAllowListProvider;

/** Trusts the four registry entries shipped by the shared GeoTools 35.1 runtime. */
public final class GeoToolsRegistryAllowListProvider implements RegistryAllowListProvider {
  @Override
  public Set<String> getAllowedRegistryClasses() {
    return Set.of(
        "org.geotools.image.palette.ColorReductionDescriptor",
        "org.geotools.image.palette.ColorInversionDescriptor",
        "org.geotools.image.palette.ColorReductionCRIF",
        "org.geotools.image.palette.ColorInversionCRIF");
  }
}
