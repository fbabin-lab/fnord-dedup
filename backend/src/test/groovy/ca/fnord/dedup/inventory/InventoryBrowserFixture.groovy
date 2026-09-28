package ca.fnord.dedup.inventory

import ca.fnord.dedup.FnordApplication
import ca.fnord.dedup.roots.SourceRegistry
import org.springframework.boot.SpringApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.nio.file.*

/** Test classpath only. Mount validation is bypassed ONLY for newly generated fixtures. */
class InventoryBrowserFixture {
    static void main(String[] args) {
        if (System.getenv('FNORD_TEST_FIXTURE_MODE') != 'generated-only') throw new IllegalStateException('This test fixture requires FNORD_TEST_FIXTURE_MODE=generated-only and a disposable database.')
        SpringApplication.run([FnordApplication,Configuration] as Class[],args)
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Configuration {
        @Bean @Primary SourceRegistry fixtureRegistry(InventoryStore store) {
            Path fixture = Files.createTempDirectory('fnord-browser-generated-')
            Files.createDirectories(fixture.resolve('nested/empty'))
            1250.times { Files.writeString(fixture.resolve(String.format('file-%04d.txt',it)),'benign fixture') }
            Files.writeString(fixture.resolve('<img src=x onerror=alert(1)>'),'inert name')
            Files.createSymbolicLink(fixture.resolve('outside-link'),Path.of('/etc/passwd'))
            def registry = new InventoryIntegrationTest.FixtureRegistry(store)
            UUID id = registry.add(fixture)
            registry.definitions[id].label = 'Generated native fixture'
            Runtime.runtime.addShutdownHook(new Thread({
                try (def paths = Files.walk(fixture)) { paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
            }))
            registry
        }
    }
}
