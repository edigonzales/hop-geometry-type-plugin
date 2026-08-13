package com.atolcd.hop.core.row.value;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.hop.core.row.value.ValueMetaPlugin;
import org.junit.jupiter.api.Test;

class ValueMetaGeometryPluginContractTest {

  @Test
  void shouldUseSharedGeometryClassLoaderGroup() {
    ValueMetaPlugin plugin = ValueMetaGeometry.class.getAnnotation(ValueMetaPlugin.class);

    assertThat(plugin).isNotNull();
    assertThat(plugin.id()).isEqualTo(String.valueOf(ValueMetaGeometry.TYPE_GEOMETRY));
    assertThat(plugin.classLoaderGroup()).isEqualTo("sogeo-geometry");
  }
}
