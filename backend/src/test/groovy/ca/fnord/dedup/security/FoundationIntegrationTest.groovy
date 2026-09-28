package ca.fnord.dedup.security

import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.testcontainers.postgresql.PostgreSQLContainer
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*
import static org.junit.jupiter.api.Assertions.*

@Tag('postgres')
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation)
class FoundationIntegrationTest {
    static final String PASSWORD = 'benign-fixture-password-2026'
    static final String HASH = new BCryptPasswordEncoder(12).encode(PASSWORD)
    static PostgreSQLContainer database
    @Autowired MockMvc mvc
    @Autowired JdbcTemplate jdbc

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry r) {
        String url = System.getenv('FNORD_TEST_DB_URL')
        if (!url) {
            database = new PostgreSQLContainer('postgres:18.6-bookworm@sha256:3725f4e2499eef5134592b3b4ab79a543ed7f8e533b05b5b637af926630f6650')
                .withDatabaseName('fnord').withUsername('fnord').withPassword('generated-disposable-test-password')
            database.start()
            url = database.jdbcUrl
        }
        final String jdbcUrl = url
        r.add('spring.datasource.url', { jdbcUrl })
        r.add('spring.datasource.username', { System.getenv('FNORD_TEST_DB_USER') ?: 'fnord' })
        r.add('spring.datasource.password', { System.getenv('FNORD_TEST_DB_PASSWORD') ?: 'generated-disposable-test-password' })
        r.add('spring.datasource.hikari.maximum-pool-size', { '5' })
        r.add('fnord.security.password-hash', { HASH })
    }
    @AfterAll static void stopDatabase() { database?.stop() }

    RequestPostProcessor token() {
        def cookie = mvc.perform(get('/api/v1/session')).andReturn().response.getCookie('XSRF-TOKEN')
        assertNotNull(cookie)
        return { request ->
            request.setCookies(cookie)
            request.addHeader('X-XSRF-TOKEN',cookie.value)
            request
        } as RequestPostProcessor
    }

    @Test @Order(1) void schemaAndAnonymousAccess() {
        assertEquals(1,jdbc.queryForObject('SELECT count(*) FROM flyway_schema_history WHERE success',Integer))
        assertEquals(1,jdbc.queryForObject('SELECT count(*) FROM source_configuration',Integer))
        for (String path : ['/api/v1/sources','/api/v1/system/info','/api/v1/scans','/api/v1/observations/anything','/api/v1/exports/anything/download'])
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath('$.code').value('AUTHENTICATION_REQUIRED'))
        mvc.perform(get('/api/v1/session')).andExpect(status().isOk()).andExpect(jsonPath('$.authenticated').value(false))
        mvc.perform(get('/api/v1/system/health')).andExpect(status().isOk()).andExpect(jsonPath('$.status').value('UP'))
    }
    @Test @Order(2) void realLoginRequiresCsrfAndPersistsSecurityAudit() {
        mvc.perform(post('/api/v1/session/login').param('username','operator').param('password',PASSWORD))
            .andExpect(status().isForbidden())
        def result = mvc.perform(post('/api/v1/session/login').with(token()).param('username','operator').param('password',PASSWORD))
            .andExpect(status().isNoContent()).andReturn()
        MockHttpSession session = (MockHttpSession)result.request.getSession(false)
        assertNotNull(session)
        mvc.perform(get('/api/v1/session').session(session)).andExpect(jsonPath('$.authenticated').value(true)).andExpect(jsonPath('$.username').value('operator'))
        mvc.perform(get('/api/v1/system/info').session(session)).andExpect(status().isOk()).andExpect(jsonPath('$.scanAvailable').value(false))
        mvc.perform(get('/api/v1/sources').session(session)).andExpect(status().isOk()).andExpect(jsonPath('$.sources').isEmpty())
        mvc.perform(post('/api/v1/session/logout').session(session)).andExpect(status().isForbidden())
        mvc.perform(post('/api/v1/session/logout').session(session).with(token())).andExpect(status().isNoContent())
        assertTrue(session.invalid)
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action='LOGIN_SUCCEEDED'",Integer) >= 1)
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action='LOGOUT'",Integer) >= 1)
    }
    @Test @Order(3) void noUnsafeRoutesAndNoCredentialedCors() {
        for (String route : ['/api/v1/files/delete','/api/v1/files/download','/api/v1/execute','/api/v1/sources']) {
            mvc.perform(delete(route).with(user('operator')).with(token())).andExpect(status().is4xxClientError())
        }
        mvc.perform(get('/api/v1/system/info').with(user('operator')).header('Origin','https://untrusted.example'))
            .andExpect(header().doesNotExist('Access-Control-Allow-Origin'))
        mvc.perform(get('/api/v1/session')).andExpect(header().exists('X-Correlation-ID'))
            .andExpect(header().string('X-Content-Type-Options','nosniff'))
    }
    @Test @Order(4) void invalidCredentialsAndLoginFloodAreRejected() {
        mvc.perform(post('/api/v1/session/login').with(token()).param('username','operator').param('password','wrong'))
            .andExpect(status().isUnauthorized())
        3.times {
            mvc.perform(post('/api/v1/session/login').with(token()).param('username','operator').param('password','wrong'))
        }
        mvc.perform(post('/api/v1/session/login').with(token()).param('username','operator').param('password',PASSWORD))
            .andExpect(status().isTooManyRequests()).andExpect(jsonPath('$.code').value('LOGIN_RATE_LIMIT'))
    }
}
