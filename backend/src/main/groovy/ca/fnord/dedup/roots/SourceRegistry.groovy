package ca.fnord.dedup.roots

import ca.fnord.dedup.roots.fs.*
import groovy.transform.CompileStatic
import jakarta.annotation.PostConstruct
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Files
import java.security.MessageDigest

@CompileStatic
@Service
class SourceRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(SourceRegistry)
    private final SourceProperties properties
    private final ReadOnlyFileAccess access
    private final JdbcTemplate jdbc
    private final ObjectMapper mapper
    private volatile List<SourceView> views = List.of()
    private String revision
    private volatile Map<UUID, FileMetadata> rootIdentities = Map.of()

    SourceRegistry(SourceProperties properties, ReadOnlyFileAccess access, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.properties = properties; this.access = access; this.jdbc = jdbc; this.mapper = mapper
    }

    @PostConstruct
    void initialize() {
        validateConfiguration(properties)
        String document = mapper.writeValueAsString([schemaVersion:1, sources:properties.sources])
        revision = HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(document.getBytes(StandardCharsets.UTF_8)))
        jdbc.update('INSERT INTO source_configuration(revision, schema_version, document) VALUES (?, 1, ?::jsonb) ON CONFLICT DO NOTHING', revision, document)
        refresh()
    }

    /** Explicit job/start/resume validation only; GET endpoints use the cached view. */
    synchronized void refresh() {
        List<SourceView> found = new ArrayList<>()
        Map<UUID, FileMetadata> identities = new LinkedHashMap<>()
        Set<UUID> overlapping = new HashSet<>()
        Map<UUID,String> backingPaths = new LinkedHashMap<>()
        for (SourceDefinition source : properties.sources) {
            String status = 'DISABLED', detail = 'Disabled in server configuration.'
            int excluded = 0
            if (source.enabled) {
                try (ReadOnlyFileAccess.Root root = access.openRoot(source)) {
                    MountTable table = MountTable.current()
                    rejectApplicationAliases(source, root.identity(), table)
                    backingPaths.put(source.id, table.backingPath(root.identity().mountId, source.containerPath))
                    status = 'AVAILABLE'; detail = 'Read-only source verified at startup.'
                    if (!source.crossMounts) excluded = table.beneath(source.containerPath).size()
                    identities.put(source.id, root.identity())
                } catch (SourceAccessException e) { status = e.code; detail = e.message }
                catch (IOException | SecurityException e) { status = 'UNAVAILABLE'; detail = 'The configured source is unavailable to this process.' }
                catch (LinkageError e) {
                    status = 'NATIVE_LINK_ERROR'
                    String message = e.message == null || e.message.isBlank() ? '(no native-loader message)' : e.message
                    detail = 'Linux native support failed to link: ' + e.class.simpleName + ': ' + message
                }
            }
            if (status != 'AVAILABLE' && status != 'DISABLED')
                LOG.warn('Source validation failed: key={} status={} detail={}', source.key, status, detail)
            found.add(view(source, status, detail, excluded))
        }
        for (int i = 0; i < properties.sources.size(); i++) {
            SourceDefinition a = properties.sources.get(i)
            if (!a.enabled) continue
            for (int j = i + 1; j < properties.sources.size(); j++) {
                SourceDefinition b = properties.sources.get(j)
                if (!b.enabled) continue
                FileMetadata x = identities.get(a.id), y = identities.get(b.id)
                boolean alias = x != null && y != null && x.inode == y.inode && x.deviceMajor == y.deviceMajor && x.deviceMinor == y.deviceMinor
                if (x != null && y != null && x.deviceMajor == y.deviceMajor && x.deviceMinor == y.deviceMinor) {
                    String physicalA = backingPaths.get(a.id), physicalB = backingPaths.get(b.id)
                    alias |= MountTable.isWithin(physicalA, physicalB) || MountTable.isWithin(physicalB, physicalA)
                }
                if (alias || MountTable.isWithin(a.containerPath, b.containerPath) || MountTable.isWithin(b.containerPath, a.containerPath)) {
                    overlapping.add(a.id); overlapping.add(b.id)
                }
            }
        }
        for (int i = 0; i < found.size(); i++) if (overlapping.contains(found.get(i).id))
            found.set(i, view(properties.sources.get(i), 'SOURCE_OVERLAP', 'This source overlaps or aliases another enabled source.', 0))
        views = List.copyOf(found)
        rootIdentities = Map.copyOf(identities)
    }

    List<SourceView> list() { views }
    String getRevision() { revision }
    FileMetadata identity(UUID id) { rootIdentities.get(id) }
    SourceDefinition definition(UUID id) {
        SourceDefinition s = properties.sources.find { SourceDefinition candidate -> candidate.id == id }
        if (s == null) return null
        new SourceDefinition(id:s.id, sourceInstanceId:s.sourceInstanceId, key:s.key, label:s.label,
            containerPath:s.containerPath, hostExportPrefix:s.hostExportPrefix, enabled:s.enabled, crossMounts:s.crossMounts)
    }

    ReadOnlyFileAccess.Root openValidated(SourceDefinition source) {
        ReadOnlyFileAccess.Root root = access.openRoot(source)
        try { rejectApplicationAliases(source, root.identity(), MountTable.current()); return root }
        catch (Throwable t) { root.close(); throw t }
    }

    private void rejectApplicationAliases(SourceDefinition source, FileMetadata identity, MountTable table) {
        NativeLinux linux = new NativeLinux()
        MountTable.Mount sourceMount = table.byId(identity.mountId)
        String sourceBacking = table.backingPath(identity.mountId, source.containerPath)
        for (String app : [properties.artifactDirectory, '/tmp', '/run/secrets', '/app', '/config']) {
            if (!Files.exists(Path.of(app))) continue
            int fd = linux.open(-100, app.getBytes(StandardCharsets.UTF_8), NativeLinux.PATH | NativeLinux.DIRECTORY, 0L)
            try {
                FileMetadata appIdentity = linux.metadata(fd)
                MountTable.Mount appMount = table.byId(appIdentity.mountId)
                String appBacking = table.backingPath(appIdentity.mountId, app)
                boolean overlap = sourceMount.device == appMount.device &&
                    (MountTable.isWithin(sourceBacking, appBacking) || MountTable.isWithin(appBacking, sourceBacking))
                if (source.crossMounts) for (MountTable.Mount child : table.beneath(source.containerPath)) {
                    overlap |= child.device == appMount.device &&
                        (MountTable.isWithin(child.root, appBacking) || MountTable.isWithin(appBacking, child.root))
                }
                if (overlap) throw new SourceAccessException('APPLICATION_STORAGE_OVERLAP', 'The source aliases or contains application-owned storage.')
            } finally { linux.close(fd) }
        }
    }

    static void validateConfiguration(SourceProperties properties) {
        if (properties.sources.size() > 100) throw new IllegalArgumentException('At most 100 source roots can be configured.')
        Set<UUID> ids = new HashSet<>()
        Set<String> keys = new HashSet<>()
        List<String> applicationPaths = [properties.artifactDirectory, '/tmp', '/run/secrets', '/app', '/config']
        for (SourceDefinition source : properties.sources) {
            if (source.id == null || source.sourceInstanceId == null || !ids.add(source.id))
                throw new IllegalArgumentException('Each source requires a unique ID and a stable source instance ID.')
            if (source.key == null || !(source.key ==~ '[a-z0-9][a-z0-9_-]{0,63}') || !keys.add(source.key))
                throw new IllegalArgumentException('Each source requires a unique lowercase key.')
            if (source.label == null || source.label.isBlank() || source.label.length() > 200)
                throw new IllegalArgumentException('Each source requires a label of 1 to 200 characters.')
            if (source.containerPath == null || !source.containerPath.startsWith('/') ||
                Path.of(source.containerPath).normalize().toString() != source.containerPath)
                throw new IllegalArgumentException('Source container paths must be normalized absolute paths.')
            for (String app : applicationPaths) {
                if (MountTable.isWithin(app, source.containerPath) || MountTable.isWithin(source.containerPath, app))
                    throw new IllegalArgumentException('Source roots must not overlap application-owned storage.')
            }
        }
    }
    private static SourceView view(SourceDefinition s, String status, String detail, int excluded) {
        new SourceView(s.id,s.sourceInstanceId,s.key,s.label,s.containerPath,s.enabled,s.crossMounts,status,detail,excluded)
    }
}
