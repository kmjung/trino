/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.iceberg;

import com.google.common.collect.ImmutableMap;
import io.trino.spi.connector.Connector;
import io.trino.spi.connector.ConnectorFactory;
import io.trino.testing.TestingConnectorContext;
import org.testng.annotations.Test;

import java.util.Map;

import static com.google.common.collect.Iterables.getOnlyElement;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestIcebergPlugin
{
    @Test
    public void testCreateConnector()
    {
        ConnectorFactory factory = getConnectorFactory();
        // simplest possible configuration
        factory.create("test", Map.of("hive.metastore.uri", "thrift://foo:1234"), new TestingConnectorContext()).shutdown();
    }

    @Test
    public void testThriftMetastore()
    {
        ConnectorFactory factory = getConnectorFactory();

        factory.create(
                "test",
                Map.of(
                        "iceberg.catalog.type", "HIVE_METASTORE",
                        "hive.metastore.uri", "thrift://foo:1234"),
                new TestingConnectorContext())
                .shutdown();
    }

    @Test
    public void testHiveMetastoreRejected()
    {
        ConnectorFactory factory = getConnectorFactory();

        assertThatThrownBy(() -> factory.create(
                "test",
                Map.of(
                        "hive.metastore", "thrift",
                        "hive.metastore.uri", "thrift://foo:1234"),
                new TestingConnectorContext()))
                .hasMessageContaining("Error: Configuration property 'hive.metastore' was not used");
    }

    @Test
    public void testGlueMetastore()
    {
        ConnectorFactory factory = getConnectorFactory();

        assertThatThrownBy(() -> factory.create(
                "test",
                Map.of("iceberg.catalog.type", "glue"),
                new TestingConnectorContext()))
                .hasMessageContaining("Explicit bindings are required and HiveMetastore is not explicitly bound");

        assertThatThrownBy(() -> factory.create(
                "test",
                Map.of(
                        "iceberg.catalog.type", "glue",
                        "hive.metastore.uri", "thrift://foo:1234"),
                new TestingConnectorContext()))
                .hasMessageContaining("Error: Configuration property 'hive.metastore.uri' was not used");
    }

    @Test
    public void testRecordingMetastore()
    {
        ConnectorFactory factory = getConnectorFactory();

        // recording with thrift
        factory.create(
                "test",
                Map.of(
                        "iceberg.catalog.type", "HIVE_METASTORE",
                        "hive.metastore.uri", "thrift://foo:1234",
                        "hive.metastore-recording-path", "/tmp"),
                new TestingConnectorContext())
                .shutdown();

        // recording with glue
        assertThatThrownBy(() -> factory.create(
                "test",
                Map.of(
                        "iceberg.catalog.type", "glue",
                        "hive.metastore.glue.region", "us-east-2",
                        "hive.metastore-recording-path", "/tmp"),
                new TestingConnectorContext()))
                .hasMessageContaining("Configuration property 'hive.metastore-recording-path' was not used")
                .hasMessageContaining("Configuration property 'hive.metastore.glue.region' was not used");
    }

    @Test
    public void testAllowAllAccessControl()
    {
        ConnectorFactory connectorFactory = getConnectorFactory();

        connectorFactory.create(
                "test",
                ImmutableMap.<String, String>builder()
                        .put("iceberg.catalog.type", "HIVE_METASTORE")
                        .put("hive.metastore.uri", "thrift://foo:1234")
                        .put("iceberg.security", "allow-all")
                        .build(),
                new TestingConnectorContext())
                .shutdown();
    }

    @Test
    public void testReadOnlyAllAccessControl()
    {
        ConnectorFactory connectorFactory = getConnectorFactory();

        connectorFactory.create(
                "test",
                ImmutableMap.<String, String>builder()
                        .put("iceberg.catalog.type", "HIVE_METASTORE")
                        .put("hive.metastore.uri", "thrift://foo:1234")
                        .put("iceberg.security", "read-only")
                        .build(),
                new TestingConnectorContext())
                .shutdown();
    }

    @Test
    public void testSystemAccessControl()
    {
        ConnectorFactory connectorFactory = getConnectorFactory();

        Connector connector = connectorFactory.create(
                "test",
                ImmutableMap.<String, String>builder()
                        .put("iceberg.catalog.type", "HIVE_METASTORE")
                        .put("hive.metastore.uri", "thrift://foo:1234")
                        .put("iceberg.security", "system")
                        .build(),
                new TestingConnectorContext());
        assertThatThrownBy(connector::getAccessControl).isInstanceOf(UnsupportedOperationException.class);
        connector.shutdown();
    }

    private static ConnectorFactory getConnectorFactory()
    {
        return getOnlyElement(new IcebergPlugin().getConnectorFactories());
    }
}
